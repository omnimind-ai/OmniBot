#!/usr/bin/env bash
# Install the develop-flavor debug APK to a connected Android device.
# Usage:
#   bash scripts/install-dev.sh              # build + install to USB device
#   bash scripts/install-dev.sh --skip-build # install already-built APK
#   bash scripts/install-dev.sh --device <serial>
#   bash scripts/install-dev.sh --local-sources --allow-dirty-runtime
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
FLUTTER_TARGET="${OOB_FLUTTER_TARGET:-lib/main_standard.dart}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot}"
SKIP_BUILD=0
SKIP_FLUTTER_WEB="${OOB_SKIP_FLUTTER_WEB:-0}"
INCLUDE_FLUTTER_WEB="${OOB_INCLUDE_FLUTTER_WEB:-0}"
CLEAN_INSTALL="${OOB_CLEAN_INSTALL:-0}"
CONFIGURE_MODEL_PROVIDER="${OOB_CONFIGURE_MODEL_PROVIDER:-required}"
MODEL_PROVIDER_BASE_URL="${LLMTHU_API_BASE_URL:-${LLMTHU_API_BASE:-https://llmapi.paratera.com}}"
MODEL_PROVIDER_API_KEY_ENV="${LLMTHU_API_KEY_ENV:-LLMTHU_API_KEY}"
MODEL_PROVIDER_MODEL_ID="${LLMTHU_MODEL:-GLM-5.1}"
MODEL_PROVIDER_PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-debug-runtime-provider}"
MODEL_PROVIDER_PROFILE_NAME="${OOB_PROVIDER_PROFILE_NAME:-LLMTHU GLM-5.1 (Debug)}"
MODEL_PROVIDER_SCENE_IDS="${OOB_MODEL_SCENE_IDS:-scene.dispatch.model,scene.vlm.operation.primary,scene.compactor.context.chat}"
MODEL_PROVIDER_DEVICE_BASE_URL="$MODEL_PROVIDER_BASE_URL"
ADB_PROVIDER_PROXY="${OOB_ADB_PROVIDER_PROXY:-0}"
ADB_PROVIDER_PROXY_PORT="${OOB_ADB_PROVIDER_PROXY_PORT:-18765}"
DEVICE_SERIAL=""
MODEL_PROVIDER_API_KEY=""
HOT_PROJECT=""

usage() {
  cat <<'EOF'
Usage:
  bash scripts/install-dev.sh [options]

Options:
  --skip-build          Skip Gradle build; install the last built APK directly.
  --local-sources       Package ../OmniFlow-exp and ../OmniTransfer directly.
  --allow-dirty-runtime Allow dirty local runtime sources for this build.
  --include-flutter-web Include the Flutter Web bundle in this debug APK. Slower.
  --skip-flutter-web    Force-skip the Flutter Web bundle during Gradle build.
  --fast                Alias for --skip-flutter-web.
  --clean-install       Uninstall the target package before installing.
  --hot-project <path>  Publish one Vibe project without rebuilding or reinstalling the APK.
  --device <serial>     Target a specific device (passed to adb -s).
  --apk <path>          Install this APK instead of the default debug APK.
  --flutter-target <p>  Flutter entrypoint for Gradle -Ptarget. Default: lib/main_standard.dart.
  --package <name>      Package to launch after install. Default: cn.com.omnimind.bot.
  --configure-provider  Require model-provider configuration after install.
  --no-configure-provider
                        Do not configure the model provider after install.
  --provider-base-url <url>
                        LLMTHU OpenAI-compatible API base URL. Default: https://llmapi.paratera.com.
  --adb-provider-proxy  Route Debug provider traffic through the adb-connected host.
                        Use when the device has no direct internet access.
  --provider-proxy-port <port>
                        Host/device loopback port for --adb-provider-proxy. Default: 18765.
  --api-key-env <name>  Env var containing the provider API key. Default: LLMTHU_API_KEY.
  --model <id>          Provider model id. Default: $LLMTHU_MODEL or GLM-5.1.
  --llmthu-base-url <url>
                        Backward-compatible alias for --provider-base-url.
  --llmthu-api-key-env <name>
                        Backward-compatible alias for --api-key-env.
  --llmthu-model <id>   Backward-compatible alias for --model.
  --help                Show this help text.

Defaults:
  When --device is omitted, this script prefers the first connected USB device
  over emulators. This is the canonical path for "install to device".
  Builds use the pinned embedded OmniFlow and OmniTransfer runtime. Set
  --local-sources to package the canonical sibling worktrees directly without
  copying or symlinks. Dirty overrides also require --allow-dirty-runtime.
  The debug APK validates and seeds LLMTHU GLM-5.1 on launch.
  Post-install configuration verifies that normal LLM and VLM scenes use the
  LLMTHU OpenAI-compatible Chat Completions profile. Codex mode keeps its
  separate ~/.codex/config.toml and auth.json configuration. Use
  --no-configure-provider only when preserving existing device configuration.
EOF
}

select_default_device() {
  local lines serial selected=""
  lines="$(adb devices -l | awk 'NR>1 && $2=="device" {print $0}')"
  if [[ -z "$lines" ]]; then
    echo "No online Android device found. Connect a device or start an emulator." >&2
    return 1
  fi

  while IFS= read -r line; do
    serial="$(awk '{print $1}' <<<"$line")"
    if [[ "$serial" != emulator-* ]]; then
      selected="$serial"
      break
    fi
    [[ -z "$selected" ]] && selected="$serial"
  done <<<"$lines"

  echo "$selected"
}

is_device_locked() {
  local trust window
  trust="$("${ADB[@]}" shell dumpsys trust 2>/dev/null | tr -d '\r' || true)"
  if grep -Eq '\(current\).*deviceLocked=1' <<<"$trust"; then
    return 0
  fi
  window="$("${ADB[@]}" shell dumpsys window 2>/dev/null | tr -d '\r' || true)"
  grep -Eq 'isKeyguardShowing=true|mDreamingLockscreen=true|mIsShowing=true' <<<"$window"
}

trimmed() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

adb_shell_arg() {
  printf '%q' "$1"
}

is_truthy() {
  case "$1" in
    1|true|TRUE|yes|YES|on|ON|required|REQUIRED) return 0 ;;
    *) return 1 ;;
  esac
}

is_disabled() {
  case "$1" in
    0|false|FALSE|no|NO|off|OFF|skip|SKIP|none|NONE) return 0 ;;
    *) return 1 ;;
  esac
}

resolve_env_value() {
  local name="$1"
  local value=""
  if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    return 1
  fi

  value="${!name:-}"
  if [[ -n "$(trimmed "$value")" ]]; then
    printf '%s' "$value"
    return 0
  fi

  if command -v zsh >/dev/null 2>&1 && [[ -f "$HOME/.zshrc" ]]; then
    local raw=""
    raw="$(
      zsh -ic 'name="$1"; value="${(P)name}"; printf "\n__OOB_KEY_BEGIN__\n%s\n__OOB_KEY_END__\n" "$value"' \
        oob-read-env "$name" 2>/dev/null || true
    )"
    if [[ "$raw" == *"__OOB_KEY_BEGIN__"* && "$raw" == *"__OOB_KEY_END__"* ]]; then
      value="${raw#*__OOB_KEY_BEGIN__$'\n'}"
      value="${value%%$'\n'__OOB_KEY_END__*}"
      if [[ -n "$(trimmed "$value")" ]]; then
        printf '%s' "$value"
        return 0
      fi
    fi
  fi

  return 1
}

ensure_java_runtime() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] &&
    "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
    export PATH="$JAVA_HOME/bin:$PATH"
    return 0
  fi
  if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    return 0
  fi

  local detected_home=""
  if [[ "$(uname -s)" == "Darwin" ]]; then
    detected_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  local candidates=(
    "$detected_home"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] &&
      "$candidate/bin/java" -version >/dev/null 2>&1; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "  -> using Java runtime: $JAVA_HOME"
      return 0
    fi
  done

  echo "No Java runtime found. Install JDK 17 or Android Studio, then retry." >&2
  return 1
}

provider_models_url() {
  local base_url
  base_url="$(trimmed "$1")"
  base_url="${base_url%/}"
  if [[ "$base_url" =~ /v[0-9]+([.][0-9]+)?$ ]]; then
    printf '%s/models' "$base_url"
  else
    printf '%s/v1/models' "$base_url"
  fi
}

validate_provider_pair() {
  local label="$1"
  local base_url="$2"
  local api_key="$3"
  local model_id="$4"
  local models_url response_file http_status

  models_url="$(provider_models_url "$base_url")"
  response_file="$(mktemp "${TMPDIR:-/tmp}/openomnibot-models.XXXXXX")"
  if ! http_status="$(curl \
    --silent \
    --show-error \
    --location \
    --max-time 30 \
    --output "$response_file" \
    --write-out '%{http_code}' \
    "$models_url" \
    -H "Authorization: Bearer $api_key"
  )"; then
    rm -f "$response_file"
    echo "$label provider preflight could not reach $models_url." >&2
    return 1
  fi

  if [[ "$http_status" != 2* ]]; then
    rm -f "$response_file"
    echo "$label provider preflight failed: HTTP $http_status from $models_url." >&2
    return 1
  fi

  if ! python3 - "$response_file" "$model_id" <<'PY'
import json
from pathlib import Path
import sys

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
model = sys.argv[2]
models = payload.get("data", []) if isinstance(payload, dict) else []
raise SystemExit(0 if any(
    isinstance(item, dict) and item.get("id") == model
    for item in models
) else 1)
PY
  then
    rm -f "$response_file"
    echo "$label provider preflight failed: model '$model_id' is not available at $models_url." >&2
    return 1
  fi

  rm -f "$response_file"
  echo "  -> $label credentials and model verified"
}

start_adb_provider_proxy() {
  local upstream="$MODEL_PROVIDER_BASE_URL"
  local port="$ADB_PROVIDER_PROXY_PORT"
  local proxy_script="$ROOT_DIR/scripts/debug-provider-proxy.py"
  local state_root="${TMPDIR:-/tmp}/openomnibot-provider-proxy"
  local log_file="$state_root-$DEVICE_SERIAL-$port.log"
  local pid_file="$state_root-$DEVICE_SERIAL-$port.pid"
  local service_label="com.omnimind.openomnibot-provider-proxy-$port"
  local python_bin=""
  local using_launchd=0

  [[ "$port" =~ ^[0-9]+$ ]] && [[ "$port" -ge 1024 ]] && [[ "$port" -le 65535 ]] || {
    echo "Invalid provider proxy port: $port" >&2
    return 1
  }
  python_bin="$(command -v python3)"
  if [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl >/dev/null 2>&1; then
    # A provider URL written to the device outlives this installer process. Keep
    # its host-side endpoint alive for the same lifetime instead of leaving a
    # detached child that shells and task runners are free to reap.
    launchctl remove "$service_label" >/dev/null 2>&1 || true
    launchctl submit -l "$service_label" -- \
      "$python_bin" "$proxy_script" \
      --host 127.0.0.1 \
      --port "$port" \
      --upstream "$upstream"
    using_launchd=1
    rm -f "$pid_file"
  elif ! curl --noproxy '*' --fail --silent --show-error --max-time 2 \
    "http://127.0.0.1:$port/__health" >/dev/null 2>&1; then
    nohup python3 "$proxy_script" \
      --host 127.0.0.1 \
      --port "$port" \
      --upstream "$upstream" \
      >"$log_file" 2>&1 &
    printf '%s\n' "$!" >"$pid_file"
  fi

  local deadline=$((SECONDS + 10))
  while [[ "$SECONDS" -lt "$deadline" ]]; do
    if curl --noproxy '*' --fail --silent --show-error --max-time 2 \
      "http://127.0.0.1:$port/__health" >/dev/null 2>&1; then
      break
    fi
    sleep 0.2
  done
  curl --noproxy '*' --fail --silent --show-error --max-time 2 \
    "http://127.0.0.1:$port/__health" >/dev/null
  "${ADB[@]}" reverse "tcp:$port" "tcp:$port" >/dev/null
  MODEL_PROVIDER_DEVICE_BASE_URL="http://127.0.0.1:$port"
  # Do not require curl (or any other optional shell utility) on Android. The
  # host-side health check above validates the proxy; adb reverse is the actual
  # device transport setup, and the first provider request is the end-to-end
  # verification. Stock emulators commonly ship without curl.
  echo "  -> adb provider proxy ready: device=$MODEL_PROVIDER_DEVICE_BASE_URL upstream=$upstream"
  if [[ "$using_launchd" -eq 1 ]]; then
    echo "     proxy_service=$service_label"
  else
    echo "     proxy_pid_file=$pid_file"
  fi
}

API_KEY_RESOLVED_ENV=""
resolve_provider_api_key() {
  local value=""
  API_KEY_RESOLVED_ENV=""
  MODEL_PROVIDER_API_KEY=""

  if value="$(resolve_env_value "$MODEL_PROVIDER_API_KEY_ENV")"; then
    API_KEY_RESOLVED_ENV="$MODEL_PROVIDER_API_KEY_ENV"
    MODEL_PROVIDER_API_KEY="$value"
    return 0
  fi
  return 1
}

verify_apk_runtime_bundle() {
  python3 - "$APK" <<'PY'
from __future__ import annotations

import json
import hashlib
from io import BytesIO
from pathlib import Path
import sys
from zipfile import ZipFile

apk = Path(sys.argv[1])
catalog_entry = "assets/catalog.v1.json"
plugin_specs = (
    {
        "id": "com.omnimind.omni-vlm-lite",
        "required_skill_files": (
            "scripts/runtime/runtime.properties",
        ),
        "requires_bootstrap": True,
        "requires_schema": True,
    },
    {
        "id": "com.omnimind.vibe-project-builder",
        "required_skill_files": ("SKILL.md", "bundle.json"),
        "expected_tools": {"project_contract", "project_check", "project_publish"},
        "must_be_visible": True,
        "optional": True,
    },
)


def parse_properties(payload: bytes) -> dict[str, str]:
    values = {}
    for raw_line in payload.decode("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()

with ZipFile(apk) as archive:
    entries = set(archive.namelist())
    if catalog_entry not in entries:
        raise SystemExit(f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- missing {catalog_entry}")
    catalog = json.loads(archive.read(catalog_entry))

    if catalog.get("schemaVersion") != 1:
        raise SystemExit(
            "APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- unsupported catalog schemaVersion"
        )
    verified = []
    catalog_plugins = catalog.get("plugins", [])
    for spec in plugin_specs:
        plugin_id = spec["id"]
        plugin = next(
            (item for item in catalog_plugins if item.get("id") == plugin_id),
            None,
        )
        if plugin is None:
            if spec.get("optional"):
                continue
            raise SystemExit(
                f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- missing plugin {plugin_id}"
            )
        if plugin.get("kind") != "runtime_bundle":
            raise SystemExit(
                f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} is not a runtime_bundle"
            )
        if spec.get("must_be_visible") and (
            (plugin.get("presentation") or {}).get("visibility") == "hidden"
        ):
            raise SystemExit(
                f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} is hidden"
            )

        runtime_skill = plugin.get("runtimeSkill") or {}
        base = runtime_skill.get("packagedAssetPath", "").strip("/")
        packaged_archive = runtime_skill.get("packagedArchivePath", "").strip("/")
        if not base and not packaged_archive:
            raise SystemExit(
                f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} has no packaged runtime"
            )
        runtime_archive = None
        runtime_entries = entries
        runtime_prefix = f"assets/{base}/" if base else ""
        reported_path = f"assets/{base}" if base else f"assets/{packaged_archive}"
        if packaged_archive:
            archive_entry = f"assets/{packaged_archive}"
            if archive_entry not in entries:
                raise SystemExit(
                    f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} missing {archive_entry}"
                )
            archive_payload = archive.read(archive_entry)
            expected_archive_sha = str(
                runtime_skill.get("componentArchiveSha256") or ""
            ).lower()
            actual_archive_sha = sha256(archive_payload)
            if expected_archive_sha != actual_archive_sha:
                raise SystemExit(
                    f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} archive checksum mismatch "
                    f"expected={expected_archive_sha or '<missing>'} actual={actual_archive_sha}"
                )
            runtime_archive = ZipFile(BytesIO(archive_payload))
            runtime_entries = set(runtime_archive.namelist())
            runtime_prefix = ""

        required = {
            *(f"{runtime_prefix}{path}" for path in spec["required_skill_files"]),
        }
        if runtime_archive is None:
            required.add(
                f"{runtime_prefix}{runtime_skill.get('markerFile', 'PACKAGED_RUNTIME_SKILL')}"
            )
            if spec.get("requires_bootstrap"):
                required.add(
                    f"{runtime_prefix}{runtime_skill.get('bootstrapScript', 'scripts/bootstrap_runtime.py')}"
                )
        else:
            required.update({"component.json", "INSTALL_DIR.json"})
        if spec.get("requires_schema"):
            schema_base = (
                (runtime_skill.get("schemaAssetPath") or "").strip("/")
                if runtime_archive is None
                else "scripts/runtime/python/schemas"
            )
            required.add(f"{runtime_prefix}{schema_base}/oob/oob_canonical_actions.v1.json")

        missing = sorted(entry for entry in required if entry not in runtime_entries)
        if missing:
            raise SystemExit(
                f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} missing "
                + ", ".join(missing)
            )

        read_runtime = runtime_archive.read if runtime_archive is not None else archive.read
        if spec.get("requires_schema"):
            runtime_properties_entry = f"{runtime_prefix}scripts/runtime/runtime.properties"
            runtime_properties = parse_properties(read_runtime(runtime_properties_entry))
            schema_checksums = {
                key.removeprefix("schema.").removesuffix(".sha256"): value.lower()
                for key, value in runtime_properties.items()
                if key.startswith("schema.") and key.endswith(".sha256")
            }
            if not schema_checksums:
                raise SystemExit(
                    f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} declares no schema checksums"
                )
            actual_checksums = {}
            for file_name, expected_checksum in sorted(schema_checksums.items()):
                schema_entry = f"{runtime_prefix}{schema_base}/oob/{file_name}"
                if schema_entry not in runtime_entries:
                    raise SystemExit(
                        f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} missing {schema_entry}"
                    )
                actual_checksum = sha256(read_runtime(schema_entry))
                actual_checksums[file_name] = actual_checksum
                if actual_checksum != expected_checksum:
                    raise SystemExit(
                        f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} schema checksum mismatch "
                        f"{file_name}: expected={expected_checksum} actual={actual_checksum}"
                    )
            bridge_file = "omniflow_android_bridge.v2.json"
            bridge_checksum = runtime_properties.get("bridge.contract.sha256", "").lower()
            if bridge_checksum != actual_checksums.get(bridge_file):
                raise SystemExit(
                    f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} bridge contract checksum mismatch "
                    f"expected={bridge_checksum or '<missing>'} "
                    f"actual={actual_checksums.get(bridge_file, '<missing>')}"
                )
        expected_tools = spec.get("expected_tools")
        if expected_tools:
            bundle_entry = f"{runtime_prefix}bundle.json"
            bundle = json.loads(read_runtime(bundle_entry))
            actual_tools = {
                tool.get("name")
                for tool in bundle.get("tools", [])
                if isinstance(tool, dict)
            }
            if actual_tools != expected_tools:
                raise SystemExit(
                    f"APK_RUNTIME_BUNDLE_CONTRACT=FAIL\n- {plugin_id} tools "
                    f"expected={sorted(expected_tools)} actual={sorted(actual_tools)}"
                )
        if runtime_archive is not None:
            runtime_archive.close()
        verified.append((plugin, reported_path))

print("APK_RUNTIME_BUNDLE_CONTRACT=PASS")
for plugin, packaged_path in verified:
    print(f"- plugin={plugin.get('id', '')}")
    print(f"- version={plugin.get('version', '')}")
    print(f"- packaged_skill={packaged_path}")
PY
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --local-sources)
      export OOB_OMNIFLOW_SOURCE_DIR="$ROOT_DIR/../OmniFlow-exp"
      export OOB_OMNITRANSFER_SOURCE_DIR="$ROOT_DIR/../OmniTransfer"
      OOB_REFRESH_PREBUILT_RUNTIME=1
      ;;
    --allow-dirty-runtime) export OOB_ALLOW_DIRTY_RUNTIME_SOURCES=1 ;;
    --skip-flutter-web|--fast) SKIP_FLUTTER_WEB=1 ;;
    --include-flutter-web) INCLUDE_FLUTTER_WEB=1; SKIP_FLUTTER_WEB=0 ;;
    --clean-install) CLEAN_INSTALL=1 ;;
    --hot-project)
      [[ $# -lt 2 ]] && { echo "--hot-project requires a directory" >&2; exit 1; }
      HOT_PROJECT="$2"; shift ;;
    --hot-project=*) HOT_PROJECT="${1#--hot-project=}" ;;
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"; shift ;;
    --device=*) DEVICE_SERIAL="${1#--device=}" ;;
    --apk)
      [[ $# -lt 2 ]] && { echo "--apk requires a path" >&2; exit 1; }
      APK="$2"; shift ;;
    --apk=*) APK="${1#--apk=}" ;;
    --flutter-target)
      [[ $# -lt 2 ]] && { echo "--flutter-target requires a path" >&2; exit 1; }
      FLUTTER_TARGET="$2"; shift ;;
    --flutter-target=*) FLUTTER_TARGET="${1#--flutter-target=}" ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a package name" >&2; exit 1; }
      PACKAGE_NAME="$2"; shift ;;
    --package=*) PACKAGE_NAME="${1#--package=}" ;;
    --configure-provider) CONFIGURE_MODEL_PROVIDER=required ;;
    --no-configure-provider) CONFIGURE_MODEL_PROVIDER=0 ;;
    --provider-base-url|--base-url)
      [[ $# -lt 2 ]] && { echo "$1 requires a URL" >&2; exit 1; }
      MODEL_PROVIDER_BASE_URL="$2"; shift ;;
    --provider-base-url=*|--base-url=*) MODEL_PROVIDER_BASE_URL="${1#*=}" ;;
    --adb-provider-proxy) ADB_PROVIDER_PROXY=1 ;;
    --provider-proxy-port)
      [[ $# -lt 2 ]] && { echo "--provider-proxy-port requires a port" >&2; exit 1; }
      ADB_PROVIDER_PROXY_PORT="$2"; shift ;;
    --provider-proxy-port=*) ADB_PROVIDER_PROXY_PORT="${1#*=}" ;;
    --api-key-env)
      [[ $# -lt 2 ]] && { echo "--api-key-env requires an env var name" >&2; exit 1; }
      MODEL_PROVIDER_API_KEY_ENV="$2"; shift ;;
    --api-key-env=*) MODEL_PROVIDER_API_KEY_ENV="${1#--api-key-env=}" ;;
    --model)
      [[ $# -lt 2 ]] && { echo "--model requires a model id" >&2; exit 1; }
      MODEL_PROVIDER_MODEL_ID="$2"; shift ;;
    --model=*) MODEL_PROVIDER_MODEL_ID="${1#--model=}" ;;
    --llmthu-base-url)
      [[ $# -lt 2 ]] && { echo "--llmthu-base-url requires a URL" >&2; exit 1; }
      MODEL_PROVIDER_BASE_URL="$2"; shift ;;
    --llmthu-base-url=*) MODEL_PROVIDER_BASE_URL="${1#*=}" ;;
    --llmthu-api-key-env)
      [[ $# -lt 2 ]] && { echo "--llmthu-api-key-env requires an env var name" >&2; exit 1; }
      MODEL_PROVIDER_API_KEY_ENV="$2"; shift ;;
    --llmthu-api-key-env=*) MODEL_PROVIDER_API_KEY_ENV="${1#--llmthu-api-key-env=}" ;;
    --llmthu-model)
      [[ $# -lt 2 ]] && { echo "--llmthu-model requires a model id" >&2; exit 1; }
      MODEL_PROVIDER_MODEL_ID="$2"; shift ;;
    --llmthu-model=*) MODEL_PROVIDER_MODEL_ID="${1#--llmthu-model=}" ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
  shift
done

if [[ "${OOB_REFRESH_PREBUILT_RUNTIME:-0}" -eq 1 ]]; then
  python3 "$ROOT_DIR/scripts/build-prebuilt-omniflow-runtime.py" \
    --omniflow-root "$OOB_OMNIFLOW_SOURCE_DIR" \
    --omnitransfer-root "$OOB_OMNITRANSFER_SOURCE_DIR"
fi

if [[ -z "$DEVICE_SERIAL" ]]; then
  DEVICE_SERIAL="$(select_default_device)"
fi
ADB=(adb -s "$DEVICE_SERIAL")

# ── 1. Check device ────────────────────────────────────────────────────────────
echo "Checking for connected device..."
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "Device is not reachable: $DEVICE_SERIAL" >&2
  echo "Connected devices:" >&2
  adb devices -l >&2 || true
  exit 1
fi
DEVICE_NAME="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo 'unknown')"
echo "  -> $DEVICE_SERIAL ($DEVICE_NAME)"
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
if is_device_locked; then
  cat >&2 <<EOF
Device $DEVICE_SERIAL is locked. Unlock the phone before installing; otherwise
the Android package installer can reject the APK with INSTALL_FAILED_ABORTED.
EOF
  exit 1
fi

MODEL_PROVIDER_DEVICE_BASE_URL="$MODEL_PROVIDER_BASE_URL"
if is_truthy "$ADB_PROVIDER_PROXY"; then
  start_adb_provider_proxy
fi

if [[ -n "$HOT_PROJECT" ]]; then
  echo "The standalone App publisher was removed; use MCP/plugin tools." >&2
  exit 1
fi

# ── 2. Build ───────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo ""
  echo "Building develop debug APK..."
  ensure_java_runtime
  if ! BUNDLED_LLMTHU_API_KEY="$(resolve_env_value "$MODEL_PROVIDER_API_KEY_ENV")"; then
    echo "$MODEL_PROVIDER_API_KEY_ENV is required to build the bundled LLMTHU debug provider." >&2
    exit 1
  fi
  validate_provider_pair \
    "LLMTHU" \
    "$MODEL_PROVIDER_BASE_URL" \
    "$BUNDLED_LLMTHU_API_KEY" \
    "$MODEL_PROVIDER_MODEL_ID"
  export LLMTHU_API_BASE="$MODEL_PROVIDER_BASE_URL"
  export LLMTHU_API_KEY="$BUNDLED_LLMTHU_API_KEY"
  export LLMTHU_MODEL="$MODEL_PROVIDER_MODEL_ID"
  unset BUNDLED_LLMTHU_API_KEY
  echo "  -> bundling LLMTHU default (key hidden)"
  echo "     base_url=$LLMTHU_API_BASE"
  echo "     model=$LLMTHU_MODEL"
  if [[ -n "${OOB_OMNIFLOW_SOURCE_DIR:-}" ]]; then
    echo "  -> using explicit OmniFlow source: $OOB_OMNIFLOW_SOURCE_DIR"
  else
    echo "  -> using pinned embedded OmniFlow source"
  fi
  if [[ -n "${OOB_OMNITRANSFER_SOURCE_DIR:-}" ]]; then
    echo "  -> using explicit OmniTransfer source: $OOB_OMNITRANSFER_SOURCE_DIR"
  else
    echo "  -> using pinned embedded OmniTransfer source"
  fi
  if [[ "${OOB_ALLOW_DIRTY_RUNTIME_SOURCES:-}" =~ ^(1|true|yes|on)$ ]]; then
    echo "  -> dirty explicit runtime sources are allowed"
  fi
  chmod +x ./gradlew
  GRADLE_ARGS=(
    assembleDevelopStandardDebug
    -Ptarget="$FLUTTER_TARGET"
    --build-cache
    -q
  )
  if [[ "$SKIP_FLUTTER_WEB" == "1" || "$SKIP_FLUTTER_WEB" == "true" ]]; then
    echo "  -> skipping Flutter Web bundle"
    GRADLE_ARGS+=(-POOB_SKIP_FLUTTER_WEB=true)
  elif [[ "$INCLUDE_FLUTTER_WEB" == "1" || "$INCLUDE_FLUTTER_WEB" == "true" ]]; then
    echo "  -> including Flutter Web bundle"
    GRADLE_ARGS+=(-POOB_FLUTTER_WEB_MODE=include)
  else
    echo "  -> Flutter Web bundle omitted for debug build"
    GRADLE_ARGS+=(-POOB_SKIP_FLUTTER_WEB=true)
  fi
  ./gradlew "${GRADLE_ARGS[@]}"
  echo "Build complete."
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  echo "Run without --skip-build first." >&2
  exit 1
fi

verify_apk_runtime_bundle

package_installed() {
  "${ADB[@]}" shell pm path "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

uninstall_existing_package() {
  if ! package_installed; then
    echo "  -> no existing $PACKAGE_NAME install found"
    return 0
  fi

  echo "  -> uninstalling existing $PACKAGE_NAME"
  "${ADB[@]}" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
  set +e
  local uninstall_output uninstall_status
  uninstall_output="$("${ADB[@]}" uninstall "$PACKAGE_NAME" 2>&1)"
  uninstall_status=$?
  set -e
  printf '%s\n' "$uninstall_output"
  return "$uninstall_status"
}

run_adb_install() {
  set +e
  INSTALL_OUTPUT="$("${ADB[@]}" install -r -d "$APK" 2>&1)"
  INSTALL_STATUS=$?
  set -e
  printf '%s\n' "$INSTALL_OUTPUT"
  return "$INSTALL_STATUS"
}

install_failure_wants_clean_retry() {
  grep -Eiq \
    'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE|INSTALL_FAILED_ALREADY_EXISTS|signatures do not match|Package .*already|already installed|existing package' \
    <<<"$INSTALL_OUTPUT"
}

print_install_rejection_hint() {
  if grep -Eq 'INSTALL_FAILED_ABORTED|User rejected permissions' <<<"$INSTALL_OUTPUT"; then
    cat >&2 <<'EOF'

Install was rejected on the device.
Unlock the phone and accept the install / USB debugging permission prompt, then rerun:
  bash scripts/install-dev.sh --skip-build
EOF
  fi
}

install_apk_with_retry() {
  if is_truthy "$CLEAN_INSTALL"; then
    echo "Clean install requested."
    uninstall_existing_package
  fi

  if run_adb_install; then
    return 0
  fi

  local first_status="$INSTALL_STATUS"
  if install_failure_wants_clean_retry; then
    cat >&2 <<EOF

Existing package/signature blocked the in-place update. The script stopped
before uninstalling, because uninstalling resets app data and Android/ColorOS
permissions. If data loss is intentional, rerun with --clean-install.
EOF
  fi

  print_install_rejection_hint
  exit "$first_status"
}

configure_model_provider() {
  if is_disabled "$CONFIGURE_MODEL_PROVIDER"; then
    return 0
  fi

  local base_url model_id api_key
  base_url="$(trimmed "$MODEL_PROVIDER_DEVICE_BASE_URL")"
  model_id="$(trimmed "$MODEL_PROVIDER_MODEL_ID")"
  if [[ -z "$base_url" || -z "$model_id" ]]; then
    if is_truthy "$CONFIGURE_MODEL_PROVIDER"; then
      echo "Provider base URL and model id must not be empty." >&2
      return 1
    fi
    echo "Model provider config skipped; provider base URL or model id is empty."
    return 0
  fi

  if ! resolve_provider_api_key; then
    if is_truthy "$CONFIGURE_MODEL_PROVIDER"; then
      echo "$MODEL_PROVIDER_API_KEY_ENV is required for provider config." >&2
      return 1
    fi
    echo "Model provider config skipped; set $MODEL_PROVIDER_API_KEY_ENV."
    return 0
  fi
  api_key="$MODEL_PROVIDER_API_KEY"

  echo ""
  echo "Configuring default model provider..."
  echo "  -> provider_base_url=$base_url"
  echo "  -> provider_model=$model_id"
  echo "  -> provider_key_env=$API_KEY_RESOLVED_ENV"
  local result_file="files/debug-model-provider-config-result.json"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$result_file" 2>/dev/null || true

  set +e
  local config_output config_status
  config_output="$("${ADB[@]}" shell am broadcast \
    -a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER \
    -n "$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugModelProviderConfigReceiver" \
    --es baseUrl "$(adb_shell_arg "$base_url")" \
    --es apiKey "$(adb_shell_arg "$api_key")" \
    --es modelId "$(adb_shell_arg "$model_id")" \
    --es profileId "$(adb_shell_arg "$MODEL_PROVIDER_PROFILE_ID")" \
    --es name "$(adb_shell_arg "$MODEL_PROVIDER_PROFILE_NAME")" \
    --es protocolType "openai_compatible" \
    --es wireApi "chat_completions" \
    --es sceneIds "$(adb_shell_arg "$MODEL_PROVIDER_SCENE_IDS")" 2>&1)"
  config_status=$?
  set -e
  if [[ "$config_status" -ne 0 ]]; then
    printf '%s\n' "$config_output" >&2
    return "$config_status"
  fi
  local deadline=$((SECONDS + 10))
  local result=""
  while [[ "$SECONDS" -lt "$deadline" ]]; do
    result="$("${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$result_file" 2>/dev/null || true)"
    if [[ -n "$(trimmed "$result")" ]]; then
      break
    fi
    sleep 0.2
  done
  if [[ -z "$(trimmed "$result")" ]]; then
    echo "Provider configuration receiver did not return a result." >&2
    return 1
  fi
  if ! python3 - \
    "$result" \
    "$MODEL_PROVIDER_PROFILE_ID" \
    "$MODEL_PROVIDER_MODEL_ID" \
    "$MODEL_PROVIDER_SCENE_IDS" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
profile_id = sys.argv[2]
model_id = sys.argv[3]
scene_ids = sys.argv[4]
profiles = {item.get("id"): item for item in payload.get("profiles", [])}
profile = profiles.get(profile_id, {})
bindings = payload.get("sceneBindings", [])
required_scenes = {
    scene.strip()
    for scene in scene_ids.replace(";", ",").split(",")
    if scene.strip()
}
bound_scenes = {
    item.get("sceneId")
    for item in bindings
    if item.get("providerProfileId") == profile_id and item.get("modelId") == model_id
}
valid = (
    payload.get("success") is True
    and payload.get("editingProfileId") == profile_id
    and profile.get("sourceType") == "custom"
    and profile.get("protocolType") == "openai_compatible"
    and profile.get("wireApi") == "chat_completions"
    and profile.get("apiKeyConfigured") is True
    and required_scenes <= bound_scenes
)
if not valid:
    print(json.dumps(payload, ensure_ascii=False), file=sys.stderr)
    raise SystemExit(1)
PY
  then
    echo "Provider configuration verification failed." >&2
    return 1
  fi
  echo "  -> LLM and VLM configured as LLMTHU Chat Completions (not Codex mode)"
}

launch_installed_app() {
  "${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 \
    >/dev/null 2>&1
}

APK_SIZE_MB="$(du -m "$APK" | cut -f1)"
echo ""
echo "Installing APK (~${APK_SIZE_MB} MB) → $DEVICE_NAME"
install_apk_with_retry
echo ""
echo "Starting app once to initialize its local runtime..."
launch_installed_app
sleep 3
configure_model_provider
echo ""
echo "Done. Launching app..."
launch_installed_app
echo "  -> $PACKAGE_NAME"
