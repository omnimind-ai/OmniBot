#!/usr/bin/env bash
set -euo pipefail

# Android versionCode layout for release tags:
#   vMAJOR.MINOR.PATCH[.BUILD]
#   (((MAJOR * 100) + MINOR) * 100 + PATCH) * 10000 + BUILD
#
# MINOR/PATCH are limited to 0..99 and BUILD to 0..9999. MAJOR is limited
# to 0..20 so the result always fits Android's signed 32-bit versionCode.
# This mapping preserves numeric tag ordering and is deterministic/auditable.

release_version_code_from_tag() {
  local raw_tag="${1:-}"
  local version="${raw_tag#v}"
  local major=""
  local minor=""
  local patch=""
  local build="0"
  local code=""
  local segment_pattern='(0|[1-9][0-9]*)'

  if [[ "$raw_tag" != v* ]]; then
    echo "Release tag must start with lowercase v: $raw_tag" >&2
    return 1
  fi

  if [[ "$version" =~ ^${segment_pattern}\.(${segment_pattern})\.(${segment_pattern})(\.(${segment_pattern}))?$ ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[3]}"
    patch="${BASH_REMATCH[5]}"
    build="${BASH_REMATCH[8]:-0}"
  else
    echo "Release tag must be vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH.BUILD: $raw_tag" >&2
    return 1
  fi

  if (( ${#major} > 2 || ${#minor} > 2 || ${#patch} > 2 || ${#build} > 4 )) ||
    (( 10#$major > 20 || 10#$minor > 99 || 10#$patch > 99 || 10#$build > 9999 )); then
    echo "Release tag is outside the auditable versionCode ranges: $raw_tag" >&2
    return 1
  fi

  code=$(((((10#$major * 100) + 10#$minor) * 100 + 10#$patch) * 10000 + 10#$build))
  if (( code <= 0 || code > 2147483647 )); then
    echo "Derived versionCode must be between 1 and 2147483647: $code" >&2
    return 1
  fi

  printf '%s\n' "$code"
}

release_version_name_from_tag() {
  local raw_tag="${1:-}"

  # Reuse the strict tag grammar/range validation. The numeric result is not
  # needed here, but a versionName must never accept a looser tag syntax.
  release_version_code_from_tag "$raw_tag" >/dev/null
  printf '%s\n' "${raw_tag#v}"
}

validate_release_version_name() {
  local raw_tag="${1:-}"
  local actual_version_name="${2:-}"
  local expected_version_name=""

  expected_version_name="$(release_version_name_from_tag "$raw_tag")"
  if [[ "$actual_version_name" != "$expected_version_name" ]]; then
    echo "Release tag $raw_tag requires Android versionName $expected_version_name, got $actual_version_name." >&2
    return 1
  fi

  printf '%s\n' "$expected_version_name"
}

read_gradle_version_name() {
  local gradle_file="${1:-}"
  local matches=""
  local count=""

  if [[ ! -f "$gradle_file" ]]; then
    echo "Gradle file was not found: $gradle_file" >&2
    return 1
  fi

  matches="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p' "$gradle_file")"
  count="$(printf '%s\n' "$matches" | awk 'NF { count += 1 } END { print count + 0 }')"
  if [[ "$count" != "1" ]]; then
    echo "Expected exactly one literal Android versionName in $gradle_file, found $count." >&2
    return 1
  fi

  printf '%s\n' "$matches"
}

verify_release_source_identity() {
  local raw_tag="${1:-}"
  local tag_commit=""
  local head_commit=""
  local dirty=""

  release_version_name_from_tag "$raw_tag" >/dev/null
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Release source verification requires a Git work tree." >&2
    return 1
  fi
  if ! git show-ref --verify --quiet "refs/tags/$raw_tag"; then
    echo "Release tag does not exist locally: $raw_tag" >&2
    return 1
  fi

  tag_commit="$(git rev-parse "refs/tags/$raw_tag^{commit}")"
  head_commit="$(git rev-parse HEAD)"
  if [[ "$head_commit" != "$tag_commit" ]]; then
    echo "HEAD $head_commit does not match $raw_tag commit $tag_commit." >&2
    return 1
  fi

  dirty="$(git status --porcelain=v1 --untracked-files=all)"
  if [[ -n "$dirty" ]]; then
    echo "Release work tree must be clean, including staged and untracked files." >&2
    return 1
  fi

  printf '%s\n' "$tag_commit"
}

validate_release_version_code() {
  local raw_tag="${1:-}"
  local supplied_code="${2:-}"
  local expected_code=""

  if [[ ! "$supplied_code" =~ ^[1-9][0-9]*$ ]] ||
    (( ${#supplied_code} > 10 )) ||
    { (( ${#supplied_code} == 10 )) && [[ "$supplied_code" > "2147483647" ]]; }; then
    echo "OMNI_RELEASE_VERSION_CODE must be a positive signed 32-bit integer: $supplied_code" >&2
    return 1
  fi

  expected_code="$(release_version_code_from_tag "$raw_tag")"
  if [[ "$supplied_code" != "$expected_code" ]]; then
    echo "Release versionCode $supplied_code does not match $raw_tag (expected $expected_code)." >&2
    return 1
  fi

  printf '%s\n' "$expected_code"
}

max_release_version_code_from_tags() {
  local excluded_tag="${1:-}"
  local tag=""
  local code=""
  local max_code=0

  while IFS= read -r tag; do
    [[ -n "$tag" && "$tag" != "$excluded_tag" ]] || continue
    code="$(release_version_code_from_tag "$tag" 2>/dev/null || true)"
    [[ -n "$code" ]] || continue
    if (( code > max_code )); then
      max_code="$code"
    fi
  done

  printf '%s\n' "$max_code"
}

verify_release_version_code_is_newest() {
  local raw_tag="${1:-}"
  local supplied_code="${2:-}"
  local resolved_code=""
  local max_existing_code=""

  resolved_code="$(validate_release_version_code "$raw_tag" "$supplied_code")"
  max_existing_code="$(git tag --list | max_release_version_code_from_tags "$raw_tag")"
  if (( resolved_code <= max_existing_code )); then
    echo "Release versionCode $resolved_code must be greater than prior tagged maximum $max_existing_code." >&2
    return 1
  fi

  printf '%s\n' "$resolved_code"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  command_name="${1:-}"
  case "$command_name" in
    derive)
      [[ $# -eq 2 ]] || { echo "Usage: $0 derive TAG" >&2; exit 2; }
      release_version_code_from_tag "$2"
      ;;
    version-name)
      [[ $# -eq 2 ]] || { echo "Usage: $0 version-name TAG" >&2; exit 2; }
      release_version_name_from_tag "$2"
      ;;
    validate-version-name)
      [[ $# -eq 3 ]] || { echo "Usage: $0 validate-version-name TAG VERSION_NAME" >&2; exit 2; }
      validate_release_version_name "$2" "$3"
      ;;
    read-gradle-version-name)
      [[ $# -eq 2 ]] || { echo "Usage: $0 read-gradle-version-name FILE" >&2; exit 2; }
      read_gradle_version_name "$2"
      ;;
    verify-source)
      [[ $# -eq 2 ]] || { echo "Usage: $0 verify-source TAG" >&2; exit 2; }
      verify_release_source_identity "$2"
      ;;
    validate)
      [[ $# -eq 3 ]] || { echo "Usage: $0 validate TAG VERSION_CODE" >&2; exit 2; }
      validate_release_version_code "$2" "$3"
      ;;
    verify-newest)
      [[ $# -eq 3 ]] || { echo "Usage: $0 verify-newest TAG VERSION_CODE" >&2; exit 2; }
      verify_release_version_code_is_newest "$2" "$3"
      ;;
    *)
      echo "Usage: $0 {derive|version-name|validate-version-name|read-gradle-version-name|verify-source|validate|verify-newest} ..." >&2
      exit 2
      ;;
  esac
fi
