# 进入开发环境:  . .\env.ps1     (前面的点不能少,让变量留在当前终端)
# 有共享工具链时优先使用它，否则使用项目内的便携工具链。
$Root = $null
if ($PSScriptRoot) { $Root = $PSScriptRoot }
elseif ($PSCommandPath) { $Root = Split-Path -Parent $PSCommandPath }
elseif ($MyInvocation.MyCommand.Path) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
elseif (Test-Path (Join-Path $PWD 'tools\go')) { $Root = $PWD.Path }      # 兜底:在项目根目录下 dot-source
else { throw "env.ps1: 请在项目根目录运行  . .\env.ps1" }
$T    = Join-Path $Root 'tools'
$SharedToolchain = if ($env:TOOLCHAIN_DIR) { $env:TOOLCHAIN_DIR } else { 'E:\ToolChain' }
$Activation = Join-Path $SharedToolchain 'use-toolchain.ps1'
if (Test-Path -LiteralPath $Activation) {
  . $Activation
  $MediaTools = $SharedToolchain
  $env:ANDROID_USER_HOME = Join-Path $SharedToolchain 'cache\android-home'
  $env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
  $env:GOROOT = Join-Path $SharedToolchain 'go'
  $env:GOMODCACHE = Join-Path $env:GOPATH 'pkg\mod'
  $env:CARGO_TARGET_DIR = $null
} else {
  $MediaTools = $T
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

  # 临时目录也放项目内
  New-Item -ItemType Directory -Force "$T\tmp","$T\android-home","$T\gradle-home","$T\gopath","$T\gocache" | Out-Null
  $env:TEMP = "$T\tmp"; $env:TMP = "$T\tmp"
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
  New-Item -ItemType Directory -Force $env:npm_config_cache, $env:CARGO_HOME, $env:CARGO_TARGET_DIR | Out-Null
}

$CargoOutput = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $Root 'desktop\src-tauri\target' }
$env:GOTELEMETRY = 'off'
$env:GOFLAGS = '-mod=mod'
$env:CGO_ENABLED = '0'
$env:CARGO_NET_GIT_FETCH_WITH_CLI = 'false'
$env:BUILD_JOBS = "$([Environment]::ProcessorCount)"
$env:CARGO_BUILD_JOBS = $env:BUILD_JOBS
$env:GOMAXPROCS = $env:BUILD_JOBS
$env:CMAKE_BUILD_PARALLEL_LEVEL = $env:BUILD_JOBS
$env:NINJA_STATUS = '[%f/%t] '
$env:GRADLE_OPTS = "-Dorg.gradle.workers.max=$($env:BUILD_JOBS) -Dfile.encoding=UTF-8"
$env:MEDIAHUB_ROOT = $Root
$env:MEDIAHUB_DEV = '1'
$env:MEDIAHUB_CONFIG = Join-Path $Root 'runtime\mediahub\config.json'
Write-Host "MediaHub dev env ready: $Root"
