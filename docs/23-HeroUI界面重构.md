# 23 · 手机端界面重构:React + HeroUI(分支 `feature/heroui-react`)

## 结论

手机端(LocalBrowse)的所有页面改为 **React 19 + HeroUI v3(3.2.7)+ Tailwind CSS v4**,放在 WebView 里运行;原来的 Telegram 风格 Compose 界面不再作为入口。数据层和需要原生 Surface / OpenGL 的全屏查看器、播放器保持 Kotlin。

视觉按 HeroUI 官方默认主题(`@heroui/react` 的 DESIGN.md):浅灰画布 + 白色大圆角卡片(`rounded-3xl` + `shadow-surface`)、胶囊按钮、灰底分段切换(Tabs 主样式)、卡片内的小标题分组;深色 / 跟随系统在「设置 → 外观」里切换(5.0.0 起默认浅色,老用户升级时改一次)。

## 结构

| 位置 | 作用 |
|---|---|
| `android/web/` | 网页界面源码(Vite)。`npm run build` 输出到 `android/app/src/main/assets/web/`(不提交,Gradle `preBuild` 前自动构建) |
| `web/WebAppHost.kt` | MainActivity 的内容:WebView、返回键、安全区、读取媒体权限、状态栏图标深浅 |
| `web/WebMedia.kt` | `https://appassets.androidplatform.net/` 的进程内应答:页面读 assets;`/srv/...` 媒体转发给当前服务器(令牌、证书指纹、内网直连、本地媒体都走原 OkHttp 客户端,带版本号的图片强制长期缓存) |
| `web/WebBridge.kt` | JS 桥:`LB.call(id, method, args)` → `window.__lb.resolve`;原生事件 `window.__lb.emit`(session / settings / back / insets / viewerClosed / update / speed …) |
| `ViewerActivity`(MainActivity.kt) | 原生全屏查看器 / 播放器,网页通过 `viewer.open` 传条目和"怎么继续加载"(群历史 / 搜索 / 随机),关闭时回传最后停留的条目 |

令牌从不进入网页:网页只用相对地址 `/srv/api/v1/media/...` 显示图片,接口请求经桥由原生发出。

## 页面

- 首页:底部悬浮 Tabs(文件夹 / 随机 / 设置);文件夹页是两列卡片,盘符用分段 Tabs 过滤,顶部「随机浏览」卡片。
- 文件夹:时间线(按天分组的大图卡片)/ 网格 / 瀑布流三种视图;双向分页、记住并同步浏览位置、视图与排序 Sheet、跳到日期(Calendar)、浏览历史 Sheet、文件夹内搜索。
- 搜索、随机浏览、设置(99 个设置项由原 `SettingsScreen.kt` 提取成 `screens/settings/schema.ts`,另有服务器管理、服务器配置、网络、测速、存储、日志、关于 / 更新、画质增强 / 硬件检测 / LSFG、本机解码器)。
- 只属于旧 Compose 界面的设置(液态玻璃、Activity 转场、聊天气泡等)在新界面隐藏,值保留。

## 开发

```bash
cd android/web
npm install
npm run dev      # 浏览器里用模拟数据(src/lib/mock.ts)预览界面
npm run build    # 输出到 assets/web(Gradle 构建 APK 时也会自动执行)
```

App 里调试网页:debug 版开启了 WebView 远程调试,Chrome 打开 `chrome://inspect`。
