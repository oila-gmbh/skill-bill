#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
MANAGED_INSTALL_MARKER=".skill-bill-install"
RUNTIME_KOTLIN_DIR="$PLUGIN_DIR/runtime-kotlin"
RUNTIME_CLI_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
RUNTIME_INSTALL_ROOT="${SKILL_BILL_RUNTIME_DIR:-$SKILL_BILL_STATE_DIR/runtime}"
RUNTIME_CLI_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-cli"
RUNTIME_MCP_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-mcp"
RUNTIME_CLI_BIN="$RUNTIME_CLI_INSTALL_DIR/bin/runtime-cli"
RUNTIME_MCP_BIN="$RUNTIME_MCP_INSTALL_DIR/bin/runtime-mcp"
RUNTIME_LAUNCHER_BIN_DIR="${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}"
DESKTOP_APP_DEFAULT_NAME="SkillBill"
DESKTOP_APP_INSTALL_DIR="${SKILL_BILL_DESKTOP_APP_DIR:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { printf "${CYAN}▸${NC} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}⚠${NC} %s\n" "$1"; }
err()   { printf "${RED}✗${NC} %s\n" "$1"; }

if [[ "${SKILL_BILL_GOAL_CONTINUATION:-}" == "1" ]]; then
  err "Refusing to run uninstall.sh during skill-bill goal-continuation."
  err "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  exit 64
fi

trim_string() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

usage() {
  cat <<USAGE
Usage: ./uninstall.sh [--desktop-app-dir PATH]

Options:
  --desktop-app-dir PATH   Remove the desktop app from a custom install root.
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --desktop-app-dir)
        if [[ $# -lt 2 || -z "$(trim_string "$2")" ]]; then
          err "--desktop-app-dir requires a path."
          exit 1
        fi
        DESKTOP_APP_INSTALL_DIR="$2"
        shift 2
        ;;
      --desktop-app-dir=*)
        DESKTOP_APP_INSTALL_DIR="${1#--desktop-app-dir=}"
        if [[ -z "$(trim_string "$DESKTOP_APP_INSTALL_DIR")" ]]; then
          err "--desktop-app-dir requires a path."
          exit 1
        fi
        shift
        ;;
      *)
        err "Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done
}

host_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$path"
  else
    printf '%s' "$path"
  fi
}

desktop_host_os() {
  local uname_s
  uname_s="$(uname -s 2>/dev/null || printf 'unknown')"
  case "$uname_s" in
    Darwin*)
      printf 'macos'
      ;;
    Linux*)
      printf 'linux'
      ;;
    MINGW*|MSYS*|CYGWIN*)
      printf 'windows'
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

default_desktop_app_install_dir() {
  local os="$1"
  case "$os" in
    macos)
      printf '%s' "/Applications"
      ;;
    linux)
      printf '%s' "${XDG_DATA_HOME:-$HOME/.local/share}/skillbill/desktop"
      ;;
    windows)
      if [[ -n "${LOCALAPPDATA:-}" ]]; then
        host_path "$LOCALAPPDATA/SkillBill/Desktop"
      else
        printf '%s' "$HOME/AppData/Local/SkillBill/Desktop"
      fi
      ;;
    *)
      printf '%s' "$HOME/.skill-bill/desktop"
      ;;
  esac
}

locate_packaged_runtime_bin() {
  if [[ ! -x "$RUNTIME_CLI_BIN" && ! -x "$RUNTIME_CLI_BUILD_BIN" ]]; then
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
  mkdir -p "$RUNTIME_INSTALL_ROOT"
  rm -rf "$RUNTIME_CLI_INSTALL_DIR.tmp" "$RUNTIME_CLI_INSTALL_DIR"
  cp -R "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" "$RUNTIME_CLI_INSTALL_DIR.tmp"
  mv "$RUNTIME_CLI_INSTALL_DIR.tmp" "$RUNTIME_CLI_INSTALL_DIR"
  ok "Kotlin CLI runtime distribution ready"
}

run_runtime_cli() {
  local runtime_cli="$RUNTIME_CLI_BIN"
  if [[ ! -x "$runtime_cli" && -x "$RUNTIME_CLI_BUILD_BIN" ]]; then
    runtime_cli="$RUNTIME_CLI_BUILD_BIN"
  fi
  "$runtime_cli" --home "$HOME" "$@"
}

remove_runtime_launcher() {
  local name="$1"
  local expected_target="$2"
  local link_path="$RUNTIME_LAUNCHER_BIN_DIR/$name"
  local actual_target=""

  if [[ ! -L "$link_path" ]]; then
    return 0
  fi

  actual_target="$(readlink "$link_path")"
  if [[ "$actual_target" != "$expected_target" ]]; then
    warn "  skipped $link_path (points outside this checkout)"
    SKIPPED_TARGETS+=("$link_path")
    return 0
  fi

  rm -f "$link_path"
  REMOVED_TARGETS+=("$link_path")
  ok "  removed $name"
}

remove_runtime_launchers() {
  info "Removing runtime launchers from: $RUNTIME_LAUNCHER_BIN_DIR"
  remove_runtime_launcher "skill-bill" "$RUNTIME_CLI_BIN"
  remove_runtime_launcher "skill-bill-mcp" "$RUNTIME_MCP_BIN"
}

desktop_app_install_path() {
  local os="$1"
  local install_root

  install_root="${DESKTOP_APP_INSTALL_DIR:-$(default_desktop_app_install_dir "$os")}"
  install_root="$(host_path "$install_root")"
  case "$os" in
    macos)
      printf '%s' "$install_root/$DESKTOP_APP_DEFAULT_NAME.app"
      ;;
    *)
      printf '%s' "$install_root/$DESKTOP_APP_DEFAULT_NAME"
      ;;
  esac
}

desktop_app_executable_path() {
  local os="$1"
  local app_target="$2"
  case "$os" in
    macos)
      printf '%s' "$app_target/Contents/MacOS/$DESKTOP_APP_DEFAULT_NAME"
      ;;
    windows)
      printf '%s' "$app_target/bin/$DESKTOP_APP_DEFAULT_NAME.bat"
      ;;
    *)
      printf '%s' "$app_target/bin/$DESKTOP_APP_DEFAULT_NAME"
      ;;
  esac
}

remove_desktop_launcher() {
  local os="$1"
  local app_target="$2"
  local expected_target
  local link_path
  local actual_target

  expected_target="$(desktop_app_executable_path "$os" "$app_target")"
  case "$os" in
    windows)
      link_path="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop.cmd"
      if [[ -f "$link_path" ]]; then
        rm -f "$link_path"
        REMOVED_TARGETS+=("$link_path")
        ok "  removed skillbill-desktop.cmd"
      fi
      ;;
    *)
      link_path="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
      if [[ ! -L "$link_path" ]]; then
        if [[ -e "$link_path" ]]; then
          warn "  skipped $link_path (not a symlink)"
          SKIPPED_TARGETS+=("$link_path")
        fi
        return 0
      fi
      actual_target="$(readlink "$link_path")"
      if [[ "$actual_target" != "$expected_target" ]]; then
        warn "  skipped $link_path (points outside this desktop install)"
        SKIPPED_TARGETS+=("$link_path")
        return 0
      fi
      rm -f "$link_path"
      REMOVED_TARGETS+=("$link_path")
      ok "  removed skillbill-desktop"
      ;;
  esac
}

remove_linux_desktop_entry() {
  local desktop_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/skillbill.desktop"
  local icon_file="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/512x512/apps/skillbill.png"

  if [[ -f "$desktop_file" ]] && grep -q '^Name=SkillBill$' "$desktop_file"; then
    rm -f "$desktop_file"
    REMOVED_TARGETS+=("$desktop_file")
    ok "  removed Linux desktop entry"
  fi
  if [[ -f "$icon_file" ]]; then
    rm -f "$icon_file"
    REMOVED_TARGETS+=("$icon_file")
    ok "  removed Linux desktop icon"
  fi
}

remove_desktop_app() {
  local os
  local app_target

  os="$(desktop_host_os)"
  app_target="$(desktop_app_install_path "$os")"

  info "Removing desktop app install for $os: $app_target"
  remove_desktop_launcher "$os" "$app_target"
  if [[ "$os" == "linux" ]]; then
    remove_linux_desktop_entry
  fi
  if [[ -d "$app_target" ]]; then
    rm -rf "$app_target"
    REMOVED_TARGETS+=("$app_target")
    ok "  removed desktop app"
  else
    info "  no desktop app install found"
  fi
}

# SKILL-14 + SKILL-16: pure relocations whose skill directory name stays the
# same (for example, moving
# skills/kotlin/bill-kotlin-code-check/ to
# platform-packs/kotlin/quality-check/bill-kotlin-code-check/) do NOT need
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
  'bill-feature-implement:bill-feature-task'
  'bill-kotlin-feature-implement:bill-feature-task'
  'bill-feature-implement-agentic:bill-feature-task'
  'bill-kotlin-feature-verify:bill-feature-verify'
  'bill-quality-check:bill-code-check'
  'bill-code-quality-check:bill-code-check'
  'bill-kotlin-quality-check:bill-kotlin-code-check'
  'bill-kotlin-code-quality-check:bill-kotlin-code-check'
  'bill-php-quality-check:bill-php-code-check'
  'bill-php-code-quality-check:bill-php-code-check'
  'bill-gcheck:bill-code-check'
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
      find "$SKILLS_DIR" -type f \( -name 'content.md' -o -name 'SKILL.md' \)
      if [[ -d "$PLATFORM_PACKS_DIR" ]]; then
        find "$PLATFORM_PACKS_DIR" -type f \( -name 'content.md' -o -name 'SKILL.md' \)
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

  if [[ ${#SKILL_NAMES[@]} -gt 0 ]]; then
    for skill in "${SKILL_NAMES[@]}"; do
      if [[ "$skill" == bill-* ]]; then
        add_legacy_name "mdp-${skill#bill-}"
      fi
    done
  fi

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

  if [[ ${#SKILL_NAMES[@]} -gt 0 ]]; then
    for skill_name in "${SKILL_NAMES[@]}"; do
      args+=(--skill-name "$skill_name")
    done
  fi
  if [[ ${#LEGACY_SKILL_NAMES[@]} -gt 0 ]]; then
    for skill_name in "${LEGACY_SKILL_NAMES[@]}"; do
      args+=(--legacy-name "$skill_name")
    done
  fi

  info "Checking $label: $target_dir"
  output="$(run_runtime_cli install cleanup-agent-target \
    --target-dir "$target_dir" \
    --marker "$MANAGED_INSTALL_MARKER" \
    ${args[@]+"${args[@]}"} || status=$?)"
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

parse_args "$@"

echo ""
printf "${CYAN}━━━ Skill Bill Uninstaller ━━━${NC}\n"
echo ""

remove_desktop_app

build_kotlin_runtime_distribution
build_skill_names
build_legacy_skill_names

info "Removing Skill Bill installs from supported agent paths."

remove_from_agent_dir "copilot" "$HOME/.copilot/skills"
# Clean skill links from every Claude config root the runtime discovers (default ~/.claude, named
# ~/.claude-<name> profiles, and CLAUDE_CONFIG_DIR), not just one pinned root, so multi-profile
# installs are fully removed.
remove_from_claude_skill_roots() {
  local roots root
  roots="$(run_runtime_cli install claude-roots 2>/dev/null)" || return 0
  [[ -z "$roots" ]] && return 0
  while IFS= read -r root; do
    [[ -n "$root" ]] || continue
    remove_from_agent_dir "claude" "$root/skills"
    # Legacy: pre-skills installs linked into the sibling commands dir. Sweep it so upgraded
    # uninstalls fully remove old slash-command links too.
    remove_from_agent_dir "claude" "$root/commands"
  done <<< "$roots"
}
remove_from_claude_skill_roots
# TODO(SKILL-34-followup): remove GLM cleanup branch on or after 2026-08-02 (one deprecation window).
info "GLM is no longer a first-class supported agent. If you used Skill Bill with GLM as a model inside Claude Code, your skills are unaffected — they live under the Claude Code skills directory."
remove_from_agent_dir "glm" "$HOME/.glm/commands"
remove_from_agent_dir "codex" "$HOME/.codex/skills"
remove_from_agent_dir "codex" "$HOME/.agents/skills"
remove_from_agent_dir "opencode" "$HOME/.config/opencode/skills"
remove_from_agent_dir "junie" "$HOME/.junie/skills"

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

remove_claude_agent_mds() {
  # Uninstall Claude native subagent markdown symlinks from every discovered config root's agents/
  # directory (~/.claude/agents plus ~/.claude-<name>/agents and CLAUDE_CONFIG_DIR/agents). The
  # runtime resolver fans the unlink across all roots; install historically never removed these.
  local output
  output="$(run_runtime_cli install unlink-claude-agents \
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

info "Removing Claude subagent markdown installs."
remove_claude_agent_mds

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

remove_junie_agent_mds() {
  # Uninstall Junie native subagent markdown symlinks from ~/.junie/agents.
  # The source discovery walks governed platform-pack and skill junie-agents/*.md
  # definitions and is independent from other agent setup choices.
  local output
  output="$(run_runtime_cli install unlink-junie-agents \
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

info "Removing Junie subagent markdown installs."
remove_junie_agent_mds

info "Removing MCP server registrations."
for agent in claude copilot codex glm opencode junie; do
  if mcp_output="$(run_runtime_cli install unregister-mcp "$agent" 2>/dev/null)"; then
    ok "  removed skill-bill MCP server ($agent)"
    while IFS= read -r profile_path; do
      [[ -n "$profile_path" ]] || continue
      info "    $profile_path"
    done <<< "$mcp_output"
  elif [[ -n "$mcp_output" ]]; then
    warn "  partially removed skill-bill MCP server ($agent); manual check may be needed"
    while IFS= read -r profile_path; do
      [[ -n "$profile_path" ]] || continue
      info "    $profile_path"
    done <<< "$mcp_output"
  else
    warn "  could not remove skill-bill MCP server ($agent)"
  fi
done

remove_runtime_launchers

remove_state_dir_full() {
  if [[ -d "$SKILL_BILL_STATE_DIR" ]]; then
    info "Removing skill-bill state directory."
    rm -rf "$SKILL_BILL_STATE_DIR"
    ok "  removed $SKILL_BILL_STATE_DIR"
  fi
}

# Pre-install wipe (SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1, set only by
# install.sh's run_pre_install_uninstall): clear runtime binaries and
# installed-skills staging so generator changes land on the next install, while
# PRESERVING the copied-in self-contained source set and durable state DBs:
#   - skills/, platform-packs/, orchestration/
#   - *.db workflow / telemetry / review stores
#   - the RESERVED baseline-manifest path (subtask-2 seam) below
# An explicit ./uninstall.sh leaves this flag unset and fully removes
# ~/.skill-bill via remove_state_dir_full.
remove_state_dir_preserving_source() {
  if [[ ! -d "$SKILL_BILL_STATE_DIR" ]]; then
    return 0
  fi
  info "Clearing skill-bill runtime/install state (preserving copied-in source)."

  # RESERVED SEAM (subtask 2): baseline-manifest.json is part of the preserved
  # self-contained source set and must survive the pre-install wipe.
  local preserved=(
    "skills"
    "platform-packs"
    "orchestration"
    "baseline-manifest.json"
  )

  local is_preserved entry name keep
  for entry in "$SKILL_BILL_STATE_DIR"/* "$SKILL_BILL_STATE_DIR"/.[!.]* "$SKILL_BILL_STATE_DIR"/..?*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    name="$(basename "$entry")"
    if [[ "$name" == *.db ]]; then
      continue
    fi
    is_preserved=0
    for keep in "${preserved[@]}"; do
      if [[ "$name" == "$keep" ]]; then
        is_preserved=1
        break
      fi
    done
    if [[ "$is_preserved" -eq 1 ]]; then
      continue
    fi
    rm -rf "$entry"
  done
  ok "  cleared runtime/install state under $SKILL_BILL_STATE_DIR (source and state DBs preserved)"
}

if [[ "${SKILL_BILL_PRESERVE_SOURCE_ON_WIPE:-}" == "1" ]]; then
  remove_state_dir_preserving_source
else
  remove_state_dir_full
fi

echo ""
printf "${GREEN}━━━ Uninstall complete ━━━${NC}\n"
echo ""
info "Removed installs: ${#REMOVED_TARGETS[@]}"
if [[ ${#SKIPPED_TARGETS[@]} -gt 0 ]]; then
  warn "Skipped non-symlink paths: ${#SKIPPED_TARGETS[@]}"
fi
echo ""
