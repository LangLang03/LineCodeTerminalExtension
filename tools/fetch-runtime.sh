#!/usr/bin/env bash
# Downloads and verifies the embedded runtime assets (PRoot + Ubuntu Base + busybox + CA certs).
# Requires: curl, ar, tar (with xz and zstd support), sha256sum.
set -euo pipefail

cd "$(dirname "$0")/.."
ASSETS="app/src/main/assets/runtime"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"
UBUNTU_SHA="075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb"
TALLOC_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb"
SHMEM_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
BUSYBOX_DEB_URL="http://ports.ubuntu.com/ubuntu-ports/pool/main/b/busybox/busybox-static_1.37.0-7ubuntu1.1_arm64.deb"
CACERT_URL="https://curl.se/ca/cacert.pem"

PROOT_SHA="3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41"
LOADER_SHA="1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5"
TALLOC_SHA="742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"
SHMEM_SHA="84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"
BUSYBOX_SHA="6db97dc211d50486ea94ecf3fbbc36a390f10d8eed86920aa356cb42bc30efde"
CACERT_SHA="427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285"

fetch() {
    local url="$1" target="$2"
    echo "fetching $(basename "$target")"
    curl -fsSL --retry 3 -o "$target" "$url"
}

verify() {
    local file="$1" expected="$2"
    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        echo "checksum mismatch for $file: expected $expected, got $actual" >&2
        exit 1
    fi
}

extract_deb() {
    local deb="$1" dest="$2"
    mkdir -p "$dest"
    ( cd "$dest" && ar x "$deb" )
    if [ -f "$dest/data.tar.xz" ]; then
        tar -xJf "$dest/data.tar.xz" -C "$dest"
    elif [ -f "$dest/data.tar.zst" ]; then
        tar --zstd -xf "$dest/data.tar.zst" -C "$dest"
    else
        echo "unsupported deb data format in $deb" >&2
        exit 1
    fi
}

fetch "$UBUNTU_URL" "$ASSETS/ubuntu-22.04.5-base-arm64.targz"
verify "$ASSETS/ubuntu-22.04.5-base-arm64.targz" "$UBUNTU_SHA"

fetch "$PROOT_DEB_URL" "$WORK/proot.deb"
extract_deb "$WORK/proot.deb" "$WORK/proot"
cp "$WORK/proot/data/data/com.termux/files/usr/bin/proot" "$ASSETS/proot"
cp "$WORK/proot/data/data/com.termux/files/usr/libexec/proot/loader" "$ASSETS/proot-loader"
verify "$ASSETS/proot" "$PROOT_SHA"
verify "$ASSETS/proot-loader" "$LOADER_SHA"

fetch "$TALLOC_DEB_URL" "$WORK/talloc.deb"
extract_deb "$WORK/talloc.deb" "$WORK/talloc"
cp "$WORK/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0" "$ASSETS/libtalloc.so.2"
verify "$ASSETS/libtalloc.so.2" "$TALLOC_SHA"

fetch "$SHMEM_DEB_URL" "$WORK/shmem.deb"
extract_deb "$WORK/shmem.deb" "$WORK/shmem"
cp "$WORK/shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" "$ASSETS/libandroid-shmem.so"
verify "$ASSETS/libandroid-shmem.so" "$SHMEM_SHA"

fetch "$BUSYBOX_DEB_URL" "$WORK/busybox.deb"
extract_deb "$WORK/busybox.deb" "$WORK/busybox"
cp "$WORK/busybox/usr/bin/busybox" "$ASSETS/busybox"
verify "$ASSETS/busybox" "$BUSYBOX_SHA"

fetch "$CACERT_URL" "$ASSETS/cacert.pem"
verify "$ASSETS/cacert.pem" "$CACERT_SHA"

chmod 644 "$ASSETS"/*
echo "embedded runtime is ready in $ASSETS"
