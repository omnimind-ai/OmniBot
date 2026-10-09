#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
FLUTTER_DIR="$ROOT_DIR/ui"
ARTIFACT_DIR="$ROOT_DIR/app/build/outputs/release-artifacts"
DEFAULT_WORKER_URL="https://omnibot.omnimind.com.cn"

INSTALL_APK=0
SKIP_FLUTTER=0
SKIP_BUILD=0
NON_INTERACTIVE=0
EDITION="standard"
REF_NAME=""
SAFE_REF_NAME=""
OUT_DIR=""
PUBLISH_GITHUB=0
PUBLISH_WORKER=0
GITHUB_REPO="${GITHUB_REPOSITORY:-}"
GITHUB_TARGET=""
WORKER_URL="${OMNIBOT_BACKEND_URL:-${APP_UPDATE_WORKER_URL:-$DEFAULT_WORKER_URL}}"
RELEASE_TRACK=""
RELEASE_DRAFT=""
RELEASE_PRERELEASE=""

usage() {
  cat <<'EOF'
Usage:
  bash scripts/build-local-release.sh [options]

Options:
  --edition standard  Build the Android release APK. Defaults to standard.
  --install           Build one release APK and install it with adb.
  --bundle            Unsupported; APK only for now.
  --skip-flutter      Skip `flutter pub get` in ui/.
  --skip-build        Reuse existing staged APK files and only package/publish.
  --tag TAG           Release tag/ref used in output file names.
                      Defaults to the exact git tag, or v<app versionName>.
  --ref-name NAME     Alias for --tag.
  --out-dir DIR       Defaults to app/build/outputs/release-artifacts/manual/<tag>.
  --publish-github    Create/update a GitHub release and upload APK assets.
  --publish-worker    Upload APK assets and release metadata to the update Worker.
  --publish-all       Run both publishing steps.
  --github-repo OWNER/REPO
                      Defaults to GITHUB_REPOSITORY or the origin GitHub remote.
  --github-target COMMIT
                      Target commitish when creating a new GitHub release.
                      Defaults to HEAD.
  --worker-url URL    Override the built-in app update Worker URL.
  --non-interactive   Do not prompt for missing signing values.
  --help              Show this help text.

Required signing values (environment, ~/.gradle/gradle.properties, or prompt):
  OMNI_RELEASE_STORE_PWD
  OMNI_RELEASE_KEY_ALIAS

Optional environment variables:
  OMNI_RELEASE_STORE_FILE   Defaults to ./release.jks when present.
  OMNI_RELEASE_KEY_PWD      Defaults to OMNI_RELEASE_STORE_PWD.
  OMNI_RELEASE_*            May also be set in ~/.gradle/gradle.properties.
  ANDROID_SDK_ROOT          Auto-detected from local.properties when absent.
  ANDROID_NDK_HOME          Auto-detected as $ANDROID_SDK_ROOT/ndk/28.2.13676358 when absent.
  GRADLE_OPTS              Defaults to the same memory settings used in CI.

Publishing credentials are read from environment variables only, to avoid
leaking tokens through shell history or process listings:
  GH_TOKEN or GITHUB_TOKEN
  APP_UPDATE_WORKER_TOKEN
  OMNIBOT_BACKEND_URL      Optional override for the website backend URL.
  APP_UPDATE_WORKER_URL    Legacy override; --worker-url is also supported.
EOF
}

read_gradle_property() {
  local property_name="$1"
  local property_file="$2"

  [[ -f "$property_file" ]] || return 1

  awk -v key="$property_name" '
    /^[[:space:]]*(#|$)/ { next }
    {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      if (index(line, key "=") == 1) {
        sub(/^[^=]*=/, "", line)
        value = line
      }
    }
    END {
      if (value != "") {
        print value
        exit 0
      }
      exit 1
    }
  ' "$property_file"
}

load_gradle_property_if_empty() {
  local property_name="$1"
  local property_value=""
  local property_file=""

  if [[ -n "${!property_name:-}" ]]; then
    return
  fi

  for property_file in "${HOME:-}/.gradle/gradle.properties" "$ROOT_DIR/gradle.properties"; do
    property_value="$(read_gradle_property "$property_name" "$property_file" 2>/dev/null || true)"
    if [[ -n "$property_value" ]]; then
      export "$property_name=$property_value"
      return
    fi
  done
}

prompt_if_empty() {
  local property_name="$1"
  local prompt_text="$2"
  local silent="${3:-0}"
  local property_value=""

  if [[ -n "${!property_name:-}" || "$NON_INTERACTIVE" -eq 1 || ! -t 0 ]]; then
    return
  fi

  if [[ "$silent" -eq 1 ]]; then
    read -r -s -p "$prompt_text: " property_value
    printf '\n'
  else
    read -r -p "$prompt_text: " property_value
  fi

  if [[ -n "$property_value" ]]; then
    export "$property_name=$property_value"
  fi
}

write_sha256() {
  local file_path="$1"
  local checksum_path="${file_path}.sha256"
  local dir_name
  local base_name
  dir_name="$(dirname "$file_path")"
  base_name="$(basename "$file_path")"

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dir_name" && sha256sum "$base_name") > "$checksum_path"
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$dir_name" && shasum -a 256 "$base_name") > "$checksum_path"
  elif command -v openssl >/dev/null 2>&1; then
    local digest
    digest="$(openssl dgst -sha256 -r "$file_path" | awk '{print $1}')"
    printf '%s  %s\n' "$digest" "$base_name" > "$checksum_path"
  else
    echo "Missing checksum tool. Install sha256sum, shasum, or openssl." >&2
    exit 1
  fi

  cat "$checksum_path"
}

sha256_of() {
  local file_path="$1"

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file_path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file_path" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$file_path" | awk '{print $1}'
  else
    echo "Missing checksum tool. Install sha256sum, shasum, or openssl." >&2
    exit 1
  fi
}

safe_ref_name() {
  printf '%s' "$1" | sed 's#[^A-Za-z0-9._-]#-#g'
}

default_ref_name() {
  local exact_tag=""
  local version_name=""

  exact_tag="$(git describe --tags --exact-match 2>/dev/null || true)"
  if [[ -n "$exact_tag" ]]; then
    printf '%s\n' "$exact_tag"
    return
  fi

  version_name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
  if [[ -n "$version_name" ]]; then
    printf 'v%s\n' "$version_name"
    return
  fi

  date -u '+local-%Y%m%d-%H%M%S'
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

python_bin() {
  if command -v python3.11 >/dev/null 2>&1; then
    printf '%s\n' python3.11
  else
    printf '%s\n' python3
  fi
}

detect_github_repo() {
  local remote_url=""

  if [[ -n "$GITHUB_REPO" ]]; then
    printf '%s\n' "$GITHUB_REPO"
    return
  fi

  remote_url="$(git remote get-url origin 2>/dev/null || true)"
  case "$remote_url" in
    git@github.com:*.git)
      remote_url="${remote_url#git@github.com:}"
      remote_url="${remote_url%.git}"
      ;;
    https://github.com/*.git)
      remote_url="${remote_url#https://github.com/}"
      remote_url="${remote_url%.git}"
      ;;
    https://github.com/*)
      remote_url="${remote_url#https://github.com/}"
      ;;
    *)
      remote_url=""
      ;;
  esac

  if [[ -z "$remote_url" ]]; then
    echo "Unable to detect GitHub repo. Pass --github-repo OWNER/REPO." >&2
    exit 1
  fi

  printf '%s\n' "$remote_url"
}

determine_release_mode() {
  local normalized_ref="${REF_NAME#v}"

  if [[ "$normalized_ref" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    RELEASE_TRACK="beta"
    RELEASE_DRAFT="false"
    RELEASE_PRERELEASE="true"
  elif [[ "$normalized_ref" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    RELEASE_TRACK="stable"
    RELEASE_DRAFT="false"
    RELEASE_PRERELEASE="false"
  else
    RELEASE_TRACK="custom"
    RELEASE_DRAFT="true"
    RELEASE_PRERELEASE="false"
  fi
}

staged_release_files() {
  local edition=""

  for edition in "${EDITIONS[@]}"; do
    printf '%s\n' "$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.apk"
    printf '%s\n' "$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.apk.sha256"
  done
}

publish_github_release() {
  local repo="$1"
  local target="$2"
  local files=()
  local gh_args=()
  local file_path=""

  require_command gh

  if [[ -n "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
    export GH_TOKEN="$GITHUB_TOKEN"
  fi

  if [[ -z "${GH_TOKEN:-}" ]]; then
    gh auth status --hostname github.com >/dev/null
  fi

  while IFS= read -r file_path; do
    files+=("$file_path")
  done < <(staged_release_files)

  if gh release view "$REF_NAME" --repo "$repo" >/dev/null 2>&1; then
    echo "GitHub release exists; uploading assets with --clobber..."
    gh release upload "$REF_NAME" "${files[@]}" --repo "$repo" --clobber
    return
  fi

  gh_args=(release create "$REF_NAME" "${files[@]}" --repo "$repo" --target "$target" --generate-notes)
  if [[ "$RELEASE_DRAFT" == "true" ]]; then
    gh_args+=(--draft)
  fi
  if [[ "$RELEASE_PRERELEASE" == "true" ]]; then
    gh_args+=(--prerelease)
  fi

  echo "Creating GitHub release $REF_NAME in $repo..."
  gh "${gh_args[@]}"
}

preflight_publish_settings() {
  local py_bin=""

  if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
    require_command gh
    if [[ -n "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
      export GH_TOKEN="$GITHUB_TOKEN"
    fi
    if ! gh auth status --hostname github.com >/dev/null 2>&1; then
      echo "GitHub publishing requires gh login or GH_TOKEN/GITHUB_TOKEN." >&2
      exit 1
    fi
  fi

  if [[ "$PUBLISH_WORKER" -eq 1 ]]; then
    require_command curl
    py_bin="$(python_bin)"
    require_command "$py_bin"

    if [[ -n "$WORKER_URL" ]]; then
      export APP_UPDATE_WORKER_URL="$WORKER_URL"
    fi

    prompt_if_empty APP_UPDATE_WORKER_URL "App update Worker URL" 0
    prompt_if_empty APP_UPDATE_WORKER_TOKEN "App update Worker token" 1

    if [[ -z "${APP_UPDATE_WORKER_URL:-}" ]]; then
      echo "Missing APP_UPDATE_WORKER_URL or --worker-url" >&2
      exit 1
    fi

    if [[ -z "${APP_UPDATE_WORKER_TOKEN:-}" ]]; then
      echo "Missing APP_UPDATE_WORKER_TOKEN" >&2
      exit 1
    fi
  fi
}

publish_worker_release() {
  local repo="$1"
  local py_bin=""
  local edition=""
  local apk_path=""
  local sha_path=""
  local apk_sha256=""
  local sha_file_sha256=""
  local payload_file=""
  local api_url=""

  require_command curl
  py_bin="$(python_bin)"
  require_command "$py_bin"

  if [[ -n "$WORKER_URL" ]]; then
    export APP_UPDATE_WORKER_URL="$WORKER_URL"
  fi

  prompt_if_empty APP_UPDATE_WORKER_URL "App update Worker URL" 0
  prompt_if_empty APP_UPDATE_WORKER_TOKEN "App update Worker token" 1

  if [[ -z "${APP_UPDATE_WORKER_URL:-}" ]]; then
    echo "Missing APP_UPDATE_WORKER_URL or --worker-url" >&2
    exit 1
  fi

  if [[ -z "${APP_UPDATE_WORKER_TOKEN:-}" ]]; then
    echo "Missing APP_UPDATE_WORKER_TOKEN" >&2
    exit 1
  fi

  for edition in "${EDITIONS[@]}"; do
    apk_path="$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.apk"
    sha_path="${apk_path}.sha256"
    apk_sha256="$(awk '{print $1}' "$sha_path")"
    sha_file_sha256="$(sha256_of "$sha_path")"

    "$py_bin" "$ROOT_DIR/scripts/upload_release_asset_to_worker.py" \
      --worker-url "$APP_UPDATE_WORKER_URL" \
      --token "$APP_UPDATE_WORKER_TOKEN" \
      --tag "$REF_NAME" \
      --file "$apk_path" \
      --content-type "application/vnd.android.package-archive" \
      --sha256 "$apk_sha256"

    "$py_bin" "$ROOT_DIR/scripts/upload_release_asset_to_worker.py" \
      --worker-url "$APP_UPDATE_WORKER_URL" \
      --token "$APP_UPDATE_WORKER_TOKEN" \
      --tag "$REF_NAME" \
      --file "$sha_path" \
      --content-type "text/plain; charset=utf-8" \
      --sha256 "$sha_file_sha256"
  done

  payload_file="$OUT_DIR/worker-release-payload.json"
  LOCAL_RELEASE_REF_NAME="$REF_NAME" \
  LOCAL_RELEASE_SAFE_REF_NAME="$SAFE_REF_NAME" \
  LOCAL_RELEASE_GITHUB_REPO="$repo" \
  LOCAL_RELEASE_ASSET_DIR="$OUT_DIR" \
  LOCAL_RELEASE_EDITIONS="${EDITIONS[*]}" \
  LOCAL_RELEASE_TRACK="$RELEASE_TRACK" \
  LOCAL_RELEASE_DRAFT="$RELEASE_DRAFT" \
  LOCAL_RELEASE_PRERELEASE="$RELEASE_PRERELEASE" \
  LOCAL_RELEASE_COMMIT="$(git rev-parse HEAD 2>/dev/null || printf unknown)" \
    "$py_bin" - <<'PY' > "$payload_file"
import json
import os
import time
from pathlib import Path

tag = os.environ["LOCAL_RELEASE_REF_NAME"]
safe_ref = os.environ["LOCAL_RELEASE_SAFE_REF_NAME"]
github_repo = os.environ["LOCAL_RELEASE_GITHUB_REPO"]
asset_dir = Path(os.environ["LOCAL_RELEASE_ASSET_DIR"])
editions = os.environ["LOCAL_RELEASE_EDITIONS"].split()

def env_bool(name: str) -> bool:
    return os.environ.get(name, "").lower() == "true"

def apk_asset(edition: str) -> dict:
    name = f"OpenOmniBot-{safe_ref}-{edition}.apk"
    apk_path = asset_dir / name
    sha256 = (asset_dir / f"{name}.sha256").read_text(encoding="utf-8").split()[0]
    return {
        "name": name,
        "githubDownloadUrl": f"https://github.com/{github_repo}/releases/download/{tag}/{name}",
        "sha256": sha256,
        "size": apk_path.stat().st_size,
    }

payload = {
    "tag": tag,
    "track": os.environ["LOCAL_RELEASE_TRACK"],
    "draft": env_bool("LOCAL_RELEASE_DRAFT"),
    "prerelease": env_bool("LOCAL_RELEASE_PRERELEASE"),
    "publishedAt": int(time.time() * 1000),
    "releaseUrl": f"https://github.com/{github_repo}/releases/tag/{tag}",
    "releaseNotes": f"Published from commit {os.environ['LOCAL_RELEASE_COMMIT']}.",
    "assets": [apk_asset(edition) for edition in editions],
}
print(json.dumps(payload, ensure_ascii=False))
PY

  api_url="${APP_UPDATE_WORKER_URL%/}"
  api_url="${api_url%/updates}"
  api_url="${api_url%/admin/releases}/admin/releases"

  echo "Publishing update metadata to Worker..."
  curl --fail --show-error --silent --location \
    --request POST "$api_url" \
    --header "Authorization: Bearer ${APP_UPDATE_WORKER_TOKEN}" \
    --header "Content-Type: application/json" \
    --data-binary @"$payload_file"
  printf '\n'
}

task_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' assembleProductionStandardRelease
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

flutter_target_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' lib/main_standard.dart
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

apk_path_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' "$ROOT_DIR/app/build/outputs/apk/productionStandard/release/app-production-standard-release.apk"
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition)
      if [[ $# -lt 2 ]]; then
        echo "--edition requires the value: standard" >&2
        exit 1
      fi
      EDITION="$2"
      shift
      ;;
    --edition=*)
      EDITION="${1#--edition=}"
      ;;
    --install)
      INSTALL_APK=1
      ;;
    --bundle)
      echo "AAB builds are unsupported; build the APK instead." >&2
      exit 1
      ;;
    --skip-flutter)
      SKIP_FLUTTER=1
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --tag|--ref-name)
      if [[ $# -lt 2 ]]; then
        echo "$1 requires a value" >&2
        exit 1
      fi
      REF_NAME="$2"
      shift
      ;;
    --tag=*|--ref-name=*)
      REF_NAME="${1#*=}"
      ;;
    --out-dir)
      if [[ $# -lt 2 ]]; then
        echo "--out-dir requires a value" >&2
        exit 1
      fi
      OUT_DIR="$2"
      shift
      ;;
    --out-dir=*)
      OUT_DIR="${1#--out-dir=}"
      ;;
    --publish-github)
      PUBLISH_GITHUB=1
      ;;
    --publish-worker)
      PUBLISH_WORKER=1
      ;;
    --publish-all)
      PUBLISH_GITHUB=1
      PUBLISH_WORKER=1
      ;;
    --github-repo)
      if [[ $# -lt 2 ]]; then
        echo "--github-repo requires a value" >&2
        exit 1
      fi
      GITHUB_REPO="$2"
      shift
      ;;
    --github-repo=*)
      GITHUB_REPO="${1#--github-repo=}"
      ;;
    --github-target)
      if [[ $# -lt 2 ]]; then
        echo "--github-target requires a value" >&2
        exit 1
      fi
      GITHUB_TARGET="$2"
      shift
      ;;
    --github-target=*)
      GITHUB_TARGET="${1#--github-target=}"
      ;;
    --worker-url)
      if [[ $# -lt 2 ]]; then
        echo "--worker-url requires a value" >&2
        exit 1
      fi
      WORKER_URL="$2"
      shift
      ;;
    --worker-url=*)
      WORKER_URL="${1#--worker-url=}"
      ;;
    --non-interactive)
      NON_INTERACTIVE=1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

case "$EDITION" in
  standard)
    EDITIONS=(standard)
    ;;
  *)
    echo "Invalid edition: $EDITION" >&2
    usage
    exit 1
    ;;
esac

if [[ -z "$REF_NAME" ]]; then
  REF_NAME="$(default_ref_name)"
fi

SAFE_REF_NAME="$(safe_ref_name "$REF_NAME")"
if [[ -z "$SAFE_REF_NAME" ]]; then
  echo "Unable to derive a non-empty safe ref name from: $REF_NAME" >&2
  exit 1
fi

if [[ "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ]]; then
  if [[ "$REF_NAME" =~ [[:space:]] ]]; then
    echo "Release tag/ref must not contain whitespace when publishing: $REF_NAME" >&2
    exit 1
  fi
fi

if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$ARTIFACT_DIR/manual/$SAFE_REF_NAME"
elif [[ "$OUT_DIR" != /* ]]; then
  OUT_DIR="$ROOT_DIR/$OUT_DIR"
fi

determine_release_mode
if [[ "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ]]; then
  GITHUB_REPO="$(detect_github_repo)"
fi
if [[ "$PUBLISH_GITHUB" -eq 1 && -z "$GITHUB_TARGET" ]]; then
  GITHUB_TARGET="$(git rev-parse HEAD)"
fi
preflight_publish_settings

if [[ "$SKIP_BUILD" -eq 0 ]]; then
load_gradle_property_if_empty OMNI_RELEASE_STORE_FILE
load_gradle_property_if_empty OMNI_RELEASE_STORE_PWD
load_gradle_property_if_empty OMNI_RELEASE_KEY_ALIAS
load_gradle_property_if_empty OMNI_RELEASE_KEY_PWD

if [[ -z "${OMNI_RELEASE_STORE_FILE:-}" && -f "$ROOT_DIR/release.jks" ]]; then
  export OMNI_RELEASE_STORE_FILE="$ROOT_DIR/release.jks"
fi

if [[ -n "${OMNI_RELEASE_STORE_FILE:-}" ]]; then
  case "$OMNI_RELEASE_STORE_FILE" in
    "~/"*) export OMNI_RELEASE_STORE_FILE="${HOME}/${OMNI_RELEASE_STORE_FILE#~/}" ;;
    /*) ;;
    *) export OMNI_RELEASE_STORE_FILE="$ROOT_DIR/$OMNI_RELEASE_STORE_FILE" ;;
  esac
fi

prompt_if_empty OMNI_RELEASE_STORE_PWD "Release keystore password" 1
prompt_if_empty OMNI_RELEASE_KEY_ALIAS "Release key alias" 0

if [[ -z "${OMNI_RELEASE_STORE_PWD:-}" ]]; then
  echo "Missing OMNI_RELEASE_STORE_PWD" >&2
  echo "Set it in the environment, ~/.gradle/gradle.properties, or rerun without --non-interactive to enter it." >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_KEY_ALIAS:-}" ]]; then
  echo "Missing OMNI_RELEASE_KEY_ALIAS" >&2
  echo "Set it in the environment, ~/.gradle/gradle.properties, or rerun without --non-interactive to enter it." >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_STORE_FILE:-}" ]]; then
  echo "Missing OMNI_RELEASE_STORE_FILE and default ./release.jks was not found" >&2
  exit 1
fi

if [[ ! -f "$OMNI_RELEASE_STORE_FILE" ]]; then
  echo "Keystore not found: $OMNI_RELEASE_STORE_FILE" >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_KEY_PWD:-}" ]]; then
  export OMNI_RELEASE_KEY_PWD="$OMNI_RELEASE_STORE_PWD"
fi

export ORG_GRADLE_PROJECT_OMNI_RELEASE_STORE_FILE="$OMNI_RELEASE_STORE_FILE"
export ORG_GRADLE_PROJECT_OMNI_RELEASE_STORE_PWD="$OMNI_RELEASE_STORE_PWD"
export ORG_GRADLE_PROJECT_OMNI_RELEASE_KEY_ALIAS="$OMNI_RELEASE_KEY_ALIAS"
export ORG_GRADLE_PROJECT_OMNI_RELEASE_KEY_PWD="$OMNI_RELEASE_KEY_PWD"

if [[ -z "${ANDROID_SDK_ROOT:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n 1)"
  if [[ -n "$sdk_dir" ]]; then
    sdk_dir="${sdk_dir//\\:/:}"
    sdk_dir="${sdk_dir//\\\\/\\}"
    export ANDROID_SDK_ROOT="$sdk_dir"
  fi
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Missing ANDROID_SDK_ROOT and could not detect it from local.properties" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
fi

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  cat >&2 <<EOF
Android NDK not found: $ANDROID_NDK_HOME
Install the CI-matching NDK with:
  sdkmanager "ndk;$NDK_VERSION"
EOF
  exit 1
fi

if [[ -z "${GRADLE_OPTS:-}" ]]; then
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"
fi

cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
if [[ "$cpu_count" -ge 4 ]]; then
  max_workers=4
elif [[ "$cpu_count" -ge 2 ]]; then
  max_workers="$cpu_count"
else
  max_workers=2
fi

echo "Repo root: $ROOT_DIR"
echo "Edition(s): ${EDITIONS[*]}"
echo "Release ref: $REF_NAME"
echo "Release track: $RELEASE_TRACK"
echo "Staging dir: $OUT_DIR"
echo "Keystore: $OMNI_RELEASE_STORE_FILE"
echo "Android SDK: $ANDROID_SDK_ROOT"
echo "Android NDK: $ANDROID_NDK_HOME"
echo "Gradle max workers: $max_workers"

chmod +x ./gradlew
mkdir -p "$ARTIFACT_DIR"

if [[ "$SKIP_FLUTTER" -eq 0 ]]; then
  echo "Installing Flutter dependencies..."
  (cd "$FLUTTER_DIR" && flutter pub get --enforce-lockfile)
fi

for edition in "${EDITIONS[@]}"; do
  task="$(task_for_edition "$edition")"
  flutter_target="$(flutter_target_for_edition "$edition")"
  source_apk="$(apk_path_for_edition "$edition")"
  artifact_apk="$ARTIFACT_DIR/OpenOmniBot-${edition}.apk"

  echo "Building $edition release APK with $flutter_target..."
  ./gradlew \
    --no-daemon \
    --build-cache \
    --max-workers="$max_workers" \
    "$task" \
    -Ptarget="$flutter_target"

  if [[ ! -f "$source_apk" ]]; then
    echo "Build finished but APK was not found: $source_apk" >&2
    exit 1
  fi

  cp "$source_apk" "$artifact_apk"
  echo "APK ready: $artifact_apk"
  write_sha256 "$artifact_apk"

  if [[ "$INSTALL_APK" -eq 1 ]]; then
    echo "Installing $edition APK via adb..."
    adb install -r "$artifact_apk"
  fi
done
else
  echo "Release ref: $REF_NAME"
  echo "Release track: $RELEASE_TRACK"
  echo "Staging dir: $OUT_DIR"
  echo "Skipping APK build; reusing existing staged artifacts when present."
fi

mkdir -p "$OUT_DIR"

MANIFEST_PATH="$OUT_DIR/manifest.txt"
{
  printf 'ref_name=%s\n' "$REF_NAME"
  printf 'safe_ref_name=%s\n' "$SAFE_REF_NAME"
  printf 'commit=%s\n' "$(git rev-parse HEAD 2>/dev/null || printf unknown)"
  printf 'built_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$MANIFEST_PATH"

for edition in "${EDITIONS[@]}"; do
  source_apk="$ARTIFACT_DIR/OpenOmniBot-${edition}.apk"
  target_apk="$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.apk"

  if [[ -f "$source_apk" ]]; then
    cp "$source_apk" "$target_apk"
  elif [[ ! -f "$target_apk" ]]; then
    echo "Expected APK was not found: $source_apk or $target_apk" >&2
    exit 1
  fi

  write_sha256 "$target_apk" >/dev/null
  sha256="$(awk '{print $1}' "${target_apk}.sha256")"
  size_bytes="$(wc -c < "$target_apk" | tr -d ' ')"
  printf 'asset=%s sha256=%s size=%s\n' "$(basename "$target_apk")" "$sha256" "$size_bytes" >> "$MANIFEST_PATH"
done

printf '\nManual upload artifacts are ready in:\n  %s\n\n' "$OUT_DIR"
find "$OUT_DIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.sha256' -o -name 'manifest.txt' \) -print | sort | sed 's#^#  #'

if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
  publish_github_release "$GITHUB_REPO" "$GITHUB_TARGET"
fi

if [[ "$PUBLISH_WORKER" -eq 1 ]]; then
  publish_worker_release "$GITHUB_REPO"
fi
