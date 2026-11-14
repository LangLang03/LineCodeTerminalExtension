# Changelog / 更新日志

## v1.2.0

### English

#### External file access

- **Shared storage bridge** - the provider now requests `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` and opts into legacy external storage (API 28), then bind-mounts the host volume `/storage/emulated/0` into the guest at `/sdcard`, `/storage/emulated/0` and `/root/sdcard`. LineCode external project paths therefore work unchanged inside Ubuntu.
- **One namespace for shell and file APIs** - foreground/background shell commands and the AIDL file operations share the same guest path mapper. Shared-storage paths are containment-checked, symlinks that escape the volume are rejected, and an ungranted permission leaves the mount points empty so operations fail closed instead of touching unexpected host paths.
- **Status page** - new "External file access" section with a grant button; when the runtime dialog is exhausted the button opens the application settings page. A grant takes effect on the next command without reinstalling the environment.
- **Provider descriptor** - `getProviderInfo()` reports version 1.2.0, the `/sdcard` and `/storage/emulated/0` mappings, `/root/sdcard`, and an `externalStorage` capability.

#### Tests and release

- 35 unit tests (new: shared-storage mapping and escape rejection, file-system access through the shared volume, shell bind assembly, mount-point creation, provider descriptor fields).
- Version `1.2.0` (versionCode 3); the release workflow publishes the signed APK on merge.

### 中文

#### 外部文件访问

- **共享存储桥接** - 提供方申请 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` 并以 API 28 的 legacy external storage 方式访问设备共享存储，随后把宿主 `/storage/emulated/0` 绑定到 guest 的 `/sdcard`、`/storage/emulated/0` 与 `/root/sdcard`。LineCode 的外部项目绝对路径因此可以在 Ubuntu 内直接使用。
- **Shell 与文件接口共用命名空间** - 前台/后台 Shell 命令与 AIDL 文件操作使用同一套 guest 路径映射；共享存储路径同样做边界校验，逃逸该卷的符号链接会被拒绝；未授权时挂载点保持为空，操作失败而不会访问意外的宿主路径。
- **状态页** - 新增「外部文件访问」区域与授权按钮；系统不再显示授权对话框时按钮改为打开应用设置页。授权后无需重装环境，下一条命令即生效。
- **提供方描述** - `getProviderInfo()` 报告版本 1.2.0、`/sdcard` 与 `/storage/emulated/0` 映射、`/root/sdcard` 以及 `externalStorage` 能力。

#### 测试与发布

- 35 个单元测试（新增：共享存储映射与逃逸拒绝、经共享卷的文件系统访问、Shell 绑定参数、挂载点创建、提供方描述字段）。
- 版本号 `1.2.0`（versionCode 3）；Release 工作流在合并后发布签名 APK。

## v1.1.0

### English

#### AIDL v2 background shell

- **Background guest tasks** - the provider now implements the v2 surface of `ITerminalProviderService` copied verbatim from LineCode `:ipc`: `startShellBackground` launches a guest command detached through PRoot and returns a handle immediately, `readShellBackground(handle, offset)` serves polled incremental reads against a contiguous prefix buffer (512 K character cap, prefix kept so offset semantics stay stable), `killShellBackground` terminates the whole guest process tree through the `/proc` walker, and `forgetShellBackground` frees the provider-side buffer. The AIDL copy ships the new `TerminalShellBackgroundState` parcelable.
- **Contract details** - output streams to the IPC callback and is retained for polling at the same time; the service waits for the Ubuntu environment (up to 180 s) before launching and returns 0 when it never became ready, -1 for empty commands or spawn failures; `getProviderInfo()` now reports `version` 1.1.0 with the `backgroundShell` capability.
- **Service teardown** - destroying the provider service kills every still-running background task.

#### Tests and release

- New `BackgroundGuestTasksTest` drives the pump/buffer/kill lifecycle with a scripted `Process` double: offset-addressed reads, streamed callback delivery, kill semantics, stale handles and the buffer cap. `ProviderInfoTest` asserts the new version and capability.
- Version `1.1.0` (versionCode 2); the release workflow publishes the signed APK on merge.

### 中文

#### AIDL v2 后台 Shell

- **后台 guest 任务** - 提供方现在实现从 LineCode `:ipc` 逐字复制的 `ITerminalProviderService` v2 接口：`startShellBackground` 经 PRoot 分离启动 guest 命令并立即返回句柄，`readShellBackground(handle, offset)` 基于连续前缀缓冲（512 K 字符上限，保留前缀使 offset 语义稳定）提供按偏移的轮询读取，`killShellBackground` 经 `/proc` 遍历终止整个 guest 进程树，`forgetShellBackground` 释放提供方侧缓冲。AIDL 副本新增 `TerminalShellBackgroundState` parcelable。
- **契约细节** - 输出同时流式回传给回调并保留用于轮询；启动前等待 Ubuntu 环境就绪（最长 180 秒），未就绪返回 0，空命令或启动失败返回 -1；`getProviderInfo()` 现在报告 `version` 1.1.0 与 `backgroundShell` 能力。
- **服务销毁** - provider service 销毁时终止所有仍在运行的后台任务。

#### 测试与发布

- 新增 `BackgroundGuestTasksTest`：以脚本化的 `Process` 测试替身驱动 pump/缓冲/kill 全链路——按偏移读取、回调流式投递、kill 语义、过期句柄与缓冲上限；`ProviderInfoTest` 断言新版本号与能力。
- 版本号 `1.1.0`（versionCode 2）；Release 工作流在合并后发布签名 APK。

## v1.0.0

### English

#### Initial release

- **Embedded Ubuntu terminal provider** - a standalone LineCode terminal-provider app that ships PRoot 5.1.107, busybox and an Ubuntu 22.04.5 LTS (arm64) root filesystem inside the APK, and exposes shell execution plus chunked file operations to LineCode over the `:ipc` AIDL contract (`ITerminalProviderService`). No third-party runtime dependencies beyond the Android framework.
- **Idempotent environment installation** - the root filesystem is extracted on first service binding (about 95 MB, 3497 entries), overlayed with IPv4 DNS, APT sandbox settings, shell profile, busybox symlinks and CA certificates, and marked by a versioned install marker. Re-install keeps `/root/workspace`; `executeShell` waits up to 180 s for readiness and reports `-3` while the environment is still being prepared.
- **Contract-faithful IPC** - the AIDL files are copied verbatim from LineCode `:ipc` for binary compatibility. `executeShell` returns the guest exit code synchronously (`-1` empty/failed launch, `-2` timeout with process-tree kill, `-3` not ready), output streams through the callback, and the file interface covers `readFile`, `writeFile`, `deleteFile`, `listDir`, `fileExists`, `fileSize`, `readFileChunk`, `writeFileChunk`, `getFileSize` and `listDirDetailed`.
- **Containment** - guest path resolution and tar extraction reject `..` escapes and symlink traversal; nothing outside the rootfs is touched. The guest runs in the app sandbox with pseudo-root only; no external storage access, `allowBackup=false`, no launcher entry.
- **Status page** - headless status activity for install/reinstall, a test command, an on-device IPC self-test (discovery, binding, providerInfo, guest file queries) and a runtime log; `auto_install` extra starts the installation without UI interaction.
- **Asset integrity** - runtime binaries are fetched and SHA-256 verified by `tools/fetch-runtime.sh`; the Ubuntu image is verified again by the installer before extraction. Licenses are packaged into `assets/licenses/` at build time.

#### Verification

- 23 unit tests cover tar extraction against a real Ubuntu image, guest path normalization and symlink limits, the guest file system, PRoot argv/environment assembly and providerInfo JSON.
- Verified on an x86_64 API 35 emulator: installation, status page flow and IPC self-test pass. Execution on arm64 hardware is pending on-device validation.

#### Release

- Version `1.0.0` (versionCode 1); the release workflow builds and publishes the signed APK on version-code increases.

### 中文

#### 首个版本

- **内置 Ubuntu 的终端提供者** - 独立的 LineCode 终端提供者应用：APK 内置 PRoot 5.1.107、busybox 与 Ubuntu 22.04.5 LTS（arm64）根文件系统，通过 `:ipc` 的 AIDL 契约（`ITerminalProviderService`）向 LineCode 提供 Shell 执行与分块文件操作。除 Android 框架外无第三方运行时依赖。
- **幂等的环境安装** - 首次绑定服务时解压根文件系统（约 95 MB、3497 个条目），写入 IPv4 DNS、APT sandbox 配置、shell profile、busybox 符号链接与 CA 证书，并以带版本的安装标记记录状态。重新安装保留 `/root/workspace`；`executeShell` 最多等待 180 秒就绪，未就绪时返回 `-3`。
- **契约一致的 IPC** - AIDL 文件逐字复制自 LineCode `:ipc` 以保证二进制兼容。`executeShell` 同步返回 guest 退出码（`-1` 命令为空或启动失败、`-2` 超时并终止进程树、`-3` 未就绪），输出经回调异步回传；文件接口覆盖 `readFile`、`writeFile`、`deleteFile`、`listDir`、`fileExists`、`fileSize`、`readFileChunk`、`writeFileChunk`、`getFileSize` 与 `listDirDetailed`。
- **边界约束** - guest 路径解析与 tar 解压均拒绝 `..` 越界与符号链接逃逸，不会触及 rootfs 之外的宿主文件。guest 进程以应用 UID 在沙箱内运行，仅伪 root；不申请外部存储，`allowBackup=false`，无桌面入口。
- **状态页** - 无桌面图标的状态 Activity：安装/重新安装、测试命令、设备侧 IPC 自检（服务发现、绑定、providerInfo、guest 文件查询）与运行日志；`auto_install` extra 可无交互直接开始安装。
- **资源完整性** - 运行时二进制由 `tools/fetch-runtime.sh` 下载并做 SHA-256 校验；Ubuntu 镜像在解压前由安装器再次校验。许可证在构建时打包进 `assets/licenses/`。

#### 验证

- 23 个单元测试覆盖：对真实 Ubuntu 镜像的 tar 解压、guest 路径规范化与符号链接限制、guest 文件系统、PRoot argv/环境变量组装、providerInfo JSON。
- 已在 x86_64 API 35 模拟器验证：安装、状态页流程与 IPC 自检通过；arm64 真机执行列入发布前验证项。

#### 版本

- 版本号 `1.0.0`（versionCode 1）；Release 工作流在 versionCode 增加时构建并发布签名 APK。
