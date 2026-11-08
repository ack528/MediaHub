# 24 · UDP 协议支持评估(5.1)

目标:后续把手机和服务器之间的传输换成延迟更低的 UDP 系协议(QUIC / HTTP/3 等)。这份文档是对服务端和手机端**现状**的检查结论,以及要走的路线。

## 结论

| 层 | 现在 | 支持 UDP? |
|---|---|---|
| 手机 ↔ 服务器(HTTP 接口、缩略图、视频) | OkHttp 5.5.0:HTTP/1.1 + HTTP/2,全部 TCP | **不支持**。OkHttp 没有 HTTP/3 / QUIC |
| 服务器 HTTP 服务 | Go `net/http`:TCP,开 HTTPS 时 HTTP/2,带保活 PING | **不支持**。标准库没有 HTTP/3 服务端(`crypto/tls` 只提供给第三方 QUIC 库用的钩子) |
| 服务器 ↔ 穿透服务器(frpc → frps) | frp 0.71.1,`transport.protocol` = tcp / **kcp** / **quic** / websocket / wss | **已支持**(kcp、quic 都是 UDP),管理程序和配置里可以选;frps 那边要同时开 `kcpBindPort` / `quicBindPort` |
| 手机 ↔ 穿透服务器(访问 frps 的远程端口) | frp `tcp` 类型代理:纯 TCP | **不支持**。要 UDP 必须用 frp 的 `udp` 类型代理 + 两端都说 UDP 协议 |
| 播放器 | media3 ExoPlayer + `datasource-okhttp`(HLS / 渐进式) | 跟 OkHttp 一样只有 TCP;换成 `media3-datasource-cronet` 才有 HTTP/3 |
| Windows 防火墙规则(管理程序「放行端口」) | 只建 `protocol=TCP` 的入站规则 | 将来监听 UDP 端口要另加 UDP 规则 |

**一句话**:现在能用的 UDP 只有 "frpc → frps" 这一段(选 `quic` 或 `kcp`)。手机到服务器的那一段(你真正感受到延迟的地方)全是 TCP;要换成 UDP 需要服务端、手机端、穿透三处一起改,下面是路线。

## 5.1 里已经做的(不改协议,先把"看得见"和"能判断")

1. **服务端能力上报**:`/api/v1/server/info` 的 `caps.transport` 和 `/api/v1/server/link`(登录后)告诉手机 `http1 / http2 / http3 / quic / udp`、穿透用的传输协议和它是不是 UDP。以后加了 HTTP/3,手机从这里判断能不能切。
2. **手机端显示当前用的协议**:设置 → 网络 → 连接质量,显示这次请求实际协商出的协议(`HTTP/2`、`HTTP/1.1`;OkHttp 以后换成支持 HTTP/3 的客户端就会显示 `HTTP/3`)和 TLS 版本。
3. **实时延迟**:手机每 2 秒测一次往返,服务端每 2 秒测一次"整条穿透链路"和"到 frps",管理程序的穿透页和手机的连接质量页都能看到。切换到 UDP 协议之后,用这个直接对比延迟、抖动、丢包的变化。
4. **frp 传输协议**:管理程序「内网穿透」页里能看到当前协议和是不是 UDP,实时延迟卡片的右上角有 `UDP · quic` / `TCP · tcp` 标记。

## 路线

### 第 1 步:现在就能做(不用改代码)

穿透服务器 frps 的 `frps.toml` 加上 `quicBindPort = 7000`(UDP,和 `bindPort` 同号没关系,TCP / UDP 端口空间是分开的)或 `kcpBindPort = 7000`,服务端「内网穿透」里把传输协议改成 `quic`。用「实时延迟」对比:

- 丢包高、抖动大的线路(手机 4G / 5G 上看视频):quic 通常明显更稳;
- 线路干净时 TCP 和 quic 差别不大,quic 还多一点 CPU;
- 注意:这只改了 frpc ↔ frps,手机 ↔ frps 仍是 TCP。

### 第 2 步:服务端加 HTTP/3(QUIC)监听

- 引入 `github.com/quic-go/quic-go/http3`,和现有 TCP 监听并存(同一份 TLS 证书,UDP 端口 = TCP 端口),在 HTTP/1.1 / HTTP/2 响应里加 `Alt-Svc: h3=":8480"` 声明。
- `/api/v1/server/info` 的 `caps.transport.http3 / quic` 置为 `true`。
- 管理程序「放行端口」再建一条 `protocol=UDP` 的规则;frp 要再配一条 `type = "udp"` 的代理才能从外网访问。
- 风险:自签名证书 + 证书指纹固定:QUIC 的 TLS 1.3 握手要走手机端同一套指纹校验。

### 第 3 步:手机端换支持 HTTP/3 的网络栈

- 选 **Cronet**(`org.chromium.net:cronet-embedded`,每个 ABI 约 +4~5 MB):接口请求、Coil 图片、ExoPlayer(`media3-datasource-cronet`)都能走 HTTP/3 + 0-RTT + 连接迁移(手机在 Wi-Fi / 蜂窝间切换时连接不断)。
- 证书固定:Cronet 不走 OkHttp 的证书校验,要用 Cronet 的公钥固定(`addPublicKeyPins`)重新实现一遍当前的指纹信任。
- 保留 OkHttp 作为回退:服务端没开 HTTP/3、或 UDP 被运营商 / 防火墙拦掉时自动回到 HTTP/2(按 `caps.transport.http3` + 失败重试)。
- 设置里加"传输协议"选项:自动 / 只用 TCP,方便排查。

### 第 4 步:自定义低延迟通道(可选)

只有把"单次请求的往返"以外的东西也要降到最低(例如实时远程控制)才值得做:基于 QUIC 的自定义流或 WebTransport。当前是浏览 + 播放场景,HTTP/3 已经够用。

## 不建议的方向

- **自己用裸 UDP 重新发明可靠传输**:KCP / QUIC 已经有成熟实现(frp 里就有),拥塞控制、丢包重传、加密自己写很容易出问题。
- **只把 frp 换成 UDP 代理而不改应用层**:HTTP/1.1 / HTTP/2 是为 TCP 设计的,放在 UDP 上没有意义;必须应用层也换成 HTTP/3。

## 怎么验证

- 手机:设置 → 网络 → 连接质量 → "协议" 一格;
- 服务端:管理程序 → 网络 → 内网穿透 → "实时延迟" 卡片;
- 抓包确认:Wireshark 过滤 `udp.port == 8480` 看有没有 QUIC 包(第 2、3 步之后)。
