# 许可证与第三方组件说明

本仓库（LineCode 终端扩展）以 **GNU Affero General Public License v3.0** 发布，全文见仓库根目录
`LICENSE` 及 `licenses/AGPL-3.0.txt`。

选择 AGPL-3.0 的原因：本项目包含逐字复制自 LineCode 的 `:ipc` AIDL 契约（LineCode 采用
AGPL-3.0），并分发 GPL/LGPL 许可的内置运行时组件；AGPL-3.0 与这些许可证兼容，且允许
LineCode 作为调用方与终端扩展协作。

## 内置组件

| 组件 | 版本 / 来源 | 许可证 | 说明 |
|---|---|---|---|
| PRoot | 5.1.107.102，Termux aarch64 构建 | GPL-2.0 | 内置二进制，未修改；对应源码见下方链接 |
| PRoot loader | 同上 | GPL-2.0 | 同上 |
| libtalloc | 2.5.0，Termux aarch64 构建 | LGPL-3.0-or-later | 内置动态库，未修改 |
| libandroid-shmem | 0.7，Termux aarch64 构建 | BSD-2-Clause | 内置动态库，未修改；许可证全文见 `BSD-2-Clause-libandroid-shmem.txt` |
| BusyBox | 1.37.0，Ubuntu arm64 static 构建 | GPL-2.0 | 内置静态二进制，未修改 |
| Ubuntu Base | 22.04.5 LTS (jammy)，arm64 | 各软件包各自许可证 | 未修改的 Ubuntu Base 镜像；详见 `Ubuntu-Base-NOTICE.md` |
| CA 证书包 | curl.se `cacert.pem` | MPL-2.0 | Mozilla CA 证书集合 |
| LineCode `:ipc` AIDL | LineCode 项目 | AGPL-3.0 | 逐字复制，保持 Binder 二进制兼容；详见 `LineCode-IPC-NOTICE.md` |
| Gradle Wrapper | Gradle 9.4.1 | Apache-2.0 | 仅构建工具，不随 APK 分发 |

## 许可证全文

- `AGPL-3.0.txt` — GNU Affero General Public License v3.0
- `GPL-2.0.txt` — GNU General Public License v2.0（PRoot、BusyBox）
- `LGPL-3.0.txt` — GNU Lesser General Public License v3.0（libtalloc）
- `MPL-2.0.txt` — Mozilla Public License 2.0（CA 证书包）
- `Apache-2.0.txt` — Apache License 2.0（Gradle Wrapper）
- `BSD-2-Clause-libandroid-shmem.txt` — BSD 2-Clause（libandroid-shmem）

## GPL/LGPL 源码获取

PRoot、BusyBox、libtalloc、libandroid-shmem 均为未修改的上游/发行版二进制，其对应源码可从以下位置获取：

- PRoot（Termux 构建）：<https://github.com/termux/proot>，包源 <https://packages.termux.dev/apt/termux-main/pool/main/p/proot/>
- libtalloc：<https://www.samba.org/ftp/talloc/>，包源 <https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/>
- libandroid-shmem：<https://github.com/termux/libandroid-shmem>，包源 <https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/>
- BusyBox：<https://busybox.net/downloads/>，包源 <http://ports.ubuntu.com/ubuntu-ports/pool/main/b/busybox/>
- Ubuntu Base：<https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/>（各软件包版权文件位于镜像内 `/usr/share/doc/<package>/copyright`）

分发本仓库构建产物时，请同时保留本目录的许可证文件；GPL/LGPL 组件的对应源码可按上述地址获取，
或向分发者书面索取。

## 随 APK 分发

`licenses/` 目录由 Gradle 构建任务复制到 APK 的 `assets/licenses/`。安装后可通过 APK 内资源查看
全部许可证文本与说明。
