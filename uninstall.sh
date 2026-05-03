#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
MANAGED_INSTALL_MARKER=".skill-bill-install"
RUNTIME_KOTLIN_DIR="$PLUGIN_DIR/runtime-kotlin"
RUNTIME_CLI_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

locate_packaged_runtime_bin() {
  if [[ ! -x "$RUNTIME_CLI_BIN" ]]; then
    err "Missing packaged Kotlin CLI runtime: $RUNTIME_CLI_BIN"
    return 1
  fi
}

build_kotlin_runtime_distribution() {
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    warn "Skipping packaged Kotlin runtime distribution build because SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1."
    locate_packaged_runtime_bin
    return 0
  fi

  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"
  if [[ ! -x "$gradlew" ]]; then
    err "Missing Gradle wrapper: $gradlew"
    exit 1
  fi

  info "Building packaged Kotlin CLI runtime distribution..."
  (
    cd "$RUNTIME_KOTLIN_DIR"
    ./gradlew -q :runtime-cli:installDist
  )
  locate_packaged_runtime_bin
  ok "Kotlin CLI runtime distribution ready"
}

run_runtime_cli() {
  "$RUNTIME_CLI_BIN" --home "$HOME" "$@"
}

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
    if [[ ${#SKILL_NAMES[@]} -eq 0 ]] || ! array_contains "$skill_name" "${SKILL_NAMES[@]}"; then
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
  if [[ ${#LEGACY_SKILL_NAMES[@]} -eq 0 ]] || ! array_contains "$candidate" "${LEGACY_SKILL_NAMES[@]}"; then
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
  local output
  local status=0
  local skill_name
  local args=()

  if [[ ! -d "$target_dir" ]]; then
    return 0
  fi

  for skill_name in "${SKILL_NAMES[@]}"; do
    args+=(--skill-name "$skill_name")
  done
  for skill_name in "${LEGACY_SKILL_NAMES[@]}"; do
    args+=(--legacy-name "$skill_name")
  done

  info "Checking $label: $target_dir"
  output="$(run_runtime_cli install cleanup-agent-target \
    --target-dir "$target_dir" \
    --marker "$MANAGED_INSTALL_MARKER" \
    "${args[@]}" || status=$?)"
  if [[ "${status:-0}" -ne 0 ]]; then
    warn "  cleanup failed"
    return 0
  fi
  if [[ -z "$output" ]]; then
    info "  nothing to remove"
    return 0
  fi
  while IFS=$'\t' read -r status skill_name; do
    [[ -n "$status" && -n "$skill_name" ]] || continue
    if [[ "$status" == "removed" ]]; then
      REMOVED_TARGETS+=("$skill_name")
      ok "  removed $(basename "$skill_name")"
    elif [[ "$status" == "skipped" ]]; then
      SKIPPED_TARGETS+=("$skill_name")
      warn "  skipped $(basename "$skill_name") (not a symlink)"
    fi
  done <<< "$output"
}

build_kotlin_runtime_distribution
build_skill_names
build_legacy_skill_names

echo ""
printf "${CYAN}━━━ Skill Bill Uninstaller ━━━${NC}\n"
echo ""
info "Removing Skill Bill installs from supported agent paths."

remove_from_agent_dir "copilot" "$HOME/.copilot/skills"
remove_from_agent_dir "claude" "$HOME/.claude/commands"
# TODO(SKILL-34-followup): remove GLM cleanup branch on or after 2026-08-02 (one deprecation window).
info "GLM is no longer a first-class supported agent. If you used Skill Bill with GLM as a model inside Claude Code, your skills are unaffected — they live under the Claude Code commands directory."
remove_from_agent_dir "glm" "$HOME/.glm/commands"
remove_from_agent_dir "codex" "$HOME/.codex/skills"
remove_from_agent_dir "codex" "$HOME/.agents/skills"
remove_from_agent_dir "opencode" "$HOME/.config/opencode/skills"

remove_codex_agents_tomls() {
  # Uninstall Codex native subagent TOML symlinks from both candidate
  # directories. Manifest-driven: walks platform-packs/<slug>/**/codex-agents/*.toml
  # and removes any matching filename in $HOME/.codex/agents and
  # $HOME/.agents/agents. Idempotent.
  local output
  output="$(run_runtime_cli install unlink-codex-agents \
    --platform-packs "$PLATFORM_PACKS_DIR" \
    --skills "$SKILLS_DIR")"
  if [[ -z "$output" ]]; then
    info "  nothing to remove"
    return 0
  fi
  while IFS= read -r link_path; do
    [[ -n "$link_path" ]] || continue
    REMOVED_TARGETS+=("$link_path")
    ok "  removed $(basename "$link_path")"
  done <<< "$output"
}

info "Removing Codex subagent TOML installs."
remove_codex_agents_tomls

remove_opencode_agent_mds() {
  # Uninstall OpenCode native subagent markdown symlinks from
  # ~/.config/opencode/agents. Manifest-driven: walks
  # platform-packs/<slug>/**/opencode-agents/*.md and removes any matching
  # filename in the OpenCode agents directory. Idempotent.
  local output
  output="$(run_runtime_cli install unlink-opencode-agents \
    --platform-packs "$PLATFORM_PACKS_DIR" \
    --skills "$SKILLS_DIR")"
  if [[ -z "$output" ]]; then
    info "  nothing to remove"
    return 0
  fi
  while IFS= read -r link_path; do
    [[ -n "$link_path" ]] || continue
    REMOVED_TARGETS+=("$link_path")
    ok "  removed $(basename "$link_path")"
  done <<< "$output"
}

info "Removing OpenCode subagent markdown installs."
remove_opencode_agent_mds

info "Removing MCP server registrations."
for agent in claude copilot codex glm opencode; do
  if run_runtime_cli install unregister-mcp "$agent" >/dev/null 2>&1; then
    ok "  removed skill-bill MCP server ($agent)"
  else
    warn "  could not remove skill-bill MCP server ($agent)"
  fi
done

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
