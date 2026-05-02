#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
MANAGED_INSTALL_MARKER=".skill-bill-install"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

# SKILL-14 + SKILL-16: pure relocations whose skill directory name stays the
# same (for example, moving
# skills/kotlin/bill-kotlin-quality-check/ to
# platform-packs/kotlin/quality-check/bill-kotlin-quality-check/) do NOT need
# RENAMED_SKILL_PAIRS entries. The installer's build_skill_names walks both
# skills/ AND platform-packs/, and the uninstaller removes
# $agent_dir/<skill_name> symlinks by name — so relocations are discovered
# automatically. Only use this array when the skill's canonical name changes.
declare -a RENAMED_SKILL_PAIRS=(
  'bill-module-history:bill-boundary-history'
  'bill-code-review-architecture:bill-kotlin-code-review-architecture'
  'bill-backend-kotlin-code-review:bill-kotlin-code-review'
  'bill-code-review-backend-api-contracts:bill-kotlin-code-review-api-contracts'
  'bill-kotlin-code-review-backend-api-contracts:bill-kotlin-code-review-api-contracts'
  'bill-backend-kotlin-code-review-api-contracts:bill-kotlin-code-review-api-contracts'
  'bill-code-review-backend-persistence:bill-kotlin-code-review-persistence'
  'bill-kotlin-code-review-backend-persistence:bill-kotlin-code-review-persistence'
  'bill-backend-kotlin-code-review-persistence:bill-kotlin-code-review-persistence'
  'bill-code-review-backend-reliability:bill-kotlin-code-review-reliability'
  'bill-kotlin-code-review-backend-reliability:bill-kotlin-code-review-reliability'
  'bill-backend-kotlin-code-review-reliability:bill-kotlin-code-review-reliability'
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
  done < <(
    {
      find "$SKILLS_DIR" -type f -name 'SKILL.md'
      if [[ -d "$PLATFORM_PACKS_DIR" ]]; then
        find "$PLATFORM_PACKS_DIR" -type f -name 'SKILL.md'
      fi
    } | sort
  )
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
  add_legacy_name ".bill-shared"

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

  if [[ -d "$target" && -f "$target/$MANAGED_INSTALL_MARKER" ]]; then
    rm -rf "$target"
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
  shopt -s nullglob
  for managed_target in "$target_dir"/*; do
    if [[ -d "$managed_target" && -f "$managed_target/$MANAGED_INSTALL_MARKER" ]]; then
      remove_skill_target "$managed_target"
    fi
  done
  shopt -u nullglob
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

unregister_mcp_json() {
  local config_path="$1"
  local label="$2"
  if [[ ! -f "$config_path" ]]; then
    return 0
  fi
  if python3 -c "
import json, sys
path = sys.argv[1]
try:
    settings = json.loads(open(path).read())
except (FileNotFoundError, json.JSONDecodeError):
    sys.exit(0)
servers = settings.get('mcpServers', {})
if 'skill-bill' not in servers:
    sys.exit(0)
del servers['skill-bill']
if not servers:
    del settings['mcpServers']
open(path, 'w').write(json.dumps(settings, indent=2, sort_keys=True) + '\n')
" "$config_path" 2>/dev/null; then
    ok "  removed skill-bill MCP server ($label)"
  fi
}

unregister_mcp_toml() {
  local config_path="$1"
  local label="$2"
  if [[ ! -f "$config_path" ]]; then
    return 0
  fi
  if python3 -c "
import sys, os
path = sys.argv[1]
if not os.path.exists(path):
    sys.exit(0)
lines = open(path).read().splitlines()
section = '[mcp_servers.skill-bill]'
filtered = []
skip = False
found = False
for line in lines:
    if line.strip() == section:
        skip = True
        found = True
        continue
    if skip and (line.startswith('[') or not line.strip()):
        if line.startswith('['):
            skip = False
            filtered.append(line)
        continue
    if not skip:
        filtered.append(line)
if not found:
    sys.exit(0)
while filtered and not filtered[-1].strip():
    filtered.pop()
filtered.append('')
open(path, 'w').write('\n'.join(filtered))
" "$config_path" 2>/dev/null; then
    ok "  removed skill-bill MCP server ($label)"
  fi
}

build_skill_names
build_legacy_skill_names

echo ""
printf "${CYAN}━━━ Skill Bill Uninstaller ━━━${NC}\n"
echo ""
info "Removing Skill Bill installs from supported agent paths."

remove_from_agent_dir "copilot" "$HOME/.copilot/skills"
remove_from_agent_dir "claude" "$HOME/.claude/commands"
remove_from_agent_dir "glm" "$HOME/.glm/commands"
remove_from_agent_dir "codex" "$HOME/.codex/skills"
remove_from_agent_dir "codex" "$HOME/.agents/skills"
remove_from_agent_dir "opencode" "$HOME/.config/opencode/skills"

remove_codex_agents_tomls() {
  # Uninstall Codex native subagent TOML symlinks from both candidate
  # directories. Manifest-driven: walks platform-packs/<slug>/**/codex-agents/*.toml
  # and removes any matching filename in $HOME/.codex/agents and
  # $HOME/.agents/agents. Idempotent.
  local python_cmd
  if python_cmd="$(command -v python3 2>/dev/null)"; then
    if "$python_cmd" -m skill_bill install unlink-codex-agents \
      --platform-packs "$PLATFORM_PACKS_DIR" 2>/dev/null; then
      ok "  Codex subagent TOMLs removed via skill_bill"
      return 0
    fi
  fi

  local toml_file link_path candidate_dir
  shopt -s nullglob globstar
  for toml_file in "$PLATFORM_PACKS_DIR"/**/codex-agents/*.toml; do
    [[ -f "$toml_file" ]] || continue
    for candidate_dir in "$HOME/.codex/agents" "$HOME/.agents/agents"; do
      link_path="$candidate_dir/$(basename "$toml_file")"
      if [[ -L "$link_path" ]]; then
        rm -f "$link_path"
        REMOVED_TARGETS+=("$link_path")
        ok "  removed $(basename "$link_path")"
      fi
    done
  done
  shopt -u nullglob globstar
}

info "Removing Codex subagent TOML installs."
remove_codex_agents_tomls

remove_opencode_agent_mds() {
  # Uninstall OpenCode native subagent markdown symlinks from
  # ~/.config/opencode/agents. Manifest-driven: walks
  # platform-packs/<slug>/**/opencode-agents/*.md and removes any matching
  # filename in the OpenCode agents directory. Idempotent.
  local python_cmd
  if python_cmd="$(command -v python3 2>/dev/null)"; then
    if "$python_cmd" -m skill_bill install unlink-opencode-agents \
      --platform-packs "$PLATFORM_PACKS_DIR" 2>/dev/null; then
      ok "  OpenCode subagent markdown removed via skill_bill"
      return 0
    fi
  fi

  local md_file link_path target_dir
  target_dir="$HOME/.config/opencode/agents"
  shopt -s nullglob globstar
  for md_file in "$PLATFORM_PACKS_DIR"/**/opencode-agents/*.md; do
    [[ -f "$md_file" ]] || continue
    link_path="$target_dir/$(basename "$md_file")"
    if [[ -L "$link_path" ]]; then
      rm -f "$link_path"
      REMOVED_TARGETS+=("$link_path")
      ok "  removed $(basename "$link_path")"
    fi
  done
  shopt -u nullglob globstar
}

info "Removing OpenCode subagent markdown installs."
remove_opencode_agent_mds

info "Removing MCP server registrations."
unregister_mcp_json "$HOME/.claude.json" "claude"
unregister_mcp_json "$HOME/.copilot/mcp-config.json" "copilot"
unregister_mcp_toml "$HOME/.codex/config.toml" "codex"
unregister_mcp_json "$HOME/.glm/mcp-config.json" "glm"
unregister_mcp_jsonc_opencode() {
  local config_path="$1"
  local label="$2"
  if [[ ! -f "$config_path" ]]; then
    return 0
  fi
  if python3 -c "
import json, sys, os

path = sys.argv[1]

def strip_jsonc(text):
    result = []
    in_string = False
    escape = False
    in_line_comment = False
    in_block_comment = False
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''
        if in_line_comment:
            if ch in '\r\n':
                in_line_comment = False
                result.append(ch)
            i += 1
            continue
        if in_block_comment:
            if ch == '*' and nxt == '/':
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue
        if in_string:
            result.append(ch)
            if escape:
                escape = False
            elif ch == '\\\\':
                escape = True
            elif ch == '\"':
                in_string = False
            i += 1
            continue
        if ch == '\"':
            in_string = True
            result.append(ch)
            i += 1
            continue
        if ch == '/' and nxt == '/':
            in_line_comment = True
            i += 2
            continue
        if ch == '/' and nxt == '*':
            in_block_comment = True
            i += 2
            continue
        result.append(ch)
        i += 1
    return ''.join(result)

def strip_trailing_commas(text):
    result = []
    in_string = False
    escape = False
    i = 0
    while i < len(text):
        ch = text[i]
        if in_string:
            result.append(ch)
            if escape:
                escape = False
            elif ch == '\\\\':
                escape = True
            elif ch == '\"':
                in_string = False
            i += 1
            continue
        if ch == '\"':
            in_string = True
            result.append(ch)
            i += 1
            continue
        if ch == ',':
            j = i + 1
            while j < len(text) and text[j] in ' \t\r\n':
                j += 1
            if j < len(text) and text[j] in '}]':
                i += 1
                continue
        result.append(ch)
        i += 1
    return ''.join(result)

try:
    raw = open(path).read()
except FileNotFoundError:
    sys.exit(0)

if raw.strip():
    try:
        settings = json.loads(strip_trailing_commas(strip_jsonc(raw)))
    except json.JSONDecodeError:
        sys.exit(0)
else:
    settings = {}

if not isinstance(settings, dict):
    sys.exit(0)

servers = settings.get('mcp', {})
if not isinstance(servers, dict) or 'skill-bill' not in servers:
    sys.exit(0)

del servers['skill-bill']
if servers:
    settings['mcp'] = servers
elif 'mcp' in settings:
    del settings['mcp']

open(path, 'w').write(json.dumps(settings, indent=2, sort_keys=True) + '\n')
" "$config_path" 2>/dev/null; then
    ok "  removed skill-bill MCP server ($label)"
  fi
}
unregister_mcp_jsonc_opencode "$HOME/.config/opencode/opencode.json" "opencode"

SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
if [[ -d "$SKILL_BILL_STATE_DIR" ]]; then
  info "Removing skill-bill state directory."
  rm -rf "$SKILL_BILL_STATE_DIR"
  ok "  removed $SKILL_BILL_STATE_DIR"
fi

echo ""
printf "${GREEN}━━━ Uninstall complete ━━━${NC}\n"
echo ""
info "Removed installs: ${#REMOVED_TARGETS[@]}"
if [[ ${#SKIPPED_TARGETS[@]} -gt 0 ]]; then
  warn "Skipped non-symlink paths: ${#SKIPPED_TARGETS[@]}"
fi
echo ""
