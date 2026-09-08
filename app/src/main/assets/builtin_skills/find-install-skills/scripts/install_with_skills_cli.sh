#!/bin/sh
# ============================================================
# THIS is the ONLY supported way to install skills for Omnibot.
# NEVER use `npx skills add` — always use this script instead.
# ============================================================
set -eu

usage() {
  cat >&2 <<'EOF'
Usage:
  sh install_with_skills_cli.sh <source> [--skill <name>]

Source formats:
  owner/repo                         → clone entire repo, install all skills found
  owner/repo@skill-name              → clone repo, install only skill-name
  owner/repo --skill skill-name      → same as above
  https://github.com/owner/repo      → full URL form

Examples:
  sh install_with_skills_cli.sh vercel-labs/skills --skill find-skills
  sh install_with_skills_cli.sh https://github.com/vercel-labs/skills --skill find-skills
  sh install_with_skills_cli.sh claude-office-skills/skills@excel-automation
EOF
  exit 1
}

[ $# -ge 1 ] || usage

SOURCE="$1"
shift

SKILL_FILTER=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skill|-s)
      [ $# -ge 2 ] || { echo "--skill requires a value" >&2; exit 2; }
      SKILL_FILTER="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      ;;
  esac
done

# --- Parse source into REPO_URL and optional SKILL_FILTER ---

# Handle owner/repo@skill format
case "$SOURCE" in
  git@github.com:*) ;;
  *@*)
    REPO_PART="${SOURCE%%@*}"
    AT_SKILL="${SOURCE#*@}"
    if [ -z "$SKILL_FILTER" ]; then
      SKILL_FILTER="$AT_SKILL"
    fi
    SOURCE="$REPO_PART"
    ;;
esac

# Convert to full GitHub URL if not already
case "$SOURCE" in
  https://github.com/*|git@github.com:*)
    REPO_URL="$SOURCE"
    ;;
  */*)
    REPO_URL="https://github.com/$SOURCE"
    ;;
  *)
    echo "Cannot parse source: $SOURCE" >&2
    echo "Expected owner/repo, owner/repo@skill, or a GitHub URL." >&2
    exit 2
    ;;
esac

# Strip trailing .git if present
REPO_URL="${REPO_URL%.git}"

# --- Set up directories ---

WORKSPACE_ROOT="${WORKSPACE_ROOT:-/workspace}"
OMNIBOT_ROOT="${OMNIBOT_ROOT:-$WORKSPACE_ROOT/.omnibot}"
TARGET_ROOT="${OMNIBOT_SKILLS_ROOT:-$OMNIBOT_ROOT/skills}"
TMP_BASE="${TMPDIR:-/tmp}/omnibot-skills-clone.$$"
CLONE_DIR="$TMP_BASE/repo"

cleanup() {
  rm -rf "$TMP_BASE"
}

trap cleanup EXIT INT TERM

mkdir -p "$TMP_BASE" "$TARGET_ROOT"

# --- Clone the repository ---

echo "Cloning $REPO_URL ..."
git clone --depth 1 "$REPO_URL" "$CLONE_DIR" 2>&1

if [ ! -d "$CLONE_DIR" ]; then
  echo "Failed to clone $REPO_URL" >&2
  exit 3
fi

# --- Discover skill directories (folders containing SKILL.md) ---

found_count=0
install_list="$TMP_BASE/install-list"
: > "$install_list"
mkdir -p "$TMP_BASE/planned-ids"

# Search up to 3 levels deep for SKILL.md files
find "$CLONE_DIR" -maxdepth 4 -name "SKILL.md" -type f > "$TMP_BASE/discovered-skills"
while IFS= read -r skill_md; do
  skill_dir="$(dirname "$skill_md")"
  skill_id="$(basename "$skill_dir")"

  # Skip the repo root if SKILL.md is at the top level
  if [ "$skill_dir" = "$CLONE_DIR" ]; then
    # Use the repo name as skill_id
    skill_id="$(basename "$REPO_URL")"
  fi

  # Apply filter if specified
  if [ -n "$SKILL_FILTER" ] && [ "$skill_id" != "$SKILL_FILTER" ]; then
    continue
  fi

  target_dir="$TARGET_ROOT/$skill_id"

  if [ -e "$target_dir" ]; then
    echo "Target skill already exists: $target_dir" >&2
    exit 4
  fi

  if ! mkdir "$TMP_BASE/planned-ids/$skill_id"; then
    echo "Duplicate skill id: $skill_id" >&2
    exit 4
  fi
  found_count=$((found_count + 1))
  printf '%s\n' "$skill_dir" >> "$install_list"
done < "$TMP_BASE/discovered-skills"

if [ "$found_count" -eq 0 ]; then
  if [ -n "$SKILL_FILTER" ]; then
    echo "No skill named '$SKILL_FILTER' found in $REPO_URL" >&2
  else
    echo "No skills (directories with SKILL.md) found in $REPO_URL" >&2
  fi
  exit 5
fi

# --- Copy discovered skills to Omnibot ---

copied_count=0

while IFS= read -r skill_dir; do
  skill_id="$(basename "$skill_dir")"
  if [ "$skill_dir" = "$CLONE_DIR" ]; then
    skill_id="$(basename "$REPO_URL")"
  fi
  target_dir="$TARGET_ROOT/$skill_id"

  cp -R "$skill_dir" "$target_dir"
  copied_count=$((copied_count + 1))
  echo "Installed $skill_id -> $target_dir"
done < "$install_list"

if [ "$copied_count" -ne "$found_count" ]; then
  echo "Copied $copied_count skills, expected $found_count" >&2
  exit 6
fi

echo "Done. Re-run skills_list in Omnibot to verify the new skill entries."
