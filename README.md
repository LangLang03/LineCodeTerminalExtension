# LineCode 终端扩展（LineCode Terminal Extension）

LineCode 的独立终端提供者应用：内置 PRoot 与 Ubuntu 22.04.5 LTS（arm64）根文件系统，
通过 LineCode `:ipc` 的 AIDL 契约，把 guest 环境中的 Shell 执行与文件操作对外提供。

| 项目 | 值 |
|---|---|
| 包名 | `cn.lineai.terminal.extension` |
| 版本 | 1.2.0（versionCode 3），见 `app/build.gradle.kts` 的 `releaseVersionName` |
| SDK | `minSdk 26` / `compileSdk 36` / `targetSdk 28`（见下文说明） |
| 架构 | arm64-v8a（内置 PRoot 与 busybox 为 aarch64 二进制） |
| 依赖 | 仅 Android 框架与 Java 11，无第三方运行时依赖 |
| 入口 | 无桌面图标；状态页 `ui/StatusActivity` + IPC 服务 `service/TerminalProviderService` |

`targetSdk 28` 是硬性要求：Android 10+ 禁止面向 API 29+ 的应用从应用数据目录执行二进制，
而 PRoot 必须从数据目录启动 guest。Lint 中对应的 `ExpiredTargetSdkVersion` 已显式关闭。

变更历史见 [update.md](update.md)；第三方组件版本与许可证见 `licenses/README.md`。

## 架构

```
LineCode（IPC 调用方） → Binder: ITerminalProviderService → TerminalProviderService
  ├─ ProotEnvironment        环境生命周期：安装幂等、串行化、就绪等待
  │   ├─ RootfsInstaller     解压 Ubuntu Base、写 overlay、维护安装标记
  │   │   └─ TarGzExtractor  流式解压，拒绝路径逃逸
  │   └─ ShellRunner         组装 PRoot argv 并执行 guest 命令
  ├─ BackgroundGuestTasks    AIDL v2 后台 guest 任务：分离启动、输出缓冲、offset 轮询
  ├─ GuestFileSystem         AIDL 文件接口（分块读写、目录列举）
  │   └─ GuestPaths          guest 路径映射，符号链接限制在各挂载根内
  ├─ ExternalStorage         设备共享存储访问：/sdcard、/storage/emulated/0 绑定到 guest
  └─ WorkerPool              进程级共享守护线程池
```

`ui/StatusActivity` 与 `ui/IpcSelfTest` 直接复用 `ProotEnvironment`，用于设备侧安装与自检。

## 内置运行时

资源位于 `app/src/main/assets/runtime/`，随 APK 打包；文件清单与 SHA-256 见
`assets/runtime/runtime-manifest.txt`，下载与校验使用 `tools/fetch-runtime.sh`。
组件来源：Ubuntu Base 22.04.5（cdimage）、PRoot/libtalloc/libandroid-shmem（Termux 仓库）、
busybox-static（Ubuntu ports）、CA 证书包（curl.se）。

首次绑定服务时自动安装：解压约 95 MB 根文件系统（数十秒），并写入 DNS（仅 IPv4）、
APT sandbox 配置、shell profile、busybox 符号链接与 CA 证书，同时创建共享存储挂载点
`/sdcard`、`/storage/emulated/0`、`/root/sdcard`。安装幂等；重新安装保留
`/root/workspace`（provider home）。`executeShell` 会等待环境就绪（最长 180 秒）。

## 构建与测试

JDK 17 + Android SDK（platform 36）+ AGP 9.2.0，Java 11 源码兼容。

```bash
./tools/fetch-runtime.sh          # 首次检出后下载并校验内置资源
./gradlew :app:assembleDebug      # 调试 APK
./gradlew :app:testDebugUnitTest  # 单元测试（解压、路径、文件系统、argv、providerInfo）
./gradlew :app:lintDebug          # 静态检查
./gradlew :app:assembleRelease    # 签名发布 APK（需要 signing.properties）
```

构建前 `verifyRuntimeAssets` 会校验必需资源是否齐全，缺失时构建直接失败。

## IPC 集成

Service 声明（与 LineCode `:ipc` 的 AIDL 逐字复制以保持二进制兼容）：

- action `cn.lineai.action.IPC_TERMINAL_PROVIDER`，权限 `cn.lineai.permission.IPC_TERMINAL_PROVIDER`
  （由 LineCode 声明为 `normal`，本应用以 `<uses-permission>` 申请以便被客户端扫描发现）；
- `getProviderType()` 返回 `terminal`；`isAvailable()` 反映 Ubuntu 环境就绪状态；
- `executeShell(command, cwd, timeoutMs, callback)` 同步返回退出码，输出经回调异步回传；
  文件接口覆盖 `readFile`/`writeFile`/`deleteFile`/`listDir`/`fileExists`/`fileSize`/
  `readFileChunk`/`writeFileChunk`/`getFileSize`/`listDirDetailed`；
- 后台 Shell（AIDL v2）：`startShellBackground` 立即返回任务句柄并在 guest 内分离启动
  （输出经回调流式回传，同时按 offset 保留在前缀缓冲中），`readShellBackground(handle, offset)`
  轮询增量输出与运行状态，`killShellBackground` 终止整个 guest 进程树，
  `forgetShellBackground` 释放提供方侧缓冲；环境未就绪时 start 返回 0；
- 退出码约定：`>= 0` guest 退出码；`-1` 命令为空或启动失败；`-2` 超时（进程树已终止）；
  `-3` 环境未就绪。`cwd` 必须为 guest 绝对路径，`..` 在 guest 根处截断。

在 LineCode 中启用：设置 → 扩展 → 终端提供者 → 扫描并添加本扩展；工具与执行 →
执行目标 → 终端提供者。签名匹配或已信任的扩展会被客户端自动发现与绑定。

## 外部文件访问

扩展以 API 28 的 legacy external storage 方式申请 `READ_EXTERNAL_STORAGE` /
`WRITE_EXTERNAL_STORAGE`（manifest 中开启 `requestLegacyExternalStorage`），获得授权后
把设备主共享存储绑定进 Ubuntu：

| Guest 路径 | Host 路径 | 用途 |
|---|---|---|
| `/sdcard` | `/storage/emulated/0` | Android 常用别名 |
| `/storage/emulated/0` | 同上 | 与 LineCode 项目绝对路径一致 |
| `/root/sdcard` | 同上 | 从 provider home 直接访问的便捷入口 |

- Shell 与 AIDL 文件接口都使用同一套映射：`executeShell`/后台任务可读写这些路径，
  `readFile`/`writeFile`/`listDir` 等文件接口同样接受 `/sdcard/...` 与
  `/storage/emulated/0/...`；
- 未授权时挂载点保持为空目录，文件接口返回失败，不会回退到 rootfs 以外的未知路径；
- 共享存储路径同样做边界校验：符号链接若指向该卷之外会被拒绝；
- 默认工作目录仍是 `/root/workspace`；访问 LineCode 的外部项目时使用其绝对路径
  （如 `/storage/emulated/0/Projects/foo`）或先 `cd /sdcard/...`。

在状态页的「外部文件访问」区域点击「授权存储访问」授予权限；若系统不再显示授权对话框，
按钮会变为「打开系统设置」，可在应用详情中手动开启存储权限。授权后无需重启环境，
下一次命令即生效。

## 安全模型

- guest 进程以应用 UID 在沙箱内运行；PRoot `-0` 仅为伪 root，不构成特权边界；
- guest 路径解析与 tar 解压均拒绝 `..` 越界与符号链接逃逸，不会触及 rootfs 之外的宿主文件；
- 只有在用户授予存储权限后才绑定共享存储；未授权时挂载点为空的 rootfs 目录，
  AIDL 文件接口不会读写任何外部文件；`allowBackup=false`，无 launcher 组件；
- 持有 `IPC_TERMINAL_PROVIDER` 权限的调用方可在 guest 内执行任意命令，并在已授权时
  读写设备共享存储；该权限只应授予受信任的客户端（信任模型与 LineCode `:ipc` 一致）。

## 排障

- 首次 `executeShell` 返回 `-3`：Ubuntu 仍在解压，等状态页显示就绪后重试；
- x86_64 模拟器无法运行内置 arm64 PRoot（`Exec format error` 属预期），真机需 arm64；
- 构建提示缺少运行时资源：执行 `./tools/fetch-runtime.sh`；
- 共享存储不可用：在状态页「外部文件访问」授权，或到系统设置中开启存储权限；
- 恢复干净环境：状态页「重新安装」，或 `adb shell pm clear cn.lineai.terminal.extension`；
- Android 12+ 日志中的 `avc: denied { link }` 属预期：系统禁止在数据目录建硬链接，
  解压器自动退化为复制。

状态页命令：`adb shell am start -n cn.lineai.terminal.extension/.ui.StatusActivity`
（可选 `--ez auto_install true` 直接开始安装）。

## 许可证

本项目以 **AGPL-3.0** 发布（与 LineCode 的 AGPL-3.0 IPC 契约及内置 GPL/LGPL 组件保持兼容），
全文见 `LICENSE`。第三方组件（PRoot、libtalloc、libandroid-shmem、BusyBox、Ubuntu Base、
CA 证书包、LineCode AIDL、Gradle Wrapper）的许可证与源码地址见 `licenses/README.md`；
构建任务 `syncLicenses` 会把它们打包进 APK 的 `assets/licenses/`。
