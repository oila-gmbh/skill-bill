#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

declare -a RENAMED_SKILL_PAIRS=(
  'bill-module-history:bill-boundary-history'
  'bill-code-review-architecture:bill-kotlin-code-review-architecture'
  'bill-code-review-backend-api-contracts:bill-backend-kotlin-code-review-api-contracts'
  'bill-kotlin-code-review-backend-api-contracts:bill-backend-kotlin-code-review-api-contracts'
  'bill-code-review-backend-persistence:bill-backend-kotlin-code-review-persistence'
  'bill-kotlin-code-review-backend-persistence:bill-backend-kotlin-code-review-persistence'
  'bill-code-review-backend-reliability:bill-backend-kotlin-code-review-reliability'
  'bill-kotlin-code-review-backend-reliability:bill-backend-kotlin-code-review-reliability'
  'bill-code-review-compose-check:bill-kmp-code-review-ui'
  'bill-kotlin-code-review-compose-check:bill-kmp-code-review-ui'
  'bill-kmp-code-review-compose-check:bill-kmp-code-review-ui'
  'bill-code-review-performance:bill-kotlin-code-review-performance'
  'bill-code-review-platform-correctness:bill-kotlin-code-review-platform-correctness'
  'bill-code-review-security:bill-kotlin-code-review-security'
  'bill-code-review-testing:bill-kotlin-code-review-testing'
  'bill-code-review-ux-accessibility:bill-kmp-code-review-ux-accessibility'
  'bill-kotlin-code-review-ux-accessibility:bill-kmp-code-review-ux-accessibility'
  'bill-kotlin-feature-implement:bill-feature-implement'
  'bill-kotlin-feature-verify:bill-feature-verify'
  'bill-gcheck:bill-quality-check'
)

declare -a SKILL_NAMES=()
declare -a LEGACY_SKILL_NAMES=()
declare -a REMOVED_TARGETS=()
declare -a SKIPPED_TARGETS=()

array_contains() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    if [[ "$item" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

build_skill_names() {
  local skill_file
  local skill_dir
  local skill_name

  SKILL_NAMES=()

  while IFS= read -r skill_file; do
    skill_dir="$(dirname "$skill_file")"
    skill_name="$(basename "$skill_dir")"
    if ! array_contains "$skill_name" "${SKILL_NAMES[@]:-}"; then
      SKILL_NAMES+=("$skill_name")
    fi
  done < <(find "$SKILLS_DIR" -type f -name 'SKILL.md' | sort)
}

add_legacy_name() {
  local candidate="$1"
  if ! array_contains "$candidate" "${LEGACY_SKILL_NAMES[@]:-}"; then
    LEGACY_SKILL_NAMES+=("$candidate")
  fi
}

build_legacy_skill_names() {
  local skill
  local pair
  local old_name

  LEGACY_SKILL_NAMES=()

  for skill in "${SKILL_NAMES[@]}"; do
    if [[ "$skill" == bill-* ]]; then
      add_legacy_name "mdp-${skill#bill-}"
    fi
  done

  for pair in "${RENAMED_SKILL_PAIRS[@]}"; do
    old_name="${pair%%:*}"
    add_legacy_name "$old_name"
    add_legacy_name "mdp-${old_name#bill-}"
  done
}

remove_skill_target() {
  local target="$1"

  if [[ -L "$target" ]]; then
    rm -f "$target"
    REMOVED_TARGETS+=("$target")
    ok "  removed $(basename "$target")"
    return
  fi

  if [[ -e "$target" ]]; then
    SKIPPED_TARGETS+=("$target")
    warn "  skipped $(basename "$target") (not a symlink)"
  fi
}

remove_from_agent_dir() {
  local label="$1"
  local target_dir="$2"
  local before_removed
  local before_skipped
  local skill_name

  if [[ ! -d "$target_dir" ]]; then
    return 0
  fi

  before_removed=${#REMOVED_TARGETS[@]}
  before_skipped=${#SKIPPED_TARGETS[@]}

  info "Checking $label: $target_dir"
  for skill_name in "${SKILL_NAMES[@]}"; do
    remove_skill_target "$target_dir/$skill_name"
  done
  for skill_name in "${LEGACY_SKILL_NAMES[@]}"; do
    remove_skill_target "$target_dir/$skill_name"
  done

  if [[ ${#REMOVED_TARGETS[@]} -eq "$before_removed" && ${#SKIPPED_TARGETS[@]} -eq "$before_skipped" ]]; then
    info "  nothing to remove"
  fi
}

build_skill_names
build_legacy_skill_names

echo ""
printf "${CYAN}━━━ Skill Bill Uninstaller ━━━${NC}\n"
echo ""
info "Removing Skill Bill symlinks from supported agent paths."

remove_from_agent_dir "copilot" "$HOME/.copilot/skills"
remove_from_agent_dir "claude" "$HOME/.claude/commands"
remove_from_agent_dir "glm" "$HOME/.glm/commands"
remove_from_agent_dir "codex" "$HOME/.codex/skills"
remove_from_agent_dir "codex" "$HOME/.agents/skills"

echo ""
printf "${GREEN}━━━ Uninstall complete ━━━${NC}\n"
echo ""
info "Removed symlinks: ${#REMOVED_TARGETS[@]}"
if [[ ${#SKIPPED_TARGETS[@]} -gt 0 ]]; then
  warn "Skipped non-symlink paths: ${#SKIPPED_TARGETS[@]}"
fi
echo ""
