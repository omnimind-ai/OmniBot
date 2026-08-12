#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

ARTIFACT_TYPE=""
ARTIFACT_FILE=""
EXPECTED_PACKAGE=""
EXPECTED_VERSION_NAME=""
EXPECTED_VERSION_CODE=""
EXPECTED_EDITION=""
EXPECTED_CERT_SHA256=""
PERMISSION_BASELINE=""
FORBIDDEN_PERMISSIONS=()

fail() {
  echo "$1" >&2
  exit 1
}

normalize_sha256() {
  local normalized=""
  normalized="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
  [[ "$normalized" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$normalized"
}

sha256_file() {
  local file_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file_path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file_path" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$file_path" | awk '{print $1}'
  else
    fail "A SHA-256 tool is required to verify bundletool."
  fi
}

resolve_python() {
  if [[ -n "${PYTHON_BIN:-}" ]]; then
    [[ -x "$PYTHON_BIN" || -f "$PYTHON_BIN" ]] || fail "Configured PYTHON_BIN was not found."
    printf '%s\n' "$PYTHON_BIN"
  elif command -v python3.11 >/dev/null 2>&1; then
    command -v python3.11
  elif command -v python3 >/dev/null 2>&1; then
    command -v python3
  else
    fail "Python 3 is required to verify the final Android manifest."
  fi
}

resolve_sdk_tool() {
  local env_name="$1"
  local command_name="$2"
  local relative_glob="$3"
  local configured="${!env_name:-}"
  local found=""

  if [[ -n "$configured" ]]; then
    [[ -x "$configured" || -f "$configured" ]] || fail "Configured $env_name was not found."
    printf '%s\n' "$configured"
    return
  fi
  if command -v "$command_name" >/dev/null 2>&1; then
    command -v "$command_name"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    found="$(find "$ANDROID_SDK_ROOT" -path "$ANDROID_SDK_ROOT/$relative_glob" -type f 2>/dev/null | sort -V | tail -n 1)"
  fi
  [[ -n "$found" ]] || fail "Required Android tool was not found: $command_name"
  printf '%s\n' "$found"
}

resolve_command() {
  local env_name="$1"
  local command_name="$2"
  local configured="${!env_name:-}"
  if [[ -n "$configured" ]]; then
    [[ -x "$configured" || -f "$configured" ]] || fail "Configured $env_name was not found."
    printf '%s\n' "$configured"
  elif command -v "$command_name" >/dev/null 2>&1; then
    command -v "$command_name"
  else
    fail "Required command was not found: $command_name"
  fi
}

trim_line() {
  tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tail -n 1
}

verify_manifest() {
  local manifest_file="$1"
  local python=""
  local args=()
  python="$(resolve_python)"
  args=(
    "$ROOT_DIR/scripts/verify_android_manifest.py"
    --manifest "$manifest_file"
    --package "$EXPECTED_PACKAGE"
    --version-name "$EXPECTED_VERSION_NAME"
    --version-code "$EXPECTED_VERSION_CODE"
    --edition "$EXPECTED_EDITION"
    --permission-baseline "$PERMISSION_BASELINE"
  )
  for permission in "${FORBIDDEN_PERMISSIONS[@]}"; do
    args+=(--forbid-permission "$permission")
  done
  "$python" "${args[@]}"
}

verify_apk() {
  local analyzer=""
  local signer=""
  local manifest_file="$1"
  local actual_package=""
  local actual_version_name=""
  local actual_version_code=""
  local signer_output=""
  local signer_digests=""
  local signer_count=""
  local signer_digest=""

  analyzer="$(resolve_sdk_tool APK_ANALYZER_BIN apkanalyzer 'cmdline-tools/*/bin/apkanalyzer*')"
  signer="$(resolve_sdk_tool APK_SIGNER_BIN apksigner 'build-tools/*/apksigner*')"

  actual_package="$("$analyzer" manifest application-id "$ARTIFACT_FILE" | trim_line)"
  actual_version_name="$("$analyzer" manifest version-name "$ARTIFACT_FILE" | trim_line)"
  actual_version_code="$("$analyzer" manifest version-code "$ARTIFACT_FILE" | trim_line)"
  [[ "$actual_package" == "$EXPECTED_PACKAGE" ]] || fail "APK package does not match the release package."
  [[ "$actual_version_name" == "$EXPECTED_VERSION_NAME" ]] || fail "APK versionName does not match the release tag."
  [[ "$actual_version_code" == "$EXPECTED_VERSION_CODE" ]] || fail "APK versionCode does not match the release versionCode."
  "$analyzer" manifest print "$ARTIFACT_FILE" > "$manifest_file"
  verify_manifest "$manifest_file"

  if ! signer_output="$("$signer" verify --verbose --print-certs "$ARTIFACT_FILE" 2>&1)"; then
    fail "APK signature verification failed."
  fi
  signer_output="${signer_output//$'\r'/}"
  printf '%s\n' "$signer_output" | grep -Eq '^Verified using v2 scheme .*:[[:space:]]*true$' ||
    fail "APK Signature Scheme v2 verification is required."
  printf '%s\n' "$signer_output" | grep -Eq '^Verified using v3 scheme .*:[[:space:]]*true$' ||
    fail "APK Signature Scheme v3 verification is required."
  signer_digests="$(printf '%s\n' "$signer_output" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest:[[:space:]]*//p')"
  signer_count="$(printf '%s\n' "$signer_digests" | awk 'NF { count += 1 } END { print count + 0 }')"
  [[ "$signer_count" == "1" ]] || fail "APK must have exactly one verified signer."
  signer_digest="$(normalize_sha256 "$signer_digests" || true)"
  [[ -n "$signer_digest" && "$signer_digest" == "$EXPECTED_CERT_SHA256" ]] ||
    fail "APK signer certificate does not match the approved release certificate."
}

verify_aab() {
  local java=""
  local jarsigner=""
  local keytool=""
  local manifest_file="$1"
  local cert_output=""
  local cert_digests=""
  local cert_count=""
  local cert_digest=""
  local expected_bundletool_sha=""
  local actual_bundletool_sha=""
  local actual_bundletool_version=""

  [[ -n "${BUNDLETOOL_JAR:-}" && -f "$BUNDLETOOL_JAR" ]] ||
    fail "BUNDLETOOL_JAR is required to inspect the final AAB manifest."
  [[ -n "${BUNDLETOOL_VERSION:-}" ]] || fail "BUNDLETOOL_VERSION is required."
  expected_bundletool_sha="$(normalize_sha256 "${BUNDLETOOL_SHA256:-}" || true)"
  [[ -n "$expected_bundletool_sha" ]] || fail "BUNDLETOOL_SHA256 is required."
  actual_bundletool_sha="$(sha256_file "$BUNDLETOOL_JAR")"
  [[ "$actual_bundletool_sha" == "$expected_bundletool_sha" ]] ||
    fail "bundletool SHA-256 does not match the pinned digest."
  java="$(resolve_command JAVA_BIN java)"
  jarsigner="$(resolve_command JARSIGNER_BIN jarsigner)"
  keytool="$(resolve_command KEYTOOL_BIN keytool)"

  actual_bundletool_version="$("$java" -jar "$BUNDLETOOL_JAR" version | trim_line)"
  [[ "$actual_bundletool_version" == "$BUNDLETOOL_VERSION" ]] ||
    fail "bundletool version does not match the pinned version."

  if ! "$jarsigner" -verify -strict -certs "$ARTIFACT_FILE" >/dev/null 2>&1; then
    fail "AAB JAR signature verification failed."
  fi
  if ! cert_output="$("$keytool" -J-Duser.language=en -J-Duser.country=US -printcert -jarfile "$ARTIFACT_FILE" 2>&1)"; then
    fail "Unable to read the AAB signer certificate."
  fi
  cert_digests="$(printf '%s\n' "$cert_output" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p')"
  cert_count="$(printf '%s\n' "$cert_digests" | awk 'NF { count += 1 } END { print count + 0 }')"
  [[ "$cert_count" == "1" ]] || fail "AAB must expose exactly one signer certificate."
  cert_digest="$(normalize_sha256 "$cert_digests" || true)"
  [[ -n "$cert_digest" && "$cert_digest" == "$EXPECTED_CERT_SHA256" ]] ||
    fail "AAB signer certificate does not match the approved release certificate."

  if ! "$java" -jar "$BUNDLETOOL_JAR" dump manifest \
    --bundle="$ARTIFACT_FILE" --module=base > "$manifest_file"; then
    fail "Unable to decode the final AAB manifest with bundletool."
  fi
  verify_manifest "$manifest_file"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) ARTIFACT_TYPE="${2:-}"; shift ;;
    --file) ARTIFACT_FILE="${2:-}"; shift ;;
    --package) EXPECTED_PACKAGE="${2:-}"; shift ;;
    --version-name) EXPECTED_VERSION_NAME="${2:-}"; shift ;;
    --version-code) EXPECTED_VERSION_CODE="${2:-}"; shift ;;
    --edition) EXPECTED_EDITION="${2:-}"; shift ;;
    --cert-sha256) EXPECTED_CERT_SHA256="${2:-}"; shift ;;
    --permission-baseline) PERMISSION_BASELINE="${2:-}"; shift ;;
    --forbid-permission) FORBIDDEN_PERMISSIONS+=("${2:-}"); shift ;;
    *) fail "Unknown artifact verification option: $1" ;;
  esac
  shift
done

[[ "$ARTIFACT_TYPE" == "apk" || "$ARTIFACT_TYPE" == "aab" ]] || fail "--type must be apk or aab."
[[ -f "$ARTIFACT_FILE" && -s "$ARTIFACT_FILE" ]] || fail "Release artifact was not found or is empty."
[[ -n "$EXPECTED_PACKAGE" && -n "$EXPECTED_VERSION_NAME" ]] || fail "Expected package and versionName are required."
[[ "$EXPECTED_VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "Expected versionCode must be positive."
[[ "$EXPECTED_EDITION" =~ ^[a-z0-9_]+$ ]] || fail "Expected edition is invalid."
EXPECTED_CERT_SHA256="$(normalize_sha256 "$EXPECTED_CERT_SHA256" || true)"
[[ -n "$EXPECTED_CERT_SHA256" ]] || fail "A 64-hex approved release certificate SHA-256 is required."
[[ -f "$PERMISSION_BASELINE" ]] || fail "Reviewed permission baseline was not found."

MANIFEST_FILE="$(mktemp)"
trap 'rm -f "$MANIFEST_FILE"' EXIT

case "$ARTIFACT_TYPE" in
  apk) verify_apk "$MANIFEST_FILE" ;;
  aab) verify_aab "$MANIFEST_FILE" ;;
esac

echo "${ARTIFACT_TYPE^^} release artifact verification: PASS"
