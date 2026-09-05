set -eu
export PATH="/root/.npm-global/bin:$PATH"
export DSH_HOME="/root/.dsh/omnibot-acp"
# These are pnpm settings, so use pnpm's documented environment prefix.
# Copying package files avoids PRoot's hard-link emulation while hoisting
# retains the layout expected by the official DSH profile.
export PNPM_CONFIG_NODE_LINKER=hoisted
export PNPM_CONFIG_PACKAGE_IMPORT_METHOD=copy
DSH_PACKAGE_ROOT="/root/.npm-global/lib/node_modules/@deepseek-ai/dsh"
NPM_PRIMARY_REGISTRY="${OMNIBOT_NPM_REGISTRY:-https://registry.npmmirror.com}"
mkdir -p "$DSH_HOME"
npm config set prefix /root/.npm-global
if ! command -v pnpm >/dev/null 2>&1; then
  if ! npm install -g --no-audit --no-fund \
      --registry="$NPM_PRIMARY_REGISTRY" \
      pnpm@11.22.0; then
    npm install -g --no-audit --no-fund \
      --registry="https://registry.npmjs.org" \
      pnpm@11.22.0
  fi
fi
# A previous Android npm run may leave a seemingly installed package with
# an incomplete dependency tree. Keep this preflight structural and let
# the authoritative native/import checks run later in this script.
if [ ! -f "$DSH_PACKAGE_ROOT/package.json" ] || \
    [ ! -f "$DSH_PACKAGE_ROOT/lib/bin.js" ] || \
    { [ ! -f "$DSH_PACKAGE_ROOT/node_modules/node-pty/prebuilds/linux-arm64/pty.node" ] && \
      [ ! -f "$DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node" ]; } || \
    [ ! -d "$DSH_PACKAGE_ROOT/node_modules" ]; then
  npm cache clean --force >/dev/null 2>&1 || true
  rm -rf "$DSH_PACKAGE_ROOT" \
    /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-* 2>/dev/null || true
  install_dsh_runtime() {
    registry="$1"
    npm install -g --no-audit --no-fund --prefer-offline \
      --fetch-retries=5 --fetch-retry-factor=2 \
      --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=15000 \
      --fetch-timeout=120000 --loglevel=notice \
      --registry="$registry" \
      @deepseek-ai/dsh@next
  }
  if ! install_dsh_runtime "$NPM_PRIMARY_REGISTRY"; then
    rm -rf "$DSH_PACKAGE_ROOT" \
      /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-* 2>/dev/null || true
    install_dsh_runtime "https://registry.npmjs.org"
  fi
fi
# Some Android npm builds install the package but skip creating its bin
# shim. Recreate the vendor-declared executable from the installed package
# before invoking the official DSH plugin workflow; this is still the
# upstream CLI entrypoint, not a private ACP replacement.
if [ ! -x /root/.npm-global/bin/dsh ] && \
    [ -f /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js ]; then
  ln -sf /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
    /root/.npm-global/bin/dsh
fi
test -x /root/.npm-global/bin/dsh
# DSH's HMR plugin requires a Node internal flag. NODE_OPTIONS rejects
# this flag, so publish a tiny launcher that passes it as a CLI argument
# while still executing the vendor's official lib/bin.js entrypoint.
# The ACP transport is headless. Keep the Web-only plugins installed in
# the shared DSH profile, but do not activate them in this process: they
# wait for webServer/webRuntime and make the ACP tree fail after a slow
# initialize. This overlay is launch-scoped and never deletes user data.
printf '%s\n' '# OmniBot ACP headless overlay' \
  '- id: dsh-plugin-mgr' \
  '  disabled: true' \
  '- id: dsh-plugin-studio' \
  '  disabled: true' \
  '- id: uisfx' \
  '  disabled: true' \
  > "/root/.dsh/omnibot-acp/omnibot-acp-headless.patch.yml"
printf '%s\n' '#!/bin/sh' \
  'exec node --expose-internals /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --patch "/root/.dsh/omnibot-acp/omnibot-acp-headless.patch.yml" "$@"' \
  > /root/.npm-global/bin/dsh-acp-android
chmod 755 /root/.npm-global/bin/dsh-acp-android
test -x /root/.npm-global/bin/dsh-acp-android
# The official node-pty package ships a glibc linux-arm64 prebuild. Alpine
# can use gcompat; Ubuntu must not be failed by an unavailable apk command.
if command -v apk >/dev/null 2>&1; then
  apk add --no-cache gcompat >/dev/null 2>&1 || true
fi
if ! node -e "require('$DSH_PACKAGE_ROOT/node_modules/node-pty')" >/dev/null 2>&1; then
  PTY_ROOT="$DSH_PACKAGE_ROOT/node_modules/node-pty"
  PTY_VENDOR="$PTY_ROOT/prebuilds/linux-arm64/pty.node"
  PTY_VENDOR_COPY="$DSH_HOME/node-pty-linux-arm64.vendor.node"
  if [ -f "$PTY_VENDOR" ]; then
    cp -f "$PTY_VENDOR" "$PTY_VENDOR_COPY"
  fi
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache build-base python3 linux-headers util-linux-dev >/dev/null
  elif command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      build-essential python3 >/dev/null
  else
    printf '%s\n' 'DeepSeek Harness: no supported native build package manager found' >&2
    exit 1
  fi
  npm_config_build_from_source=true npm_config_nodedir= npm rebuild --prefix "$DSH_PACKAGE_ROOT/node_modules/node-pty" --build-from-source
  # node-gyp may leave an absolute Android data-path symlink. Proot sees
  # the Alpine root instead, so materialize the compiled addon as a regular
  # file before the runtime loads it.
  PTY_BUILD="$DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node"
  if [ -L "$PTY_BUILD" ]; then
    cp -Lf "$PTY_BUILD" "$PTY_BUILD.materialized"
    mv -f "$PTY_BUILD.materialized" "$PTY_BUILD"
  fi
  if [ ! -f "$PTY_BUILD" ] && [ -f "$PTY_VENDOR_COPY" ]; then
    mkdir -p "$PTY_ROOT/prebuilds/linux-arm64"
    cp -f "$PTY_VENDOR_COPY" "$PTY_ROOT/prebuilds/linux-arm64/pty.node"
  fi
fi
node -e "require('$DSH_PACKAGE_ROOT/node_modules/node-pty')" >/dev/null 2>&1
PROFILE_LAYOUT_MARKER="$DSH_HOME/.omnibot-profile-reset-v13"
profile_was_reset=0
# Profiles created before copy imports were enabled contain PRoot's
# `.l2s...0001 -> ...0002` hard-link emulation. pnpm reports those files
# as already installed and will not rewrite them. This DSH_HOME is owned by
# OmniBot, so rebuild its profile once for this layout revision; the marker
# preserves later user-installed plugins and commands on normal retries.
if [ ! -f "$PROFILE_LAYOUT_MARKER" ]; then
  rm -rf "$DSH_HOME/profiles"
  profile_was_reset=1
fi
configure_dsh_profile_pnpm() {
  profile_root="$DSH_HOME/profiles/acp"
  [ -f "$profile_root/pnpm-workspace.yaml" ] || return 0
  (
    cd "$profile_root"
    pnpm config set --location=project nodeLinker hoisted
    pnpm config set --location=project packageImportMethod copy
  )
}
dsh_native_health() {
  export DSH_HOME="/root/.dsh/omnibot-acp"
  export PATH="/root/.npm-global/bin:$PATH"
  command -v dsh >/dev/null 2>&1 &&
    command -v dsh-acp-android >/dev/null 2>&1 &&
    command -v pnpm >/dev/null 2>&1 &&
    test -f "$DSH_HOME/profiles/acp/package.json" &&
    test -f "$DSH_HOME/profiles/acp/node_modules/@openma/deepseek-harness-acp/package.json"
}
dsh_acp_profile_is_healthy() {
  dsh_native_health &&
    timeout 30 dsh-acp-android --profile acp --dump-config >/dev/null 2>&1
}
install_dsh_acp_adapter() {
  registry="$1"
  export npm_config_registry="$registry"
  plugin_status=0
  config_status=0
  # DSH forwards plugin arguments to pnpm. Explicitly select the workspace
  # root so this remains valid across supported pnpm versions.
  dsh plugin --profile acp add -w @openma/deepseek-harness-acp@latest || plugin_status="$?"
  configure_dsh_profile_pnpm || config_status="$?"
  if [ "$config_status" -eq 0 ] && dsh_acp_profile_is_healthy; then
    if [ "$plugin_status" -ne 0 ]; then
      printf '%s\n' \
        "DeepSeek Harness: plugin command exited $plugin_status, but the ACP profile passed health checks" >&2
    fi
    return 0
  fi
  return 1
}
# Follow the vendor workflow: DSH creates/updates the ACP profile and
# owns its plugin dependency graph, patch layers, tools, and commands.
# Preserve the persistent profile and its user plugins. A failed mirror
# attempt retries only the adapter operation against the official registry.
configure_dsh_profile_pnpm || true
if ! install_dsh_acp_adapter "$NPM_PRIMARY_REGISTRY"; then
  install_dsh_acp_adapter "https://registry.npmjs.org"
fi
# The ACP profile is persistent Harness state, not session state. Never
# remove dependencies from it during a reconnect or a normal Agent switch:
# user-installed DSH plugins, skills and commands must remain available to
# every later ACP session that uses this same profile. A broken or
# incompatible plugin must be reported by ACP initialize/health instead of
# being silently destroyed by the host.
test -f "$DSH_HOME/profiles/acp/package.json"
dsh_acp_profile_is_healthy
if [ "$profile_was_reset" -eq 1 ]; then
  : > "$PROFILE_LAYOUT_MARKER"
fi
