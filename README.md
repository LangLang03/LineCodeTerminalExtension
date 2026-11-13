# LineCode 终端扩展（LineCode Terminal Extension）

## 1. 概述

LineCode 终端扩展是 LineCode 的独立终端提供者应用。应用内置 PRoot 与 Ubuntu 22.04.5 LTS（arm64）根文件系统，通过 LineCode `:ipc` 的 AIDL 契约，将 guest 环境中的 Shell 执行与文件操作对外提供。

| 项目 | 值 |
|---|---|
| 应用名 | `LineCode终端扩展` |
| 包名 | `cn.lineai.terminal.extension` |
| 版本 | `1.0.0`（versionCode 1） |
| SDK | `minSdk 26` / `compileSdk 36` / **`targetSdk 28`** |
| 运行架构 | arm64-v8a（仅支持 arm64 设备；内置 PRoot 与 busybox 为 aarch64 二进制） |
| 依赖 | 仅 Android 框架与 Java 11，无第三方运行时依赖 |
| 桌面入口 | 无。Manifest 中不含 `MAIN`/`LAUNCHER` 组件，图标用于应用信息页与状态页头部 |

应用包含两个对外组件：

- `service/TerminalProviderService`：导出为 `cn.lineai.action.IPC_TERMINAL_PROVIDER`，由 `cn.lineai.permission.IPC_TERMINAL_PROVIDER` 保护，实现 `ITerminalProviderService`。
- `ui/StatusActivity`：无桌面入口的状态与安装页，导出 `cn.lineai.action.OPEN_TERMINAL_EXTENSION`。

## 2. 架构

```
LineCode（IPC 调用方，持有 IPC_TERMINAL_PROVIDER 权限）
  └─ Binder : cn.lineai.ipc.terminal.ITerminalProviderService
       └─ TerminalProviderService（IPC 服务端）
            ├─ ProotEnvironment（环境生命周期）
            │    ├─ RootfsInstaller（解压、overlay、安装标记）
            │    │    ├─ TarGzExtractor
            │    │    └─ RootfsOverlay
            │    └─ ShellRunner（PRoot 执行）
            │         └─ ProcessTreeKiller
            └─ GuestFileSystem（文件接口）
                 └─ GuestPaths（guest 路径映射）

存储：files/runtime/（PRoot、loader、运行库、tmp）
      files/ubuntu/rootfs/（Ubuntu 22.04.5 LTS）
```

`ui/StatusActivity` 与 `ui/IpcSelfTest` 直接复用 `ProotEnvironment`，不经过 Binder，用于设备侧的安装与自检。

### 2.1 模块职责

| 模块 | 职责 |
|---|---|
| `TerminalExtensionApp` | 进程入口，持有进程内唯一的 `ProotEnvironment` 实例 |
| `service/TerminalProviderService` | `ITerminalProviderService` 的 Binder 实现，参数校验与回调转发 |
| `runtime/ProotEnvironment` | PRoot 与 Ubuntu 环境生命周期；安装幂等、串行化、就绪等待 |
| `runtime/RootfsInstaller` | 解压内置 Ubuntu Base、写入 overlay、维护安装标记；重装保留 workspace |
| `runtime/TarGzExtractor` | 流式解压 tar.gz，按 guest 语义重建链接与权限，拒绝路径逃逸 |
| `runtime/RootfsOverlay` | 写入 DNS、apt sandbox、shell profile、workspace、CA 证书与 busybox |
| `runtime/ShellRunner` | 组装 PRoot argv 与环境变量，执行 guest 命令，回传输出与退出码 |
| `runtime/ProcessTreeKiller` | 通过 `/proc` 收集子孙进程并发送 `SIGKILL`，确保 PRoot guest 一并退出 |
| `runtime/GuestFileSystem` | AIDL 文件接口实现：分块读写、目录列举、JSON 明细 |
| `runtime/GuestPaths` | guest 绝对路径规范化与到 rootfs 宿主路径的映射，符号链接限制在 rootfs 内 |
| `runtime/RuntimePaths` | `files/runtime` 与 `files/ubuntu` 下的固定路径定义 |
| `runtime/ProviderInfo` | 构建 `getProviderInfo()` 返回的 JSON 文档 |
| `runtime/Json` | 最小 JSON 字符串转义 |
| `runtime/WorkerPool` | 进程级共享守护线程池，用于 shell 执行、解压与状态页任务 |
| `ui/StatusActivity` | 状态、安装/重装、测试命令、IPC 自检与运行日志 |
| `ui/IpcSelfTest` | 设备侧 IPC 自检：服务发现、绑定、providerInfo 与 guest 文件查询 |

### 2.2 目录结构

```
app/src/main/aidl/                 LineCode :ipc 的 AIDL 契约（逐字复制，保持二进制兼容）
app/src/main/assets/runtime/       PRoot、Ubuntu Base、busybox、CA 证书与资源清单
app/src/main/java/cn/lineai/terminal/extension/
  TerminalExtensionApp.java        应用入口
  runtime/                         安装、解压、路径映射、PRoot 执行、guest 文件系统
  service/TerminalProviderService  IPC 服务端
  ui/                              状态页与 IPC 自检
app/src/test/java/                 单元测试（解压、路径、文件系统、argv、providerInfo）
tools/fetch-runtime.sh             下载并校验内置运行时资源
licenses/                          许可证与第三方组件说明（构建时打包到 APK 的 assets/licenses/）
```

## 3. 内置运行时

所有资源位于 `app/src/main/assets/runtime/`，随 APK 打包，首次安装时复制或解压到应用数据目录。文件大小与 SHA-256 如下表；`tools/fetch-runtime.sh` 在下载时按同一组 SHA-256 校验。

| 文件 | 大小（字节） | SHA-256 | 来源 |
|---|---:|---|---|
| `ubuntu-22.04.5-base-arm64.targz` | 27,671,306 | `075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f` | https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz |
| `proot` | 259,832 | `3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41` | https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb → `data/data/com.termux/files/usr/bin/proot` |
| `proot-loader` | 18,136 | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` | 同上 `.deb` → `data/data/com.termux/files/usr/libexec/proot/loader` |
| `libtalloc.so.2` | 33,592 | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` | https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb → `data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0` |
| `libandroid-shmem.so` | 14,432 | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb → `data/data/com.termux/files/usr/lib/libandroid-shmem.so` |
| `busybox` | 1,980,808 | `6db97dc211d50486ea94ecf3fbbc36a390f10d8eed86920aa356cb42bc30efde` | http://ports.ubuntu.com/ubuntu-ports/pool/main/b/busybox/busybox-static_1.37.0-7ubuntu1.1_arm64.deb → `usr/bin/busybox` |
| `cacert.pem` | 200,116 | `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285` | https://curl.se/ca/cacert.pem |

资源清单文件为 `app/src/main/assets/runtime/runtime-manifest.txt`；第三方组件许可说明见 `licenses/README.md`，
构建时会复制到 APK 的 `assets/licenses/` 内。

首次安装时，除解压 Ubuntu Base 镜像外，还会写入以下配置：

- `/etc/resolv.conf`：只写入设备当前网络的 IPv4 DNS（容器网络不支持可靠 IPv6），无可用地址时回退 `223.5.5.5`、`1.1.1.1`、`8.8.8.8`；
- `/etc/apt/apt.conf.d/99linecode`：`APT::Sandbox::User "root";` 与 `Acquire::ForceIPv4 "true";`，适配 PRoot 伪 root 并强制 IPv4；
- `/etc/gai.conf`：优先使用 IPv4 地址（`precedence ::ffff:0:0/96 100`）；已安装的 rootfs 在下次绑定时自动刷新上述网络配置；
- `/etc/profile.d/linecode.sh`：`PATH`、`PS1`、常用 alias；`/root/.bashrc` 与 `/root/.profile` 引用该文件；
- `/usr/local/bin/busybox` 及 `vi`、`less`、`wget`、`nc`、`unzip`、`zip`、`xz`、`unxz`、`nslookup`、`telnet` 符号链接；
- `/etc/ssl/certs/ca-certificates.crt`。

需要更多工具时可在 guest 内安装：

```bash
apt-get update && apt-get install -y git curl python3
```

## 4. IPC 契约

### 4.1 AIDL

`app/src/main/aidl/cn/lineai/ipc/` 下的 AIDL 逐字复制自 LineCode `:ipc` 模块，用于保证 Binder 接口的二进制兼容：

- `cn/lineai/ipc/IBaseIpcService.aidl`：`getProviderType()`、`getProviderInfo()`、`isAvailable()`；
- `cn/lineai/ipc/terminal/ITerminalProviderService.aidl`：在基类方法之外提供 `executeShell`、文件操作与分块文件操作；
- `cn/lineai/ipc/terminal/ITerminalProviderCallback.aidl`：`onOutput`、`onError`、`onComplete`。

本应用实现 `ITerminalProviderService`，其中：

- `getProviderType()` 固定返回 `terminal`；
- `isAvailable()` 返回 Ubuntu 环境是否就绪；
- `executeShell(command, cwd, timeoutMs, callback)` 同步返回退出码，输出通过回调异步回传；
- 文件接口包括 `readFile`、`writeFile`、`deleteFile`、`listDir`、`fileExists`、`fileSize`、`readFileChunk`、`writeFileChunk`、`getFileSize`、`listDirDetailed`。

`executeShell` 的返回码约定：

| 返回码 | 含义 |
|---:|---|
| `>= 0` | guest 进程退出码 |
| `-1` | 命令为空、PRoot 启动失败或执行被中断 |
| `-2` | 执行超时，进程树已被终止 |
| `-3` | Ubuntu 环境在 180 秒内未就绪 |

`timeoutMs <= 0` 时使用默认 30 秒；单次执行上限为 30 分钟。`cwd` 必须为 guest 绝对路径；空值与相对路径回退到 provider home，`..` 在 guest 根处截断。

### 4.2 Service 声明

```xml
<service
    android:name=".service.TerminalProviderService"
    android:exported="true"
    android:permission="cn.lineai.permission.IPC_TERMINAL_PROVIDER">
    <intent-filter>
        <action android:name="cn.lineai.action.IPC_TERMINAL_PROVIDER" />
    </intent-filter>
</service>
```

应用同时声明以下权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="cn.lineai.permission.IPC_TERMINAL_PROVIDER" />
```

`cn.lineai.permission.IPC_TERMINAL_PROVIDER` 由 LineCode 声明；LineCode 的 `IpcProviderScanner` 仅列出申请了该权限的 provider，因此本应用通过 `<uses-permission>` 申请该权限。

### 4.3 providerInfo

`getProviderInfo()` 返回如下结构的 JSON（`home` 为 provider 主目录）：

```json
{
  "name": "LineCode Terminal Extension",
  "version": "1.0.0",
  "shell": "/bin/bash",
  "home": "/root/workspace",
  "distro": "Ubuntu 22.04.5 LTS (jammy)",
  "arch": "aarch64",
  "capabilities": [
    "executeShell", "readFile", "writeFile", "deleteFile", "listDir",
    "fileExists", "fileSize", "readFileChunk", "writeFileChunk",
    "getFileSize", "listDirDetailed"
  ]
}
```

`home` 固定为 `/root/workspace`。客户端接线后，LineCode 在终端提供者模式下将该路径作为项目根目录。

### 4.4 guest 路径语义

文件接口的路径均为 guest 命名空间内的绝对 Linux 路径，映射到 `files/ubuntu/rootfs/`：

- 路径必须以 `/` 开头；空路径、相对路径与含 NUL 的路径被拒绝；
- `..` 在 guest 根处截断，不会越出 rootfs；
- 符号链接按 guest 语义解析：绝对链接目标以 rootfs 为根，相对链接基于父目录，最多 40 层；
- 解析后的宿主路径必须位于 rootfs 内，否则返回空结果或失败；
- 读取：单文件上限 32 MB，分块读取默认与上限均为 1 MB，`size <= 0` 时按上限截断；
- 写入：`offset == -1` 追加，否则从 `offset` 覆盖写入；缺失的父目录会创建；
- 删除不递归，仅删除单个文件或空目录；
- `listDirDetailed` 返回 JSON 数组 `[{"name":"...","dir":true,"size":0}, ...]`，空目录或无效路径返回 `[]`。

## 5. targetSdk 28 说明

PRoot 需要执行存放在应用数据目录中的 guest 二进制（如 rootfs 内的 `/bin/bash`）。Android 10（API 29）起禁止 `targetSdk >= 29` 的应用从可写数据目录执行二进制，Termux 等 PRoot 方案同样停留在 28。这是本扩展的硬性约束：将 `targetSdk` 提升到 29 及以上会导致 PRoot 以 `EACCES` 失败。

`app/build.gradle.kts` 中显式禁用 lint 的 `ExpiredTargetSdkVersion` 检查，该检查面向应用商店的 targetSdk 要求，不适用于本扩展的部署方式。

## 6. 构建、安装与使用

### 6.1 构建

构建环境：JDK 17、Android SDK（platform 36）、Gradle wrapper 9.4.1 + AGP 9.2.0。源码兼容级别为 Java 11。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 30 MB）
```

构建前会执行 `verifyRuntimeAssets`：若 `app/src/main/assets/runtime/` 缺少任一必需资源，构建直接失败。首次检出后如资源缺失，执行：

```bash
./tools/fetch-runtime.sh
```

静态检查：

```bash
./gradlew :app:lintDebug
```

### 6.2 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

测试覆盖 tar.gz 解压与真实 Ubuntu 镜像、guest 路径规范化与符号链接限制、guest 文件系统读写/分块/删除、PRoot argv 与环境变量、providerInfo JSON。

### 6.3 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装后无桌面图标，通过状态页或 LineCode 调用。

### 6.4 在 LineCode 中启用

1. 打开 LineCode → 设置 → 扩展 → 终端提供者 → 扫描；
2. 在列表中选择「LineCode终端扩展」，添加并启用；
3. 工具与执行 → 执行目标 → 终端提供者。

### 6.5 状态页命令

```bash
# 打开状态页
adb shell am start -n cn.lineai.terminal.extension/.ui.StatusActivity

# 通过导出 action 打开
adb shell am start -a cn.lineai.action.OPEN_TERMINAL_EXTENSION

# 无界面安装（auto_install extra）
adb shell am start -n cn.lineai.terminal.extension/.ui.StatusActivity --ez auto_install true
```

`auto_install` 为可选 boolean extra；为 `true` 且环境尚未安装时立即开始安装，安装进度与结果写入页面日志。服务被 LineCode 绑定后也会在后台开始解压，`executeShell` 会等待环境就绪（最长 180 秒）。

## 7. 状态页

状态页显示环境状态、rootfs 路径、PRoot 路径、占用空间与 ABI 提示，并提供以下操作：

- **安装 / 重新安装**：安装幂等；重新安装会重置 Ubuntu 环境并保留 `/root/workspace`，操作前有确认对话框；
- **运行测试命令**：执行 `uname -a; id; bash --version | head -1; cat /etc/os-release | head -2; ls /`，验证 PRoot 与 Ubuntu 正常工作并显示退出码；
- **IPC 自检**：查询 provider action 的可见服务、绑定 `ITerminalProviderService`、读取 `getProviderType` / `getProviderInfo` / `isAvailable`，并执行 `fileExists`、`listDir`、`getFileSize` 查询；
- **清空日志**：清空运行日志，日志区域支持选择与复制。

首次安装需要解压约 95 MB 的根文件系统（3497 个条目），耗时数十秒；安装期间页面每 500 ms 刷新一次状态。



## 8. 运行时数据路径

数据均位于应用私有目录，随卸载或 `pm clear` 清除。

| 路径 | 内容 |
|---|---|
| `files/runtime/proot` | PRoot 主程序（0755） |
| `files/runtime/proot-loader` | PRoot loader（0755） |
| `files/runtime/lib/libtalloc.so.2` | PRoot 依赖库 |
| `files/runtime/lib/libandroid-shmem.so` | PRoot 依赖库 |
| `files/runtime/tmp/` | `PROOT_TMP_DIR` |
| `files/ubuntu/rootfs/` | 解压后的 Ubuntu 22.04.5 根文件系统 |
| `files/ubuntu/rootfs.tmp/` | 安装阶段临时解压目录，成功或失败后清理 |
| `files/ubuntu/rootfs.marker` | 安装标记：`version`、`sha256`、`installedAt` |
| `files/ubuntu/workspace.backup/` | 重装期间临时保存的 `/root/workspace` |

`/root/workspace` 为 provider home，重装时保留。文件接口的 guest 路径全部映射到 `files/ubuntu/rootfs/`；应用不绑定 `/sdcard`，不暴露外部存储。

## 9. 安全模型

- **Binder 信任模型**：Service 由 `cn.lineai.permission.IPC_TERMINAL_PROVIDER` 保护，该权限由 LineCode 声明为 `normal`。任何申请该权限的应用均可在 Android 框架层绑定，本应用不额外做签名或调用方身份校验；该模型与 LineCode `:ipc` 一致。
- **IPC 能力边界**：持有权限的调用方可在 guest 内执行任意命令，并读写 rootfs 内的文件。该权限应仅授予受信任的客户端。
- **进程权限**：guest 进程以应用 UID 在应用沙箱内运行；PRoot 的 `-0` 仅提供伪 root，不构成特权边界。
- **路径限制**：guest 路径解析与 tar 解压均拒绝 `..` 越界与符号链接逃逸，文件操作不会触及 rootfs 之外的宿主文件。
- **存储权限**：不申请 `MANAGE_EXTERNAL_STORAGE`，不绑定外部存储。
- **完整性**：内置资源在下载阶段由 `tools/fetch-runtime.sh` 校验 SHA-256；Ubuntu 镜像在安装阶段由 `RootfsInstaller` 以 SHA-256 校验。
- **备份与入口**：`android:allowBackup="false"`；无 launcher 组件；状态页仅提供安装、测试命令与 IPC 自检操作，不接收任意命令参数。

## 10. 排障

| 现象 | 处理 |
|---|---|
| PRoot 启动报 `EACCES` / `Permission denied` | 确认 `targetSdk` 仍为 28；不要将 rootfs 放到外部存储 |
| 状态页提示「当前设备不是 arm64」 | 本扩展仅支持 arm64 设备；x86_64 模拟器无法运行内置的 arm64 PRoot（`Exec format error` 属预期） |
| 首次 `executeShell` 返回 `-3` | Ubuntu 仍在解压；等待状态页显示环境就绪后重试 |
| `apt-get update` 失败 | 检查设备网络与 guest 内 `/etc/resolv.conf`；重装后在状态页日志中查看具体错误 |
| 需要恢复干净环境 | 状态页执行「安装 / 重新安装」，或执行 `adb shell pm clear cn.lineai.terminal.extension` |
| Android 12+ 解压日志出现 `avc: denied { link }` | 属预期：系统禁止在应用数据目录创建硬链接，解压器自动退化为复制；Ubuntu Base 仅含两个硬链接（perl、uncompress） |
| 构建失败并提示缺少运行时资源 | 执行 `./tools/fetch-runtime.sh` 重新下载并校验资源 |
| 部分定制 ROM 上 PRoot 被 seccomp 拦截 | 在 guest 内执行简单命令确认；此类限制无法由应用侧解决 |

## 11. 许可证

- 本项目以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，全文见根目录 `LICENSE`；
  选择 AGPL-3.0 是为了与 LineCode 的 AGPL-3.0 IPC 契约及内置 GPL/LGPL 组件保持兼容。
- 第三方组件（PRoot、libtalloc、libandroid-shmem、BusyBox、Ubuntu Base、CA 证书包、LineCode AIDL、
  Gradle Wrapper）的版本、许可证与源码地址见 `licenses/README.md`，许可证全文位于 `licenses/`。
- 构建任务 `syncLicenses` 会把 `licenses/` 复制到 APK 的 `assets/licenses/`，因此分发 APK 时许可证
  文本随包附带；GPL/LGPL 组件的对应源码获取方式见该目录说明。

## 12. 已验证范围

- **构建**：`./gradlew :app:assembleDebug` 成功，产出 debug APK（约 30 MB）；`aapt2 dump` 确认 `targetSdk=28`、无 launcher 组件、Service 的 action 与 permission 正确。
- **单元测试**：`./gradlew :app:testDebugUnitTest` 的 23 个测试全部通过，覆盖真实 Ubuntu 镜像解压、guest 路径与符号链接限制、guest 文件系统、PRoot argv/环境变量、providerInfo JSON 与 IPv4 DNS 过滤。
- **x86_64 API 35 模拟器**：APK 安装、Ubuntu 解压（3497 个条目、约 95 MB，符号链接与权限正确）、状态页安装流程、IPC 自检（服务发现、绑定、providerInfo、`fileExists` / `listDir` / `getFileSize`）均通过；权限层验证确认未持有 `IPC_TERMINAL_PROVIDER` 的调用方无法启动或绑定 Service。
- **未验证**：arm64 真机上的 PRoot 实际执行。当前验证环境没有 arm64 设备或模拟器；x86_64 上执行 arm64 PRoot 会报 `Exec format error`，属预期现象。该项列入发布前真机验证范围。

## 13. LineCode 客户端集成状态

LineCode 2.0.x 的公开版本仅实现终端提供者的发现、添加与启停管理；包含终端提供者集成改动的 LineCode 构建（本仓库同期的客户端分支）在此基础上完成了以下行为：

1. `terminal_provider` 执行模式映射到 `TerminalIpcProvider`，`shell_execute` 经 IPC 在扩展环境中执行；
2. provider 连接成功后，`getHomePath()` 作为终端提供者模式下的远端工作目录；
3. 本地文件工具在终端提供者模式下被过滤，仅保留可通过 provider 执行的工具族。

客户端会按安全策略自动发现并绑定 provider：与 LineCode 使用相同签名的扩展直接加载；签名不一致的扩展需在 LineCode 的「设置 → 安全 → 信任签名不一致的扩展包」中显式开启。因此签名匹配或已信任的终端扩展无需手动扫描与启用。

本应用完整实现上述 Binder 契约，不依赖客户端的具体版本；若使用仅具备管理功能的旧版客户端，可通过状态页的测试命令与 IPC 自检验证本扩展自身功能。
