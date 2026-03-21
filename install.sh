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

get_agent_path() {
  case "$1" in
    copilot) echo "$HOME/.copilot/skills" ;;
    claude)  echo "$HOME/.claude/commands" ;;
    glm)     echo "$HOME/.glm/commands" ;;
    codex)   echo "$HOME/.agents/skills" ;;
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

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, glm, codex"
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
echo ""

SKILL_NAMES=()
for skill_path in "$SKILLS_DIR"/*/; do
  [[ -d "$skill_path" ]] || continue
  SKILL_NAMES+=("$(basename "$skill_path")")
done

info "Skills found: ${#SKILL_NAMES[@]}"
echo ""

remove_legacy_skill_links() {
  local target_dir="$1"

  for skill in "${SKILL_NAMES[@]}"; do
    case "$skill" in
      bill-*)
        legacy_skill="mdp-${skill#bill-}"
        legacy_target="$target_dir/$legacy_skill"
        if [[ -e "$legacy_target" || -L "$legacy_target" ]]; then
          rm -rf "$legacy_target"
          ok "  removed legacy $legacy_skill"
        fi
        ;;
    esac
  done
}

mkdir -p "$PRIMARY_DIR"
info "Installing to primary ($PRIMARY): $PRIMARY_DIR"
remove_legacy_skill_links "$PRIMARY_DIR"

for skill in "${SKILL_NAMES[@]}"; do
  target="$PRIMARY_DIR/$skill"
  source="$SKILLS_DIR/$skill"

  if [[ -e "$target" || -L "$target" ]]; then
    rm -rf "$target"
  fi

  ln -s "$source" "$target"
  ok "  $skill → plugin"
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
  remove_legacy_skill_links "$agent_dir"

  for skill in "${SKILL_NAMES[@]}"; do
    target="$agent_dir/$skill"
    source="$PRIMARY_DIR/$skill"

    if [[ -e "$target" || -L "$target" ]]; then
      rm -rf "$target"
    fi

    ln -s "$source" "$target"
    ok "  $skill"
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
echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
info "Run 'install.sh' again after adding new skills."
echo ""
