# LineCode IPC 契约说明

`app/src/main/aidl/cn/lineai/ipc/` 下的 AIDL 文件逐字复制自 LineCode 项目的 `:ipc` 模块，
用于保证 Binder 接口与 LineCode 客户端的二进制兼容：

- `IBaseIpcService.aidl`
- `terminal/ITerminalProviderService.aidl`
- `terminal/ITerminalProviderCallback.aidl`

LineCode 项目采用 **GNU Affero General Public License v3.0** 发布；本仓库同样以 AGPL-3.0 发布，
具体条款见仓库根目录 `LICENSE`。相关版权归 LineCode 项目作者所有。
