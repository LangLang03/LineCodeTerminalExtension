# Ubuntu Base 22.04.5 LTS (jammy, arm64) 说明

本应用内置的 Ubuntu Base 镜像来自 Ubuntu 官方（未修改）：

<https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz>

该镜像包含数百个软件包，各软件包按其自身许可证分发（包括 GPL、LGPL、MIT、BSD、Apache 等）。
完整许可证文本随镜像提供，位于 guest 文件系统的 `/usr/share/doc/<package>/copyright`；
发行版整体许可信息参见 <https://ubuntu.com/legal>。

首次安装时，本应用会向镜像写入网络、apt、shell profile、busybox 与 CA 证书等配置文件；不会修改
镜像中已有软件包的二进制或版权文件。
