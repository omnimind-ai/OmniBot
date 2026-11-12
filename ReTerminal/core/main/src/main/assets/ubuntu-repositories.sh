#!/bin/sh
set -e

if ! arch="$(dpkg --print-architecture)"; then
    echo "无法读取 Ubuntu 系统架构，请检查 dpkg 是否可用。" >&2
    exit 1
fi
if [ "$arch" != arm64 ]; then
    printf 'Ubuntu 系统架构为 %s；当前终端需要 arm64。请使用 arm64 的 Ubuntu 环境。\n' "$arch" >&2
    exit 1
fi

main_base="${1:-http://ports.ubuntu.com/ubuntu-ports}"
security_base="${2:-http://ports.ubuntu.com/ubuntu-ports}"
codename="$(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release | tr -d '"' | head -n 1)"
[ -n "$codename" ] || codename=noble

# Keep foreign dpkg architectures and user repositories intact. Our ports
# sources serve the native arm64 environment, so APT must not request amd64
# indexes from them when a user has enabled a foreign architecture.
sources=/etc/apt/sources.list.d/ubuntu.sources
sources_tmp="$sources.$$"
trap 'rm -f "$sources_tmp"' EXIT HUP INT TERM
mkdir -p /etc/apt/sources.list.d
cat > "$sources_tmp" <<EOF
Types: deb
URIs: $main_base
Suites: $codename $codename-updates $codename-backports
Components: main universe restricted multiverse
Architectures: $arch
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $security_base
Suites: $codename-security
Components: main universe restricted multiverse
Architectures: $arch
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
mv -f "$sources_tmp" "$sources"
