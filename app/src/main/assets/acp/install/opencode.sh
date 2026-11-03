# Shared by terminal setup and explicit assistant installation.
# OpenCode publishes different official binaries for glibc and musl rootfs.
set -eu
# Stage V2 without replacing the working V1 command. Android/proot requires
# selecting the rootfs libc explicitly; the native package needs no scripts.
mkdir -p /root/.npm-global
omnibot_opencode_stage=$(mktemp -d /root/.npm-global/.opencode-install.XXXXXX)
trap 'rm -rf "$omnibot_opencode_stage"' EXIT
npm install -g --prefix "$omnibot_opencode_stage" --omit=optional --ignore-scripts --no-bin-links --no-audit --no-fund @opencode/cli@latest
omnibot_opencode_platform=@opencode/cli-linux-arm64
if [ -f /etc/alpine-release ]; then
    omnibot_opencode_platform=@opencode/cli-linux-arm64-musl
fi
omnibot_opencode_version=$(node -p "require(process.argv[1]).version" "$omnibot_opencode_stage/lib/node_modules/@opencode/cli/package.json")
omnibot_opencode_binary="$omnibot_opencode_stage/lib/node_modules/$omnibot_opencode_platform/bin/opencode"
# Reinstall the matching binary on an explicit install/update; executable bits
# alone do not prove that the loader exists or that the binary is current.
npm install -g --prefix "$omnibot_opencode_stage" --force --ignore-scripts --no-bin-links --no-audit --no-fund --prefer-online "$omnibot_opencode_platform@$omnibot_opencode_version"
omnibot_opencode_reported_version=$("$omnibot_opencode_binary" --version)
case "$omnibot_opencode_reported_version" in
    "$omnibot_opencode_version"|"opencode v$omnibot_opencode_version") ;;
    *) echo "OpenCode binary version does not match the installed package." >&2; exit 1 ;;
esac
# Keep a one-time copy before the host writes its managed V2 Provider fields.
for omnibot_opencode_config in /root/.config/opencode/opencode.json /root/.config/opencode/opencode.jsonc; do
    if [ -f "$omnibot_opencode_config" ] && [ ! -e "$omnibot_opencode_config.pre-v2.bak" ]; then
        cp -p "$omnibot_opencode_config" "$omnibot_opencode_config.pre-v2.bak"
    fi
done
omnibot_opencode_package="$omnibot_opencode_stage/lib/node_modules/@opencode/cli"
mkdir -p "$omnibot_opencode_package/bin"
cp "$omnibot_opencode_binary" "$omnibot_opencode_package/bin/opencode.exe"
chmod 755 "$omnibot_opencode_package/bin/opencode.exe"
# Both releases own `opencode`; remove the V1 package only after V2 executes.
if [ -d /root/.npm-global/lib/node_modules/opencode-ai ]; then
    npm uninstall -g --prefix /root/.npm-global --no-audit --no-fund opencode-ai
fi
mkdir -p /root/.npm-global/lib/node_modules/@opencode /root/.npm-global/bin
if [ -d /root/.npm-global/lib/node_modules/@opencode/cli ]; then
    mv /root/.npm-global/lib/node_modules/@opencode/cli "$omnibot_opencode_stage/previous-cli"
fi
if ! mv "$omnibot_opencode_package" /root/.npm-global/lib/node_modules/@opencode/cli; then
    if [ -d "$omnibot_opencode_stage/previous-cli" ]; then
        mv "$omnibot_opencode_stage/previous-cli" /root/.npm-global/lib/node_modules/@opencode/cli
    fi
    exit 1
fi
ln -sf /root/.npm-global/lib/node_modules/@opencode/cli/bin/opencode.exe /root/.npm-global/bin/opencode
ln -sf /root/.npm-global/lib/node_modules/@opencode/cli/bin/opencode.exe /root/.npm-global/bin/opencode2
/root/.npm-global/bin/opencode --version
