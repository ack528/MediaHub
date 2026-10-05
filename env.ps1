# 进入开发环境:  . .\env.ps1     (前面的点不能少,让变量留在当前终端)
# 所有工具和缓存都重定向到项目内,不改系统环境变量、不写注册表。
$Root = $null
if ($PSScriptRoot) { $Root = $PSScriptRoot }
elseif ($PSCommandPath) { $Root = Split-Path -Parent $PSCommandPath }
elseif ($MyInvocation.MyCommand.Path) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
elseif (Test-Path (Join-Path $PWD 'tools\go')) { $Root = $PWD.Path }      # 兜底:在项目根目录下 dot-source
else { throw "env.ps1: 请在项目根目录运行  . .\env.ps1" }
$T    = Join-Path $Root 'tools'

# Java / Android / Gradle
$env:JAVA_HOME         = "$T\jdk"
$env:ANDROID_HOME      = "$T\android-sdk"
$env:ANDROID_SDK_ROOT  = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = "$T\android-home"
$env:ANDROID_AVD_HOME  = "$T\android-home\avd"
$env:GRADLE_USER_HOME  = "$T\gradle-home"

# Go
$env:GOROOT      = "$T\go"
$env:GOPATH      = "$T\gopath"
$env:GOCACHE     = "$T\gocache"
$env:GOMODCACHE  = "$T\gopath\pkg\mod"
$env:GOTELEMETRY = 'off'
$env:GOFLAGS     = '-mod=mod'
$env:CGO_ENABLED = '0'          # 纯 Go:SQLite 用 modernc.org/sqlite,无需 C 编译器

# 临时目录也放项目内
New-Item -ItemType Directory -Force "$T\tmp","$T\android-home","$T\gradle-home","$T\gopath","$T\gocache" | Out-Null
$env:TEMP = "$T\tmp"; $env:TMP = "$T\tmp"

# 其它
$env:MEDIAHUB_ROOT = $Root
$env:PATH = @(
  "$T\jdk\bin", "$T\gradle\bin", "$T\go\bin", "$T\gopath\bin",
  "$T\android-sdk\cmdline-tools\latest\bin", "$T\android-sdk\platform-tools",
  "$T\ffmpeg", "$T\exiftool", "$T\vips\bin", "$T\sqlite", $env:PATH
) -join ';'


# Tauri 桌面程序:Node / npm 缓存、Cargo 依赖与编译产物都放项目内(Rust 工具链与 MSVC 复用本机已装的)
$env:npm_config_cache   = "$T\npm-cache"
$env:CARGO_HOME         = "$T\cargo-home"
$env:CARGO_TARGET_DIR   = "$T\cargo-target"
$env:RUSTUP_HOME        = "$env:USERPROFILE\.rustup"     # 复用已装的 Rust 工具链
$env:CARGO_NET_GIT_FETCH_WITH_CLI = 'false'
New-Item -ItemType Directory -Force $env:npm_config_cache, $env:CARGO_HOME, $env:CARGO_TARGET_DIR | Out-Null
Write-Host "MediaHub dev env ready: $Root"
