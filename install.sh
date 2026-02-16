#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG="$PLUGIN_DIR/config.yaml"
SKILLS_DIR="$PLUGIN_DIR/skills"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

expand_path() { echo "${1/#\~/$HOME}"; }

# Parse config.yaml into parallel arrays (Bash 3.x compatible)
AGENT_NAMES=()
AGENT_PATHS=()
PRIMARY=""

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  [[ -z "${line// }" ]] && continue

  if [[ "$line" =~ ^primary:[[:space:]]*(.*) ]]; then
    PRIMARY="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ ^[[:space:]]+([a-zA-Z0-9_-]+):[[:space:]]*$ ]]; then
    current_agent="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ skills_dir:[[:space:]]*(.*) ]]; then
    AGENT_NAMES+=("$current_agent")
    AGENT_PATHS+=("$(expand_path "${BASH_REMATCH[1]}")")
  fi
done < "$CONFIG"

get_agent_path() {
  local name="$1"
  for i in "${!AGENT_NAMES[@]}"; do
    if [[ "${AGENT_NAMES[$i]}" == "$name" ]]; then
      echo "${AGENT_PATHS[$i]}"
      return
    fi
  done
}

if [[ -z "$PRIMARY" ]]; then
  err "No primary agent defined in config.yaml"
  exit 1
fi

echo ""
printf "${CYAN}━━━ Android Review Plugin Installer ━━━${NC}\n"
echo ""
info "Plugin:  $PLUGIN_DIR"
info "Primary: $PRIMARY"
echo ""

# Enumerate skills
SKILL_NAMES=()
for skill_path in "$SKILLS_DIR"/*/; do
  [[ -d "$skill_path" ]] || continue
  SKILL_NAMES+=("$(basename "$skill_path")")
done

info "Skills found: ${#SKILL_NAMES[@]}"
echo ""

# Step 1: Install skills to primary agent
PRIMARY_DIR="$(get_agent_path "$PRIMARY")"
if [[ -z "$PRIMARY_DIR" ]]; then
  err "Primary agent '$PRIMARY' not found in config"
  exit 1
fi

mkdir -p "$PRIMARY_DIR"
info "Installing to primary ($PRIMARY): $PRIMARY_DIR"

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

# Step 2: Symlink other agents → primary agent
for i in "${!AGENT_NAMES[@]}"; do
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"
  [[ "$agent" == "$PRIMARY" ]] && continue

  parent_dir="$(dirname "$agent_dir")"
  if [[ ! -d "$parent_dir" ]]; then
    warn "Skipping $agent (not installed: $parent_dir does not exist)"
    continue
  fi

  mkdir -p "$agent_dir"
  info "Symlinking $agent: $agent_dir → $PRIMARY_DIR"

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

# Summary
printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Primary agent:   $PRIMARY → $PRIMARY_DIR"
for i in "${!AGENT_NAMES[@]}"; do
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"
  [[ "$agent" == "$PRIMARY" ]] && continue
  parent_dir="$(dirname "$agent_dir")"
  if [[ -d "$parent_dir" ]]; then
    info "Linked agent:    $agent → $agent_dir (via $PRIMARY)"
  fi
done
echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
info "Run 'install.sh' again after adding new skills."
echo ""
