set -eu
export PATH="/root/.npm-global/bin:$PATH"
export DSH_HOME="/root/.dsh/omnibot-acp"
DSH_PACKAGE_ROOT="/root/.npm-global/lib/node_modules/@deepseek-ai/dsh"
NPM_PRIMARY_REGISTRY="${OMNIBOT_NPM_REGISTRY:-https://registry.npmmirror.com}"
mkdir -p "$DSH_HOME"
npm config set prefix /root/.npm-global
# A previous Android npm run may leave a seemingly installed package with
# an incomplete dependency tree. Keep this preflight structural and let
# the authoritative native/import checks run later in this script.
if ! node -e "const p=require('$DSH_PACKAGE_ROOT/package.json'); if(p.version !== '0.1.2-rc.1') process.exit(1)" >/dev/null 2>&1 ||
    [ ! -f "$DSH_PACKAGE_ROOT/node_modules/@deepseek-ai/dsh-acp-app/cordis.patch.yml" ] || \
    [ ! -f "$DSH_PACKAGE_ROOT/lib/bin.js" ] || \
    { [ ! -f "$DSH_PACKAGE_ROOT/node_modules/node-pty/prebuilds/linux-arm64/pty.node" ] && \
      [ ! -f "$DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node" ]; } || \
    [ ! -d "$DSH_PACKAGE_ROOT/node_modules" ]; then
  install_dsh_runtime() {
    registry="$1"
    npm install -g --no-audit --no-fund --prefer-offline \
      --fetch-retries=5 --fetch-retry-factor=2 \
      --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=15000 \
      --fetch-timeout=120000 --loglevel=notice \
      --registry="$registry" \
      @deepseek-ai/dsh@0.1.2-rc.1
  }
  if ! install_dsh_runtime "$NPM_PRIMARY_REGISTRY"; then
    install_dsh_runtime "https://registry.npmjs.org"
  fi
fi
# Android external browser launches are cross-site navigations. Establish a
# same-origin document after token verification before navigating to the app,
# so the official Strict cookie is sent. Keep all auth checks and cookie flags.
node <<'OMNIBOT_DSH_BROWSER_NAVIGATION'
const fs = require('node:fs');
const path = '/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-connection/lib/index.js';
const source = fs.readFileSync(path, 'utf8');
const marker = '/* omnibot: same-origin browser login navigation */';
if (!source.includes(marker)) {
  const start = source.indexOf('\t\t\t\tres.writeHead(303, {');
  const end = source.indexOf('\t\t\t\treturn false;', start);
  if (start < 0 || end < 0) throw new Error('Unsupported DeepSeek browser login implementation');
  const original = source.slice(start, end);
  if (!original.includes('"set-cookie": sessionCookie(') ||
      !original.includes('"location": "/"') || !original.includes('res.end();')) {
    throw new Error('Unsupported DeepSeek browser token exchange');
  }
  const updated = original
    .replace('res.writeHead(303, {', marker + '\n\t\t\t\tres.writeHead(200, {')
    .replace('"location": "/",', '"content-type": "text/html; charset=utf-8",')
    .replace('res.end();', 'res.end(\'<!doctype html><meta http-equiv="refresh" content="0;url=/"><title>Opening chat</title><a href="/">Continue</a>\');');
  fs.writeFileSync(path, source.slice(0, start) + updated + source.slice(end));
}
OMNIBOT_DSH_BROWSER_NAVIGATION
# Some Android npm builds install the package but skip creating its bin
# shim. Recreate the vendor-declared executable from the installed package
# before invoking the official DSH profile; this is still the
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
printf '%s\n' '#!/bin/sh' \
  'exec node --expose-internals /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js "$@"' \
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
# The official launcher auto-initializes shipped profiles on first use. Do
# that explicitly during preparation so a later ACP launch never races a
# missing profile. `--help` is the vendor's non-session control path: it
# boots the profile composition but does not claim the ACP stdio stream.
dsh-acp-android --profile acp --help >/dev/null
# Health must verify the persistent profile, not only the global npm tree.
# Preserve the profile and any user plugins on retries; never recreate it here.
test -f "$DSH_HOME/profiles/acp/package.json"
test -f "$DSH_HOME/profiles/acp/cordis.patch.yml"
node --input-type=module -e "import fs from 'node:fs'; const profile=JSON.parse(fs.readFileSync('$DSH_HOME/profiles/acp/package.json','utf8')); const bundles=profile?.dsh?.profile?.bundles; if (!Array.isArray(bundles) || !bundles.includes('@deepseek-ai/dsh-acp-app')) process.exit(1)" >/dev/null 2>&1
test -f "$DSH_PACKAGE_ROOT/node_modules/@deepseek-ai/dsh-acp-app/cordis.patch.yml"
