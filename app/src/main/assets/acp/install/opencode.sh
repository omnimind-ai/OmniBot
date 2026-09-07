# Shared by terminal setup and explicit assistant installation.
# OpenCode publishes different official binaries for glibc and musl rootfs.
set -eu
npm install -g --prefix /root/.npm-global --no-audit --no-fund opencode-ai@latest
omnibot_opencode_platform=opencode-linux-arm64
if [ -f /etc/alpine-release ]; then
    omnibot_opencode_platform=opencode-linux-arm64-musl
fi
omnibot_opencode_version=$(node -p "require('/root/.npm-global/lib/node_modules/opencode-ai/package.json').version")
omnibot_opencode_binary="/root/.npm-global/lib/node_modules/$omnibot_opencode_platform/bin/opencode"
# Reinstall the matching binary on an explicit install/update; executable bits
# alone do not prove that the loader exists or that the binary is current.
npm install -g --prefix /root/.npm-global --force --no-audit --no-fund --prefer-online "$omnibot_opencode_platform@$omnibot_opencode_version"
"$omnibot_opencode_binary" --version >/dev/null
ln -sf "$omnibot_opencode_binary" /root/.npm-global/bin/opencode
