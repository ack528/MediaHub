# 05 · 方案 D:直接改造 Telegram 客户端 + 自建 MTProto 服务端

> **一句话**:既然要"和 Telegram 一样的体验",干脆 fork 现成的 Telegram Android 客户端,后端换成自建的 Telegram 兼容服务端,把文件夹导入成群聊。
> **结论(先说):不推荐作为落地方案。**本文档的价值是把这条路的真实成本摆清楚,并指出哪些部分可以借鉴(UI 行为、加载策略)。

## 1. 三条子路线

| 子路线 | 做法 | 能否达到目标 |
|---|---|---|
| **D1** | Fork **Telegram-FOSS**(GPLv2)或 **Telegram X**(GPLv3,基于 TDLib),后端用 **Teamgram**(Apache-2.0,Go 实现的 MTProto 服务端) | 理论可行,工程上几乎不可行,见 §3 |
| **D2** | Fork 客户端,**把网络层 `tgnet`/`ConnectionsManager` 替换成对自家 REST API 的适配器** | 要重写 TL 对象与上百个调用的语义,维护成本极高,见 §4 |
| **D3** | 用 TDLib 做客户端 | **不可行**:TDLib 只会说 MTProto,不能指向自定义 REST 后端(它面向 Telegram 基础设施) |

## 2. D1:Teamgram 路线的成本

Teamgram 的事实(来自其 README):
- 是 Go 编写的 Telegram 兼容服务端,Apache-2.0,实现 MTProto 2.0 与 API Layer 228,有 Android/iOS/桌面三个兼容客户端。
- 部署栈:**MySQL、Redis、etcd、Kafka、MinIO、FFmpeg**,用 Docker Compose 拉起;默认验证码为 `12345`。
- **社区版明确缺少频道、视频通话、机器人。**

对本项目的含义:

| 问题 | 说明 |
|---|---|
| **媒体需要导入** | Telegram 的消息媒体存放在服务端对象存储(MinIO)。要把"文件夹里的现成媒体"变成"群聊消息",得把文件**上传/拷贝**到 MinIO(或写一个把本地文件当作对象存储的适配器)。你的媒体盘多数已写满(F:、G:、H: 几乎 0 空间,其余也大多不足 100–250GB 可用),**不可能再复制一份**。 |
| **没有按需转码** | Telegram 的视频是"上传时已转好"的文件,服务端不会实时转码。用户要求的"实时预览、保持较小带宽、尽量多格式"需要另建一整套转码层,等于把方案 B 的工作量全做一遍,还要对接到 MTProto 的文件分块协议。 |
| **缩略图模型不同** | MTProto 中缩略图是消息体里内联的小图与多尺寸 `PhotoSize`;要为数百万文件**预生成并写入消息结构**,而方案 B/C 是按需生成。 |
| **群聊 = 频道/超级群** | 一个文件夹内数万到数十万条"消息"需要超级群/频道的容量与分页能力;社区版缺频道,普通群的历史与成员模型并不适合这种规模。 |
| **基础设施过重** | 为一个个人 NAS 常驻 6 个组件,Windows 上还要用 Docker/WSL2;故障面远大于一个 Windows 服务。 |
| **协议兼容性风险** | 官方客户端随官方协议层升级而变化,Teamgram 需要追 API Layer;fork 的客户端与自建服务端版本可能漂移。 |
| **新消息、已读、在线状态** | 这些聊天功能对本项目毫无价值,却是 MTProto 服务端必须实现的一部分。 |

## 3. D2:改造客户端网络层

**可行性评估**
- DrKLO/Telegram 的 `TMessagesProj` 是体量极大的代码库;网络层 `tgnet`(JNI/C++)与上层 Java 通过 TL 对象紧耦合。要换后端,需要实现一个"假的 `ConnectionsManager`":把 `TL_messages_getHistory`、`TL_upload_getFile`、`TL_messages_search` 等请求在本地翻译成 REST 调用,并把结果拼装回 TL 对象。
- 需要伪造的对象非常多(用户、对话、消息、`Photo`/`Document` 及各种 `PhotoSize`、属性、`FileLocation`),并保证与 `MessagesStorage`(SQLite)、`FileLoader`、`ImageLoader` 的内部假设一致。
- 构建要求高(Android Studio、NDK 27、SDK 36、submodule、`BuildVars` 与签名配置);Telegram X 要求 8GB 内存与数十 GB 磁盘。
- 许可证:GPLv2/GPLv3 → 若分发 APK,**整个应用必须开源**;个人自用不受影响。
- 官方上游持续更新,fork 的合并成本长期存在;许多功能(通话、支付、机器人、贴纸、聊天分组之外的大量 UI)是死代码,但仍需编译和维护。

**可以得到的**:像素级一致的 Telegram 视觉与交互(滚动、气泡、媒体查看器、手势)。

**判断**:这是用"极高的代码耦合成本"去换"现成的 UI"。而 UI 的**行为**(滚动即加载、分级缩略图、查看器手势)用 Compose 复刻的成本远低于维护一个 Telegram fork。

## 4. 可借鉴(仍然有价值的部分)

阅读 DrKLO/Telegram 源码(**只读、不拷贝**,以避开 GPL 传染)时,值得对照的点:
| 关注点 | 看什么 | 对应到我们的方案 |
|---|---|---|
| 图片加载 | `ImageLoader` / `ImageReceiver`:加载优先级、取消、内存缓存与文件缓存分层 | 06 号文档 §5 的三层状态机与取消策略 |
| 文件分块 | `FileLoader` / `FileLoadOperation`:按固定分块下载、优先级、断点 | 视频直放的 Range 读取与预取 |
| 共享媒体 | `SharedMediaLayout`:按类型分页、网格、快速滚动条 | 06 号文档 §4.2 |
| 查看器 | `PhotoViewer`:手势、转场、视频与图片统一 | 06 号文档 §4.3 |
| 占位缩略图 | stripped thumb | ThumbHash |
| 协议语义 | `messages.getHistory` 的 `offset_id/add_offset/limit`,`upload.getFile` 的 4KB 对齐、≤1MB 分块 | 07 号文档游标与 `around` 语义 |

## 5. 如果仍要尝试(最小验证路径)
1. 搭 Teamgram(Docker Compose),用兼容客户端跑通登录与普通群聊。
2. 写一个**导入器**:选定一个小文件夹(几百个文件),通过 Teamgram 的内部接口/数据库把本地文件登记为一个群的消息(媒体 `Document`/`Photo`),验证客户端能否显示缩略图并播放。
3. 评估三个数字:导入吞吐、重复占用空间、转码缺口。
4. 若第 2 步需要深度读源码才能做出来,**止损**,转向方案 C。

## 6. 评分(1–5,5 最好)

| 维度 | 分 | 说明 |
|---|---|---|
| UI 还原度 | 5 | 本身就是 Telegram |
| 满足"实时转码 + 省带宽" | 1 | 需要另造转码层 |
| 格式覆盖 | 2 | 受限于 Telegram 的媒体类型和导入流程 |
| 磁盘占用 | 1 | 需要把媒体复制进对象存储 |
| 工作量/风险 | 1 | 最高 |
| 长期维护 | 1 | 追上游、追协议 |
| 数据与隐私 | 4 | 全自建,但组件多、攻击面大 |

**结论**:不推荐。若只想要 Telegram 手感,走方案 C/B + 06 号文档的 Compose 客户端,在行为层模仿 Telegram。
