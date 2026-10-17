打包时 tools\package.ps1 会把 tools\frp\frpc.exe 压成同目录下的 frpc.exe.gz(git 忽略它),编译进服务端。
没有 frpc.exe.gz 时(开发构建),服务端改用 tools\frp\frpc.exe 或配置里填写的路径。
frp 是 Apache-2.0 许可的开源项目:https://github.com/fatedier/frp
