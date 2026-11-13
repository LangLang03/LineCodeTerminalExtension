# Changelog / 更新日志

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
