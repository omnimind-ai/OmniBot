#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release-version-code.sh"

NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
FLUTTER_DIR="$ROOT_DIR/ui"
ARTIFACT_DIR="$ROOT_DIR/app/build/outputs/release-artifacts"
PINNED_BUNDLETOOL_VERSION="1.18.1"
PINNED_BUNDLETOOL_SHA256="675786493983787ffa11550bdb7c0715679a44e1643f3ff980a529e9c822595c"

INSTALL_APK=0
BUNDLE=0
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
RELEASE_NOTES_FILE=""
RELEASE_NOTES_SHA256=""
WORKER_URL="${APP_UPDATE_WORKER_URL:-}"
RELEASE_TRACK=""
RELEASE_DRAFT=""
RELEASE_PRERELEASE=""
RELEASE_VERSION_CODE="${OMNI_RELEASE_VERSION_CODE:-}"
RELEASE_CERT_SHA256="${OMNI_RELEASE_CERT_SHA256:-}"
REUSE_ARTIFACT=""
ARTIFACT_TYPE=""
ARTIFACT_EXTENSION=""
VERIFIED_ARTIFACT_PATH=""
SOURCE_VERSION_NAME=""
TAG_COMMIT=""

usage() {
  cat <<'EOF'
Usage:
  bash scripts/build-local-release.sh [options]

Options:
  --edition EDITION   `standard` builds the direct-download APK (default).
                      `play` builds the Google Play AAB and requires --bundle.
  --install           Build one release APK and install it with adb.
  --bundle            Build the signed productionPlayRelease AAB. It is never
                      sent to the GitHub/Worker self-update channel.
  --skip-flutter      Skip `flutter pub get` in ui/.
  --skip-build        Reuse exactly the file passed by --reuse-artifact after
                      strict package/version/edition/permission/signature checks.
  --reuse-artifact FILE
                      Required with --skip-build; implicit global APK reuse is forbidden.
  --tag TAG           Release tag/ref used in output file names.
                      Defaults to the exact git tag, or v<app versionName>.
  --ref-name NAME     Alias for --tag.
  --version-code CODE Explicit Android versionCode. It must equal the
                      deterministic code derived from the release tag.
  --release-cert-sha256 SHA256
                      Approved release/upload certificate fingerprint. Required
                      for both newly built and reused APK/AAB artifacts.
  --out-dir DIR       Defaults to app/build/outputs/release-artifacts/manual/<tag>.
  --publish-github    Create one immutable GitHub release and upload APK assets.
  --publish-worker    Worker publication requires --publish-all so metadata is
                      never exposed before the matching GitHub release exists.
  --publish-all       Worker assets -> GitHub release -> Worker metadata.
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
  OMNI_RELEASE_VERSION_CODE Required unless --version-code is provided.
                            For vMAJOR.MINOR.PATCH[.BUILD], it is derived as
                            (((MAJOR*100)+MINOR)*100+PATCH)*10000+BUILD.
  OMNI_RELEASE_CERT_SHA256  Public approved signer fingerprint; may be supplied
                            instead of --release-cert-sha256.
  BUNDLETOOL_JAR            Required for Play AAB final-manifest verification.
                            Must be bundletool-all-1.18.1.jar with the digest
                            pinned in this script; overrides cannot change it.
  OMNI_RELEASE_*            May also be set in ~/.gradle/gradle.properties.
  ANDROID_SDK_ROOT          Auto-detected from local.properties when absent.
  ANDROID_NDK_HOME          Auto-detected as $ANDROID_SDK_ROOT/ndk/28.2.13676358 when absent.
  GRADLE_OPTS              Defaults to the same memory settings used in CI.

Publishing credentials are read from environment variables only, to avoid
leaking tokens through shell history or process listings:
  GH_TOKEN or GITHUB_TOKEN
  APP_UPDATE_WORKER_TOKEN
  APP_UPDATE_WORKER_URL    Optional publishing URL; it must match the clean HTTPS
                           OMNIBOT_UPDATE_WORKER_URL compiled into the app.
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

configure_android_sdk_root() {
  local sdk_dir=""
  local sdk_drive=""
  local sdk_rest=""

  if [[ -z "${ANDROID_SDK_ROOT:-}" && -f "$ROOT_DIR/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n 1)"
    if [[ -n "$sdk_dir" ]]; then
      sdk_dir="${sdk_dir//\\:/:}"
      sdk_dir="${sdk_dir//\\\\/\\}"
      if command -v cygpath >/dev/null 2>&1; then
        sdk_dir="$(cygpath -u "$sdk_dir")"
      elif [[ "$sdk_dir" == [A-Za-z]:\\* ]]; then
        sdk_drive="${sdk_dir:0:1}"
        sdk_rest="${sdk_dir:3}"
        sdk_rest="${sdk_rest//\\//}"
        sdk_dir="/mnt/${sdk_drive,,}/$sdk_rest"
      fi
      export ANDROID_SDK_ROOT="$sdk_dir"
    fi
  fi

  if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
  fi
  if [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "$ANDROID_SDK_ROOT" ]]; then
    echo "Missing ANDROID_SDK_ROOT and could not detect a valid SDK from local.properties" >&2
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

normalize_release_worker_url() {
  local py_bin="$1"
  local raw_url="$2"
  "$py_bin" -c '
import sys
sys.path.insert(0, sys.argv[1])
from upload_release_asset_to_worker import normalize_worker_url
print(normalize_worker_url(sys.argv[2]))
' "$ROOT_DIR/scripts" "$raw_url"
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

prepare_release_notes() {
  local repo="$1"
  local py_bin=""
  local raw_notes_file=""

  require_command gh
  py_bin="$(python_bin)"
  require_command "$py_bin"
  raw_notes_file="$OUT_DIR/.generated-release-notes.txt"
  RELEASE_NOTES_FILE="$OUT_DIR/release-notes.md"

  if gh release view "$REF_NAME" --repo "$repo" >/dev/null 2>&1; then
    echo "GitHub release already exists for $REF_NAME; refusing to overwrite tagged assets." >&2
    exit 1
  fi
  gh api --method POST "repos/${repo}/releases/generate-notes" \
    --raw-field "tag_name=${REF_NAME}" \
    --raw-field "target_commitish=${GITHUB_TARGET}" \
    --jq '.body // ""' > "$raw_notes_file"
  "$py_bin" "$ROOT_DIR/scripts/prepare_release_notes.py" \
    --input "$raw_notes_file" \
    --output "$RELEASE_NOTES_FILE" \
    --tag "$REF_NAME"
  rm -f -- "$raw_notes_file"
  RELEASE_NOTES_SHA256="$(sha256_of "$RELEASE_NOTES_FILE")"
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
    echo "GitHub release already exists for $REF_NAME; refusing to overwrite tagged assets." >&2
    exit 1
  fi

  if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
    echo "Immutable release notes were not prepared." >&2
    exit 1
  fi
  if [[ "$(sha256_of "$RELEASE_NOTES_FILE")" != "$RELEASE_NOTES_SHA256" ]]; then
    echo "Immutable release notes changed before GitHub publication." >&2
    exit 1
  fi
  gh_args=(release create "$REF_NAME" "${files[@]}" --repo "$repo" --target "$target" --notes-file "$RELEASE_NOTES_FILE")
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
  local compiled_worker_url=""
  local publishing_worker_url=""

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
    py_bin="$(python_bin)"
    require_command "$py_bin"

    if [[ -z "$WORKER_URL" ]]; then
      WORKER_URL="${OMNIBOT_UPDATE_WORKER_URL:-}"
    fi
    export APP_UPDATE_WORKER_URL="$WORKER_URL"
    prompt_if_empty APP_UPDATE_WORKER_TOKEN "App update Worker token" 1

    if [[ -z "${APP_UPDATE_WORKER_URL:-}" ]]; then
      echo "Missing APP_UPDATE_WORKER_URL or --worker-url" >&2
      exit 1
    fi

    if [[ -z "${APP_UPDATE_WORKER_TOKEN:-}" ]]; then
      echo "Missing APP_UPDATE_WORKER_TOKEN" >&2
      exit 1
    fi

    compiled_worker_url="$(normalize_release_worker_url "$py_bin" "${OMNIBOT_UPDATE_WORKER_URL:-}")"
    publishing_worker_url="$(normalize_release_worker_url "$py_bin" "$APP_UPDATE_WORKER_URL")"
    if [[ "$publishing_worker_url" != "$compiled_worker_url" ]]; then
      echo "Publishing Worker URL must match the OMNIBOT_UPDATE_WORKER_URL compiled into the app." >&2
      exit 1
    fi
    WORKER_URL="$publishing_worker_url"
    export APP_UPDATE_WORKER_URL="$publishing_worker_url"
  fi
}

upload_worker_assets() {
  local py_bin=""
  local edition=""
  local apk_path=""
  local sha_path=""
  local apk_sha256=""
  local sha_file_sha256=""

  py_bin="$(python_bin)"
  require_command "$py_bin"

  for edition in "${EDITIONS[@]}"; do
    apk_path="$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.apk"
    sha_path="${apk_path}.sha256"
    apk_sha256="$(awk '{print $1}' "$sha_path")"
    sha_file_sha256="$(sha256_of "$sha_path")"

    "$py_bin" "$ROOT_DIR/scripts/upload_release_asset_to_worker.py" \
      --worker-url "$APP_UPDATE_WORKER_URL" \
      --tag "$REF_NAME" \
      --file "$apk_path" \
      --content-type "application/vnd.android.package-archive" \
      --sha256 "$apk_sha256"

    "$py_bin" "$ROOT_DIR/scripts/upload_release_asset_to_worker.py" \
      --worker-url "$APP_UPDATE_WORKER_URL" \
      --tag "$REF_NAME" \
      --file "$sha_path" \
      --content-type "text/plain; charset=utf-8" \
      --sha256 "$sha_file_sha256"
  done
}

publish_worker_metadata() {
  local repo="$1"
  local py_bin=""
  local payload_file=""

  py_bin="$(python_bin)"
  require_command "$py_bin"
  if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
    echo "Immutable release notes were not prepared." >&2
    exit 1
  fi
  if [[ "$(sha256_of "$RELEASE_NOTES_FILE")" != "$RELEASE_NOTES_SHA256" ]]; then
    echo "Immutable release notes changed after GitHub publication." >&2
    exit 1
  fi

  payload_file="$OUT_DIR/worker-release-payload.json"
  LOCAL_RELEASE_REF_NAME="$REF_NAME" \
  LOCAL_RELEASE_SAFE_REF_NAME="$SAFE_REF_NAME" \
  LOCAL_RELEASE_GITHUB_REPO="$repo" \
  LOCAL_RELEASE_ASSET_DIR="$OUT_DIR" \
  LOCAL_RELEASE_EDITIONS="${EDITIONS[*]}" \
  LOCAL_RELEASE_TRACK="$RELEASE_TRACK" \
  LOCAL_RELEASE_DRAFT="$RELEASE_DRAFT" \
  LOCAL_RELEASE_PRERELEASE="$RELEASE_PRERELEASE" \
  LOCAL_RELEASE_NOTES_FILE="$RELEASE_NOTES_FILE" \
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
release_notes = Path(os.environ["LOCAL_RELEASE_NOTES_FILE"]).read_text(encoding="utf-8")
if not release_notes.strip() or len(release_notes.encode("utf-8")) > 256 * 1024:
    raise SystemExit("Immutable release notes are empty or exceed 256 KiB.")

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
    "releaseNotes": release_notes,
    "assets": [apk_asset(edition) for edition in editions],
}
print(json.dumps(payload, ensure_ascii=False))
PY

  echo "Publishing update metadata to Worker..."
  "$py_bin" "$ROOT_DIR/scripts/publish_release_metadata_to_worker.py" \
    --worker-url "$APP_UPDATE_WORKER_URL" \
    --payload-file "$payload_file"
}

task_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' assembleProductionStandardRelease
      ;;
    play)
      printf '%s\n' bundleProductionPlayRelease
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
    play)
      printf '%s\n' lib/main_standard.dart
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

artifact_path_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' "$ROOT_DIR/app/build/outputs/apk/productionStandard/release/app-production-standard-release.apk"
      ;;
    play)
      printf '%s\n' "$ROOT_DIR/app/build/outputs/bundle/productionPlayRelease/app-production-play-release.aab"
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

absolute_existing_file() {
  local file_path="$1"
  local file_dir=""
  local file_name=""
  [[ -f "$file_path" ]] || { echo "Artifact file was not found: $file_path" >&2; return 1; }
  file_dir="$(cd "$(dirname "$file_path")" && pwd)"
  file_name="$(basename "$file_path")"
  printf '%s/%s\n' "$file_dir" "$file_name"
}

verify_release_artifact() {
  local artifact_path="$1"
  local permission_baseline="$ROOT_DIR/scripts/production-${EDITION}-permissions.txt"
  local args=(
    --type "$ARTIFACT_TYPE"
    --file "$artifact_path"
    --package "cn.com.omnimind.bot"
    --version-name "$SOURCE_VERSION_NAME"
    --version-code "$RELEASE_VERSION_CODE"
    --edition "$EDITION"
    --cert-sha256 "$RELEASE_CERT_SHA256"
    --permission-baseline "$permission_baseline"
  )
  if [[ "$EDITION" == "play" ]]; then
    args+=(
      --forbid-permission android.permission.REQUEST_INSTALL_PACKAGES
      --forbid-permission android.permission.QUERY_ALL_PACKAGES
      --forbid-permission android.permission.MANAGE_EXTERNAL_STORAGE
    )
  fi
  bash "$ROOT_DIR/scripts/verify-release-artifact.sh" "${args[@]}"
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
      BUNDLE=1
      ;;
    --skip-flutter)
      SKIP_FLUTTER=1
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --reuse-artifact)
      if [[ $# -lt 2 ]]; then
        echo "--reuse-artifact requires a file path" >&2
        exit 1
      fi
      REUSE_ARTIFACT="$2"
      shift
      ;;
    --reuse-artifact=*)
      REUSE_ARTIFACT="${1#*=}"
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
    --version-code)
      if [[ $# -lt 2 ]]; then
        echo "--version-code requires a value" >&2
        exit 1
      fi
      RELEASE_VERSION_CODE="$2"
      shift
      ;;
    --version-code=*)
      RELEASE_VERSION_CODE="${1#*=}"
      ;;
    --release-cert-sha256)
      if [[ $# -lt 2 ]]; then
        echo "--release-cert-sha256 requires a value" >&2
        exit 1
      fi
      RELEASE_CERT_SHA256="$2"
      shift
      ;;
    --release-cert-sha256=*)
      RELEASE_CERT_SHA256="${1#*=}"
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
    ARTIFACT_TYPE="apk"
    ARTIFACT_EXTENSION="apk"
    ;;
  play)
    EDITIONS=(play)
    ARTIFACT_TYPE="aab"
    ARTIFACT_EXTENSION="aab"
    ;;
  *)
    echo "Invalid edition: $EDITION" >&2
    usage
    exit 1
    ;;
esac

if [[ "$EDITION" == "standard" && "$BUNDLE" -eq 1 ]]; then
  echo "--bundle is reserved for --edition play." >&2
  exit 1
fi
if [[ "$EDITION" == "play" && "$BUNDLE" -ne 1 ]]; then
  echo "--edition play requires --bundle." >&2
  exit 1
fi
if [[ "$EDITION" == "play" && ( "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ) ]]; then
  echo "Play AAB artifacts must not be published to the APK self-update channels." >&2
  exit 1
fi
if [[ "$PUBLISH_WORKER" -eq 1 && "$PUBLISH_GITHUB" -ne 1 ]]; then
  echo "Worker publication requires --publish-all so GitHub and Worker use one immutable release transaction." >&2
  exit 1
fi
if [[ "$EDITION" == "play" && "$INSTALL_APK" -eq 1 ]]; then
  echo "AAB artifacts cannot be installed directly with adb." >&2
  exit 1
fi
if [[ "$SKIP_BUILD" -eq 1 && -z "$REUSE_ARTIFACT" ]]; then
  echo "--skip-build requires an explicit --reuse-artifact file." >&2
  exit 1
fi
if [[ "$SKIP_BUILD" -eq 1 && ( "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ) ]]; then
  echo "--skip-build artifacts are for offline verification only and cannot be published." >&2
  exit 1
fi
if [[ "$SKIP_BUILD" -eq 0 && -n "$REUSE_ARTIFACT" ]]; then
  echo "--reuse-artifact is valid only together with --skip-build." >&2
  exit 1
fi

if [[ -z "$REF_NAME" ]]; then
  REF_NAME="$(default_ref_name)"
fi

SOURCE_VERSION_NAME="$(read_gradle_version_name "$ROOT_DIR/app/build.gradle.kts")"
validate_release_version_name "$REF_NAME" "$SOURCE_VERSION_NAME" >/dev/null
TAG_COMMIT="$(verify_release_source_identity "$REF_NAME")"
python3 "$ROOT_DIR/scripts/verify-agent-runtime-supply-chain.py"

if [[ -z "$RELEASE_VERSION_CODE" ]]; then
  echo "Missing release versionCode. Pass --version-code or set OMNI_RELEASE_VERSION_CODE." >&2
  exit 1
fi

RELEASE_VERSION_CODE="$(verify_release_version_code_is_newest "$REF_NAME" "$RELEASE_VERSION_CODE")"

RELEASE_CERT_SHA256="$(printf '%s' "$RELEASE_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
if [[ ! "$RELEASE_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Missing or invalid approved release certificate SHA-256 fingerprint." >&2
  exit 1
fi

if [[ "$EDITION" == "play" ]]; then
  if [[ -z "${BUNDLETOOL_JAR:-}" || ! -f "$BUNDLETOOL_JAR" ]]; then
    echo "Play AAB release requires BUNDLETOOL_JAR." >&2
    exit 1
  fi
  bundletool_sha256="$(sha256_of "$BUNDLETOOL_JAR")"
  if [[ "$bundletool_sha256" != "$PINNED_BUNDLETOOL_SHA256" ]]; then
    echo "BUNDLETOOL_JAR does not match the pinned bundletool 1.18.1 SHA-256." >&2
    exit 1
  fi
  export BUNDLETOOL_VERSION="$PINNED_BUNDLETOOL_VERSION"
  export BUNDLETOOL_SHA256="$PINNED_BUNDLETOOL_SHA256"
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
load_gradle_property_if_empty OMNIBOT_UPDATE_WORKER_URL
if [[ "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ]]; then
  GITHUB_REPO="$(detect_github_repo)"
fi
if [[ "$PUBLISH_GITHUB" -eq 1 && -z "$GITHUB_TARGET" ]]; then
  GITHUB_TARGET="$TAG_COMMIT"
elif [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
  resolved_github_target="$(git rev-parse "$GITHUB_TARGET^{commit}" 2>/dev/null || true)"
  if [[ "$resolved_github_target" != "$TAG_COMMIT" ]]; then
    echo "--github-target must resolve to the verified release tag commit $TAG_COMMIT." >&2
    exit 1
  fi
  GITHUB_TARGET="$TAG_COMMIT"
fi
preflight_publish_settings
configure_android_sdk_root

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
echo "Release versionName: $SOURCE_VERSION_NAME"
echo "Release versionCode: $RELEASE_VERSION_CODE"
echo "Verified tag commit: $TAG_COMMIT"
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
  source_artifact="$(artifact_path_for_edition "$edition")"
  artifact_copy="$ARTIFACT_DIR/OpenOmniBot-${edition}.${ARTIFACT_EXTENSION}"

  echo "Building signed $edition release ${ARTIFACT_TYPE^^} with $flutter_target..."
  ./gradlew \
    --no-daemon \
    --build-cache \
    --max-workers="$max_workers" \
    "$task" \
    -POMNI_RELEASE_VERSION_CODE="$RELEASE_VERSION_CODE" \
    -Ptarget="$flutter_target"

  if [[ ! -f "$source_artifact" ]]; then
    echo "Build finished but ${ARTIFACT_TYPE^^} was not found: $source_artifact" >&2
    exit 1
  fi

  post_build_commit="$(verify_release_source_identity "$REF_NAME")"
  if [[ "$post_build_commit" != "$TAG_COMMIT" ]]; then
    echo "Release source identity changed during the build." >&2
    exit 1
  fi
  verify_release_artifact "$source_artifact"
  cp "$source_artifact" "$artifact_copy"
  VERIFIED_ARTIFACT_PATH="$artifact_copy"
  echo "Verified ${ARTIFACT_TYPE^^} ready: $VERIFIED_ARTIFACT_PATH"
  write_sha256 "$VERIFIED_ARTIFACT_PATH"

  if [[ "$INSTALL_APK" -eq 1 ]]; then
    echo "Installing $edition APK via adb..."
    adb install -r "$VERIFIED_ARTIFACT_PATH"
  fi
done
else
  REUSE_ARTIFACT="$(absolute_existing_file "$REUSE_ARTIFACT")"
  case "${REUSE_ARTIFACT,,}" in
    *."$ARTIFACT_EXTENSION") ;;
    *)
      echo "Reused artifact must have .$ARTIFACT_EXTENSION extension for $EDITION." >&2
      exit 1
      ;;
  esac
  verify_release_artifact "$REUSE_ARTIFACT"
  VERIFIED_ARTIFACT_PATH="$REUSE_ARTIFACT"
  echo "Release ref: $REF_NAME"
  echo "Release versionName: $SOURCE_VERSION_NAME"
  echo "Release versionCode: $RELEASE_VERSION_CODE"
  echo "Verified tag commit: $TAG_COMMIT"
  echo "Release track: $RELEASE_TRACK"
  echo "Staging dir: $OUT_DIR"
  echo "Skipping build; reusing one explicitly verified $ARTIFACT_TYPE artifact."
fi

mkdir -p "$OUT_DIR"

if [[ "$EDITION" == "standard" ]]; then
  MANIFEST_PATH="$OUT_DIR/manifest.txt"
else
  MANIFEST_PATH="$OUT_DIR/play-manifest.txt"
fi
{
  printf 'ref_name=%s\n' "$REF_NAME"
  printf 'safe_ref_name=%s\n' "$SAFE_REF_NAME"
  printf 'version_name=%s\n' "$SOURCE_VERSION_NAME"
  printf 'version_code=%s\n' "$RELEASE_VERSION_CODE"
  printf 'edition=%s\n' "$EDITION"
  printf 'artifact_type=%s\n' "$ARTIFACT_TYPE"
  printf 'update_worker_url=%s\n' "${OMNIBOT_UPDATE_WORKER_URL:-}"
  printf 'release_cert_sha256=%s\n' "$RELEASE_CERT_SHA256"
  printf 'commit=%s\n' "$TAG_COMMIT"
  printf 'built_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$MANIFEST_PATH"

for edition in "${EDITIONS[@]}"; do
  target_artifact="$OUT_DIR/OpenOmniBot-${SAFE_REF_NAME}-${edition}.${ARTIFACT_EXTENSION}"
  if [[ "$VERIFIED_ARTIFACT_PATH" != "$target_artifact" ]]; then
    cp "$VERIFIED_ARTIFACT_PATH" "$target_artifact"
  fi

  write_sha256 "$target_artifact" >/dev/null
  sha256="$(awk '{print $1}' "${target_artifact}.sha256")"
  size_bytes="$(wc -c < "$target_artifact" | tr -d ' ')"
  printf 'asset=%s sha256=%s size=%s\n' "$(basename "$target_artifact")" "$sha256" "$size_bytes" >> "$MANIFEST_PATH"
done

printf '\nManual upload artifacts are ready in:\n  %s\n\n' "$OUT_DIR"
find "$OUT_DIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' -o -name '*.sha256' -o -name '*manifest.txt' \) -print | sort | sed 's#^#  #'

if [[ "$PUBLISH_GITHUB" -eq 1 || "$PUBLISH_WORKER" -eq 1 ]]; then
  pre_publish_commit="$(verify_release_source_identity "$REF_NAME")"
  if [[ "$pre_publish_commit" != "$TAG_COMMIT" ]]; then
    echo "Release source identity changed before publishing." >&2
    exit 1
  fi
fi

if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
  prepare_release_notes "$GITHUB_REPO"
fi

if [[ "$PUBLISH_WORKER" -eq 1 ]]; then
  upload_worker_assets
fi

if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
  publish_github_release "$GITHUB_REPO" "$GITHUB_TARGET"
fi

if [[ "$PUBLISH_WORKER" -eq 1 ]]; then
  publish_worker_metadata "$GITHUB_REPO"
fi
