#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
MODE="safe"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

usage() {
  cat <<USAGE
Usage: ./install.sh [--mode safe|override|interactive]

Modes:
  safe         Replace existing symlinks, migrate legacy plugin installs, and skip non-symlink conflicts. (default)
  override     Replace any existing target path, including local copies, and prune stale bill-* installs.
  interactive  Prompt before replacing non-symlink conflicts.
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode)
        [[ $# -ge 2 ]] || { err "Missing value for --mode"; usage; exit 1; }
        MODE="$2"
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        err "Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done

  case "$MODE" in
    safe|override|interactive) ;;
    *)
      err "Invalid mode: $MODE"
      usage
      exit 1
      ;;
  esac
}

get_agent_path() {
  case "$1" in
    copilot) echo "$HOME/.copilot/skills" ;;
    claude)  echo "$HOME/.claude/commands" ;;
    glm)     echo "$HOME/.glm/commands" ;;
    codex)
      if [[ -d "$HOME/.codex" || -d "$HOME/.codex/skills" ]]; then
        echo "$HOME/.codex/skills"
      else
        echo "$HOME/.agents/skills"
      fi
      ;;
    *)       return 1 ;;
  esac
}

is_agent_available() {
  case "$1" in
    codex)
      [[ -d "$HOME/.codex" || -d "$HOME/.agents" ]]
      ;;
    *)
      local agent_dir
      agent_dir="$(get_agent_path "$1")"
      [[ -d "$(dirname "$agent_dir")" ]]
      ;;
  esac
}

declare -a RENAMED_SKILL_PAIRS=(
  'bill-code-review:bill-kotlin-code-review'
  'bill-code-review-architecture:bill-kotlin-code-review-architecture'
  'bill-code-review-backend-api-contracts:bill-kotlin-code-review-backend-api-contracts'
  'bill-code-review-backend-persistence:bill-kotlin-code-review-backend-persistence'
  'bill-code-review-backend-reliability:bill-kotlin-code-review-backend-reliability'
  'bill-code-review-compose-check:bill-kmp-code-review-compose-check'
  'bill-kotlin-code-review-compose-check:bill-kmp-code-review-compose-check'
  'bill-code-review-performance:bill-kotlin-code-review-performance'
  'bill-code-review-platform-correctness:bill-kotlin-code-review-platform-correctness'
  'bill-code-review-security:bill-kotlin-code-review-security'
  'bill-code-review-testing:bill-kotlin-code-review-testing'
  'bill-code-review-ux-accessibility:bill-kmp-code-review-ux-accessibility'
  'bill-kotlin-code-review-ux-accessibility:bill-kmp-code-review-ux-accessibility'
  'bill-feature-implement:bill-kotlin-feature-implement'
  'bill-feature-verify:bill-kotlin-feature-verify'
  'bill-gcheck:bill-kotlin-quality-check'
)

declare -a SKILL_NAMES=()
declare -a SKILL_PATHS=()
declare -a LEGACY_SKILL_NAMES=()
declare -a SKIPPED_TARGETS=()

auto_replace_allowed() {
  local target="$1"
  [[ -L "$target" ]]
}

confirm_replace() {
  local target="$1"
  local reason="$2"
  local answer

  while true; do
    printf "${CYAN}▸${NC} Replace '%s' (%s)? [y/N]: " "$target" "$reason"
    read -r answer
    case "$answer" in
      y|Y|yes|YES) return 0 ;;
      n|N|no|NO|'') return 1 ;;
      *) warn "Please answer y or n." ;;
    esac
  done
}

should_replace_target() {
  local target="$1"
  local reason="$2"

  if auto_replace_allowed "$target"; then
    return 0
  fi

  case "$MODE" in
    override)
      return 0
      ;;
    interactive)
      confirm_replace "$target" "$reason"
      return $?
      ;;
    safe)
      warn "Skipping '$target' (${reason}) because it is not a symlink. Re-run with --mode override or --mode interactive to replace it."
      return 1
      ;;
  esac
}

remove_if_allowed() {
  local target="$1"
  local reason="$2"

  if [[ ! -e "$target" && ! -L "$target" ]]; then
    return 0
  fi

  if should_replace_target "$target" "$reason"; then
    rm -rf "$target"
    return 0
  fi

  SKIPPED_TARGETS+=("$target")
  return 1
}

lookup_renamed_skill() {
  local query="$1"
  local pair old_name new_name

  for pair in "${RENAMED_SKILL_PAIRS[@]}"; do
    old_name="${pair%%:*}"
    new_name="${pair##*:}"
    if [[ "$old_name" == "$query" ]]; then
      printf '%s\n' "$new_name"
      return 0
    fi
  done

  return 1
}

find_skill_index() {
  local query="$1"
  local idx

  for idx in "${!SKILL_NAMES[@]}"; do
    if [[ "${SKILL_NAMES[$idx]}" == "$query" ]]; then
      printf '%s\n' "$idx"
      return 0
    fi
  done

  return 1
}

build_skill_names() {
  local skill_file skill_dir skill_name
  local existing_idx

  SKILL_NAMES=()
  SKILL_PATHS=()

  while IFS= read -r skill_file; do
    skill_dir="$(dirname "$skill_file")"
    skill_name="$(basename "$skill_dir")"

    if existing_idx="$(find_skill_index "$skill_name" 2>/dev/null)"; then
      err "Duplicate skill name '$skill_name' found at:"
      err "  ${SKILL_PATHS[$existing_idx]}"
      err "  $skill_dir"
      exit 1
    fi

    SKILL_NAMES+=("$skill_name")
    SKILL_PATHS+=("$skill_dir")
  done < <(find "$SKILLS_DIR" -type f -name 'SKILL.md' | sort)
}

add_legacy_name() {
  local candidate="$1"
  local existing
  for existing in "${LEGACY_SKILL_NAMES[@]:-}"; do
    if [[ "$existing" == "$candidate" ]]; then
      return
    fi
  done
  LEGACY_SKILL_NAMES+=("$candidate")
}

build_legacy_skill_names() {
  LEGACY_SKILL_NAMES=()

  local skill pair old_name
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

remove_legacy_skill_paths() {
  local target_dir="$1"
  local legacy_skill replacement legacy_target

  for legacy_skill in "${LEGACY_SKILL_NAMES[@]}"; do
    legacy_target="$target_dir/$legacy_skill"
    if [[ ! -e "$legacy_target" && ! -L "$legacy_target" ]]; then
      continue
    fi
    if replacement="$(lookup_renamed_skill "$legacy_skill" 2>/dev/null)"; then
      :
    else
      replacement=""
    fi
    if [[ -z "$replacement" && "$legacy_skill" == mdp-* ]]; then
      local bill_name="bill-${legacy_skill#mdp-}"
      if replacement="$(lookup_renamed_skill "$bill_name" 2>/dev/null)"; then
        :
      else
        replacement="$bill_name"
      fi
    fi

    if remove_if_allowed "$legacy_target" "legacy install path"; then
      if [[ -e "$legacy_target" || -L "$legacy_target" ]]; then
        :
      else
        if [[ -n "$replacement" ]]; then
          ok "  removed legacy $legacy_skill (use $replacement)"
        else
          ok "  removed legacy $legacy_skill"
        fi
      fi
    fi
  done
}

prune_project_skill_paths() {
  local target_dir="$1"
  local installed_path skill_name

  [[ -d "$target_dir" ]] || return 0

  while IFS= read -r -d '' installed_path; do
    skill_name="$(basename "$installed_path")"

    if find_skill_index "$skill_name" >/dev/null 2>&1; then
      continue
    fi

    if remove_if_allowed "$installed_path" "stale bill-* skill not present in plugin"; then
      if [[ ! -e "$installed_path" && ! -L "$installed_path" ]]; then
        ok "  pruned stale $skill_name"
      fi
    fi
  done < <(find "$target_dir" -mindepth 1 -maxdepth 1 -name 'bill-*' -print0)
}

install_skill_link() {
  local target="$1"
  local source="$2"
  local label="$3"

  if [[ -e "$target" || -L "$target" ]]; then
    if ! remove_if_allowed "$target" "existing install target"; then
      warn "  skipped $label"
      return
    fi
  fi

  ln -s "$source" "$target"
  ok "  $label"
}

parse_args "$@"
build_skill_names
build_legacy_skill_names

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, glm, codex"
info "Install mode: $MODE"
if [[ "$MODE" == safe ]]; then
  info "Safe mode replaces symlinks, migrates legacy plugin installs, and skips local directory/file conflicts."
elif [[ "$MODE" == override ]]; then
  info "Override mode replaces any existing target path and prunes stale bill-* installs."
fi
echo ""
printf "${CYAN}▸${NC} Enter agents (comma-separated, primary first): "
read -r input

if [[ -z "$input" ]]; then
  err "No agents provided"
  exit 1
fi

IFS=',' read -ra RAW_AGENTS <<< "$input"

AGENT_NAMES=()
AGENT_PATHS=()
for raw in "${RAW_AGENTS[@]}"; do
  agent="$(echo "$raw" | tr -d '[:space:]')"
  path="$(get_agent_path "$agent" 2>/dev/null || true)"
  if [[ -z "$path" ]]; then
    err "Unknown agent: $agent"
    exit 1
  fi
  AGENT_NAMES+=("$agent")
  AGENT_PATHS+=("$path")
done

PRIMARY="${AGENT_NAMES[0]}"
PRIMARY_DIR="${AGENT_PATHS[0]}"

echo ""
info "Plugin:  $PLUGIN_DIR"
info "Primary: $PRIMARY ($PRIMARY_DIR)"
info "Skills found: ${#SKILL_NAMES[@]}"
echo ""

mkdir -p "$PRIMARY_DIR"
info "Installing to primary ($PRIMARY): $PRIMARY_DIR"
if [[ "$MODE" == override ]]; then
  prune_project_skill_paths "$PRIMARY_DIR"
fi
remove_legacy_skill_paths "$PRIMARY_DIR"
for idx in "${!SKILL_NAMES[@]}"; do
  skill="${SKILL_NAMES[$idx]}"
  install_skill_link "$PRIMARY_DIR/$skill" "${SKILL_PATHS[$idx]}" "$skill → plugin"
done
echo ""

for i in "${!AGENT_NAMES[@]}"; do
  [[ "$i" -eq 0 ]] && continue
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"

  if ! is_agent_available "$agent"; then
    warn "Skipping $agent (not installed)"
    continue
  fi

  mkdir -p "$agent_dir"
  info "Symlinking $agent: $agent_dir → $PRIMARY_DIR"
  if [[ "$MODE" == override ]]; then
    prune_project_skill_paths "$agent_dir"
  fi
  remove_legacy_skill_paths "$agent_dir"

  for skill in "${SKILL_NAMES[@]}"; do
    install_skill_link "$agent_dir/$skill" "$PRIMARY_DIR/$skill" "$skill"
  done
  echo ""
done

printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Primary agent:   $PRIMARY → $PRIMARY_DIR"
for i in "${!AGENT_NAMES[@]}"; do
  [[ "$i" -eq 0 ]] && continue
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"
  if is_agent_available "$agent"; then
    info "Linked agent:    $agent → $agent_dir (via $PRIMARY)"
  fi
done

if [[ ${#SKIPPED_TARGETS[@]} -gt 0 ]]; then
  echo ""
  warn "Skipped ${#SKIPPED_TARGETS[@]} existing path(s):"
  for skipped in "${SKIPPED_TARGETS[@]}"; do
    warn "  $skipped"
  done
fi

echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
info "Run './install.sh --mode safe' again after adding new skills."
echo ""
