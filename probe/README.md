# 天玑 GPU 适配检测(probe)

自用诊断 App:在手机上测 Vulkan / LSFG 光流补帧 在这块 GPU 上能不能跑、跑多快、画面对不对。

- 构建:`powershell -File tools\build-probe.ps1`(release,arm64)→ `probe\app\build\outputs\apk\release\app-release.apk`
- 依赖主工程里 `tools\setup-lsfg.ps1` 复制好的 LSFG 源码;`Lossless.dll` 放在 `app\src\main\assets\lsfg\`(不提交,不要分发 APK)
- 每个测试在独立进程(:probe)里跑,原生崩溃 / 超时只记录,继续下一项
- 报告:`/sdcard/Android/data/com.localtg.probe/files/reports/run-*/`(report.txt + 对比图),App 内可分享 zip
- 自定义 Vulkan 驱动:把 `.so` 通过「导入驱动」放进去(或 adb push 到 `.../files/drivers/`),检测会对它额外跑 信息 / 计算 / 3 个 LSFG 配置。入口取 `vk_icdGetInstanceProcAddr`
