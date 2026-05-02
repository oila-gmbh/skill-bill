#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
MANAGED_INSTALL_MARKER=".skill-bill-install"
RUNTIME_KOTLIN_DIR="$PLUGIN_DIR/runtime-kotlin"
RUNTIME_CLI_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
RUNTIME_MCP_BIN="$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp"

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
Usage: ./install.sh
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
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
}

get_agent_path() {
  # Source of truth is skill_bill/install.py::agent_paths. We shell out to
  # the Python module so the bash installer and the new-skill scaffolder
  # agree on every supported-agent path without duplicating the table.
  # Falls back to the inline case block when python3 is missing, so a
  # first-run install on a fresh machine still bootstraps correctly.
  local python_cmd
  if python_cmd="$(command -v python3 2>/dev/null)"; then
    if output="$("$python_cmd" -m skill_bill install agent-path "$1" 2>/dev/null)"; then
      echo "$output"
      return 0
    fi
  fi
  case "$1" in
    copilot) echo "$HOME/.copilot/skills" ;;
    claude)  echo "$HOME/.claude/commands" ;;
    glm)     echo "$HOME/.glm/commands" ;;
    opencode) echo "$HOME/.config/opencode/skills" ;;
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

get_codex_agents_path() {
  # Mirrors get_agent_path for the native Codex subagents directory.
  # Source of truth lives in skill_bill/install.py::_codex_agents_path.
  local python_cmd
  if python_cmd="$(command -v python3 2>/dev/null)"; then
    if output="$("$python_cmd" -m skill_bill install codex-agents-path 2>/dev/null)"; then
      echo "$output"
      return 0
    fi
  fi
  if [[ -d "$HOME/.codex" || -d "$HOME/.codex/agents" ]]; then
    echo "$HOME/.codex/agents"
  else
    echo "$HOME/.agents/agents"
  fi
}

install_codex_agents_tomls() {
  # Install Codex native subagent TOML defs under the resolved agents dir.
  # Walks platform-packs/<slug>/**/codex-agents/*.toml — manifest-driven,
  # never hardcodes a slug. Runs only when codex was selected by the user.
  local target_dir
  target_dir="$(get_codex_agents_path)"
  mkdir -p "$target_dir"
  info "Installing Codex subagent TOMLs to: $target_dir"

  local python_cmd
  if python_cmd="$(command -v python3 2>/dev/null)"; then
    if "$python_cmd" -m skill_bill install link-codex-agents \
      --platform-packs "$PLATFORM_PACKS_DIR" 2>/dev/null; then
      ok "  Codex subagent TOMLs linked via skill_bill"
      return 0
    fi
  fi

  local toml_file link_path
  shopt -s nullglob globstar
  for toml_file in "$PLATFORM_PACKS_DIR"/**/codex-agents/*.toml; do
    [[ -f "$toml_file" ]] || continue
    link_path="$target_dir/$(basename "$toml_file")"
    if [[ -L "$link_path" || -e "$link_path" ]]; then
      rm -f "$link_path"
    fi
    ln -s "$toml_file" "$link_path"
    ok "  $(basename "$toml_file") → $toml_file"
  done
  shopt -u nullglob globstar
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
  'bill-backend-kotlin-code-review:bill-kotlin-code-review'
  'bill-code-review-architecture:bill-kotlin-code-review-architecture'
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
  'bill-feature-implement-agentic:bill-feature-implement'
  'bill-kotlin-feature-verify:bill-feature-verify'
  'bill-gcheck:bill-quality-check'
  'bill-skill-scaffold:bill-create-skill'
  'bill-new-skill-all-agents:bill-create-skill'
)

declare -a SUPPORTED_AGENTS=(copilot claude glm codex opencode)
declare -a SKILL_NAMES=()
declare -a SKILL_PATHS=()
declare -a INSTALL_SKILL_NAMES=()
declare -a INSTALL_SKILL_PATHS=()
declare -a PLATFORM_PACKAGES=()
declare -a REQUIRED_PLATFORM_PACKAGES=()
declare -a SELECTED_PLATFORM_PACKAGES=()
declare -a LEGACY_SKILL_NAMES=()
TELEMETRY_LEVEL="anonymous"

remove_if_allowed() {
  local target="$1"

  if [[ ! -e "$target" && ! -L "$target" ]]; then
    return 0
  fi

  if [[ -L "$target" ]]; then
    rm -f "$target"
    return 0
  fi

  if [[ -d "$target" ]]; then
    if [[ -f "$target/$MANAGED_INSTALL_MARKER" ]] || path_has_matching_skill_name "$target"; then
      rm -rf "$target"
      return 0
    fi
  fi

  err "Refusing to overwrite existing non-Skill-Bill path: $target"
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

trim_string() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

normalize_platform_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_/-'
}

normalize_agent_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]'
}

add_agent_selection() {
  local agent="$1"
  if ! array_contains "$agent" "${AGENT_NAMES[@]:-}"; then
    AGENT_NAMES+=("$agent")
    AGENT_PATHS+=("$(get_agent_path "$agent")")
  fi
}

resolve_agent_selection() {
  local token="$1"
  local normalized
  local index
  local all_index
  local agent

  token="$(trim_string "$token")"
  [[ -n "$token" ]] || return 1

  if [[ "$token" =~ ^[0-9]+$ ]]; then
    index=$((token - 1))
    if (( index >= 0 && index < ${#SUPPORTED_AGENTS[@]} )); then
      printf '%s\n' "${SUPPORTED_AGENTS[$index]}"
      return 0
    fi
    all_index=${#SUPPORTED_AGENTS[@]}
    if (( index == all_index )); then
      printf '__all__\n'
      return 0
    fi
    return 1
  fi

  normalized="$(normalize_agent_token "$token")"
  if [[ "$normalized" == "all" ]]; then
    printf '__all__\n'
    return 0
  fi

  for agent in "${SUPPORTED_AGENTS[@]}"; do
    if [[ "$normalized" == "$agent" ]]; then
      printf '%s\n' "$agent"
      return 0
    fi
  done

  return 1
}

format_agent_list() {
  local result=""
  local agent

  for agent in "$@"; do
    if [[ -z "$result" ]]; then
      result="$agent"
    else
      result="$result, $agent"
    fi
  done

  printf '%s' "$result"
}

prompt_for_agent_selection() {
  local input
  local raw_tokens=()
  local invalid_tokens=()
  local token
  local resolved
  local i
  local option_number
  local supported_agent

  while true; do
    echo ""
    info "Available agents:"
    for i in "${!SUPPORTED_AGENTS[@]}"; do
      printf "  %s. %s\n" "$((i + 1))" "${SUPPORTED_AGENTS[$i]}"
    done
    option_number=$(( ${#SUPPORTED_AGENTS[@]} + 1 ))
    printf "  %s. all (install to every supported agent)\n" "$option_number"
    info "Choose one or more agents (comma-separated)."
    printf "${CYAN}▸${NC} Enter agents: "
    read -r input

    if [[ -z "$(trim_string "$input")" ]]; then
      warn "No agents provided. Choose at least one agent."
      continue
    fi

    AGENT_NAMES=()
    AGENT_PATHS=()
    invalid_tokens=()
    IFS=',' read -ra raw_tokens <<< "$input"

    for token in "${raw_tokens[@]:-}"; do
      token="$(trim_string "$token")"
      [[ -z "$token" ]] && continue
      resolved="$(resolve_agent_selection "$token" 2>/dev/null || true)"
      if [[ -z "$resolved" ]]; then
        invalid_tokens+=("$token")
        continue
      fi
      if [[ "$resolved" == "__all__" ]]; then
        for supported_agent in "${SUPPORTED_AGENTS[@]:-}"; do
          add_agent_selection "$supported_agent"
        done
        continue
      fi
      add_agent_selection "$resolved"
    done

    if [[ ${#invalid_tokens[@]} -gt 0 ]]; then
      warn "Unknown agent selection: $(printf '%s, ' "${invalid_tokens[@]}" | sed 's/, $//')"
      continue
    fi

    if [[ ${#AGENT_NAMES[@]} -eq 0 ]]; then
      warn "No valid agents selected. Choose at least one agent."
      continue
    fi

    return 0
  done
}

display_platform_name() {
  case "$1" in
    kotlin) printf 'Kotlin' ;;
    kmp) printf 'KMP' ;;
    *)
      local label="${1//-/ }"
      printf '%s' "$label"
      ;;
  esac
}

build_platform_packages() {
  local discovered=()
  local package

  while IFS= read -r package; do
    [[ "$package" == "base" || "$package" == bill-* ]] && continue
    discovered+=("$package")
  done < <(find "$SKILLS_DIR" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)

  # Platform packs contribute package names as well. With SKILL-14, most
  # platform code-review content lives under platform-packs/<slug>/. These
  # slugs may or may not overlap with skills/<package>/ entries; union both.
  if [[ -d "$PLATFORM_PACKS_DIR" ]]; then
    while IFS= read -r package; do
      if ! array_contains "$package" "${discovered[@]:-}"; then
        discovered+=("$package")
      fi
    done < <(find "$PLATFORM_PACKS_DIR" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)
  fi

  PLATFORM_PACKAGES=()
  for package in "${discovered[@]:-}"; do
    if ! array_contains "$package" "${PLATFORM_PACKAGES[@]:-}"; then
      PLATFORM_PACKAGES+=("$package")
    fi
  done
}

resolve_platform_selection() {
  local token="$1"
  local normalized
  local package
  local index
  local all_index

  token="$(trim_string "$token")"
  [[ -n "$token" ]] || return 1

  if [[ "$token" =~ ^[0-9]+$ ]]; then
    index=$((token - 1))
    if (( index >= 0 && index < ${#PLATFORM_PACKAGES[@]} )); then
      printf '%s\n' "${PLATFORM_PACKAGES[$index]}"
      return 0
    fi
    all_index=${#PLATFORM_PACKAGES[@]}
    if (( index == all_index )); then
      printf '__all__\n'
      return 0
    fi
    return 1
  fi

  normalized="$(normalize_platform_token "$token")"
  case "$normalized" in
    all)
      printf '__all__\n'
      return 0
      ;;
    kotlin)
      printf 'kotlin\n'
      return 0
      ;;
    kmp|androidkmp)
      printf 'kmp\n'
      return 0
      ;;
  esac

  for package in "${PLATFORM_PACKAGES[@]}"; do
    if [[ "$normalized" == "$(normalize_platform_token "$package")" ]]; then
      printf '%s\n' "$package"
      return 0
    fi
    if [[ "$normalized" == "$(normalize_platform_token "$(display_platform_name "$package")")" ]]; then
      printf '%s\n' "$package"
      return 0
    fi
  done

  return 1
}

format_platform_list() {
  local result=""
  local package
  local label

  for package in "$@"; do
    label="$(display_platform_name "$package")"
    if [[ -z "$result" ]]; then
      result="$label"
    else
      result="$result, $label"
    fi
  done

  printf '%s' "$result"
}

append_required_platform_packages() {
  local package

  for package in "${REQUIRED_PLATFORM_PACKAGES[@]:-}"; do
    # A required package is present if it exists under skills/ OR under
    # platform-packs/. The check spans both trees so future required packs
    # continue to work if they live under platform-packs/.
    if { [[ -d "$SKILLS_DIR/$package" ]] || [[ -d "$PLATFORM_PACKS_DIR/$package" ]]; } \
      && ! array_contains "$package" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
      SELECTED_PLATFORM_PACKAGES+=("$package")
    fi
  done
}

prompt_for_platform_selection() {
  local input
  local i
  local option_number
  local package
  local token
  local resolved
  local invalid_tokens=()
  local raw_tokens=()

  if [[ ${#PLATFORM_PACKAGES[@]} -eq 0 ]]; then
    SELECTED_PLATFORM_PACKAGES=()
    append_required_platform_packages
    return 0
  fi

  while true; do
    echo ""
    info "Available optional platforms:"
    for i in "${!PLATFORM_PACKAGES[@]}"; do
      package="${PLATFORM_PACKAGES[$i]}"
      printf "  %s. %s (%s)\n" "$((i + 1))" "$(display_platform_name "$package")" "$package"
    done
    option_number=$(( ${#PLATFORM_PACKAGES[@]} + 1 ))
    printf "  %s. all (install every platform package)\n" "$option_number"
    info "Base skills are always installed."
    info "Optional platform packages are discovered from platform-packs/ at install time."
    info "Governed add-on assets under platform-packs/<platform>/addons/ ship with their owning platform package."
    info "Choose one or more optional platform numbers (comma-separated). Names still work if you prefer them."
    printf "${CYAN}▸${NC} Enter platforms (e.g. 1,3 or %s): " "$option_number"
    read -r input

    if [[ -z "$(trim_string "$input")" ]]; then
      warn "No platforms provided. Choose at least one optional platform."
      continue
    fi

    SELECTED_PLATFORM_PACKAGES=()
    invalid_tokens=()
    IFS=',' read -ra raw_tokens <<< "$input"

    for token in "${raw_tokens[@]}"; do
      token="$(trim_string "$token")"
      [[ -z "$token" ]] && continue
      resolved="$(resolve_platform_selection "$token" 2>/dev/null || true)"
      if [[ -z "$resolved" ]]; then
        invalid_tokens+=("$token")
        continue
      fi
      if [[ "$resolved" == "__all__" ]]; then
        SELECTED_PLATFORM_PACKAGES=("${PLATFORM_PACKAGES[@]}")
        break
      fi
      if ! array_contains "$resolved" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
        SELECTED_PLATFORM_PACKAGES+=("$resolved")
      fi
    done

    if [[ ${#invalid_tokens[@]} -gt 0 ]]; then
      warn "Unknown platform selection: $(printf '%s, ' "${invalid_tokens[@]}" | sed 's/, $//')"
      continue
    fi

    if [[ ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]]; then
      warn "No valid platforms selected. Choose at least one optional platform."
      continue
    fi

    append_required_platform_packages
    return 0
  done
}

prompt_for_telemetry_preference() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose a telemetry level. You can change it later with 'skill-bill telemetry set-level'."
    printf "  1. anonymous (default) — aggregate counts, no content\n"
    printf "  2. full — includes finding details, learnings, rejection notes\n"
    printf "  3. off — no telemetry\n"
    printf "${CYAN}▸${NC} Enter telemetry level [1]: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|1|anonymous)
        TELEMETRY_LEVEL="anonymous"
        return 0
        ;;
      2|full)
        TELEMETRY_LEVEL="full"
        return 0
        ;;
      3|off)
        TELEMETRY_LEVEL="off"
        return 0
        ;;
      *)
        warn "Enter 1, 2, 3, anonymous, full, off, or press Enter for the default."
        ;;
    esac
  done
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
  done < <(
    {
      find "$SKILLS_DIR" -type f -name 'SKILL.md'
      if [[ -d "$PLATFORM_PACKS_DIR" ]]; then
        find "$PLATFORM_PACKS_DIR" -type f -name 'SKILL.md'
      fi
    } | sort
  )
}

resolve_package_name_for_skill_path() {
  # Map a skill directory to its owning package name.
  # - skills/<skill>/SKILL.md                               -> base
  # - skills/<pkg>/<skill>/SKILL.md                         -> <pkg>
  # - platform-packs/<slug>/code-review/<skill>/SKILL.md    -> <slug>
  # - platform-packs/<slug>/quality-check/<skill>/SKILL.md  -> <slug>
  # Falls back to the immediate parent directory name for any other shape.
  local skill_dir="$1"
  local parent parent_parent parent_name grand skill_name
  parent="$(dirname "$skill_dir")"
  parent_parent="$(dirname "$parent")"
  parent_name="$(basename "$parent")"
  grand="$(basename "$parent_parent")"
  skill_name="$(basename "$skill_dir")"

  if [[ "$parent" == "$SKILLS_DIR" && "$skill_name" == bill-* ]]; then
    printf 'base'
    return 0
  fi

  if [[ ( "$parent_name" == "code-review" || "$parent_name" == "quality-check" ) && -d "$parent_parent" ]]; then
    # platform-packs/<slug>/{code-review,quality-check}/<skill>
    printf '%s' "$grand"
    return 0
  fi

  printf '%s' "$parent_name"
}

build_install_skill_names() {
  local idx
  local skill_dir
  local package_name
  local canonical_name

  INSTALL_SKILL_NAMES=()
  INSTALL_SKILL_PATHS=()

  for idx in "${!SKILL_NAMES[@]}"; do
    skill_dir="${SKILL_PATHS[$idx]}"
    package_name="$(resolve_package_name_for_skill_path "$skill_dir")"
    canonical_name="${SKILL_NAMES[$idx]}"

    if [[ "$package_name" == "base" ]] || array_contains "$package_name" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
      INSTALL_SKILL_NAMES+=("$canonical_name")
      INSTALL_SKILL_PATHS+=("$skill_dir")
    fi
  done
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
  add_legacy_name ".bill-shared"

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

    if remove_if_allowed "$legacy_target"; then
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

install_skill() {
  local target="$1"
  local source="$2"
  local label="$3"

  if [[ -e "$target" || -L "$target" ]]; then
    remove_if_allowed "$target"
  fi

  # SKILL-21: we symlink the entire skill directory, so every file inside it
  # — SKILL.md, content.md, and sibling supporting files — is visible to
  # every detected agent without per-file plumbing. Adding new files to a
  # governed skill (e.g. the v1.1 content.md sibling) needs no installer
  # changes.
  if command -v python3 >/dev/null 2>&1; then
    python3 -m skill_bill install link-skill \
      --source "$source" \
      --target-dir "$(dirname "$target")" \
      --agent manual
  else
    ln -s "$source" "$target"
  fi
  ok "  $label"
}

path_has_matching_skill_name() {
  local target="$1"
  local skill_file="$target/SKILL.md"
  local declared_name=""
  local expected_name

  [[ -d "$target" ]] || return 1
  [[ -f "$skill_file" ]] || return 1

  expected_name="$(basename "$target")"
  declared_name="$(sed -n 's/^name:[[:space:]]*//p' "$skill_file" | head -n 1)"
  [[ "$declared_name" == "$expected_name" ]]
}

parse_args "$@"
build_skill_names
build_legacy_skill_names
build_platform_packages

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, glm, codex, opencode"
info "Install behavior: replace existing Skill Bill installs and reinstall the selected platforms."
prompt_for_agent_selection
prompt_for_platform_selection
prompt_for_telemetry_preference
build_install_skill_names

echo ""
info "Plugin:  $PLUGIN_DIR"
info "Agents selected: $(format_agent_list "${AGENT_NAMES[@]}")"
info "Skills found: ${#SKILL_NAMES[@]}"
info "Skills selected: ${#INSTALL_SKILL_NAMES[@]} (base + $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}"))"
info "Telemetry:      $TELEMETRY_LEVEL"
echo ""

info "Removing existing Skill Bill installs before reinstalling the selected platforms."
bash "$PLUGIN_DIR/uninstall.sh"
echo ""

for i in "${!AGENT_NAMES[@]}"; do
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"

  mkdir -p "$agent_dir"
  info "Installing to $agent: $agent_dir"
  remove_legacy_skill_paths "$agent_dir"

  for idx in "${!INSTALL_SKILL_NAMES[@]}"; do
    skill="${INSTALL_SKILL_NAMES[$idx]}"
    install_skill "$agent_dir/$skill" "${INSTALL_SKILL_PATHS[$idx]}" "$skill → plugin"
  done

  if [[ "$agent" == "codex" ]]; then
    install_codex_agents_tomls
  fi
  echo ""
done

SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
export SKILL_BILL_CONFIG_PATH="${SKILL_BILL_CONFIG_PATH:-${SKILL_BILL_STATE_DIR}/config.json}"
export SKILL_BILL_REVIEW_DB="${SKILL_BILL_REVIEW_DB:-${SKILL_BILL_STATE_DIR}/review-metrics.db}"

find_python_310_plus() {
  local candidate
  for candidate in python3.13 python3.12 python3.11 python3.10 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then
      if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
        echo "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

ensure_skill_bill_venv() {
  local venv_dir="$PLUGIN_DIR/.venv"
  local venv_python="$venv_dir/bin/python"
  local system_python
  if ! system_python="$(find_python_310_plus)"; then
    return 1
  fi
  if [ -x "$venv_python" ] && "$venv_python" -c 'import mcp' >/dev/null 2>&1; then
    echo "$venv_python"
    return 0
  fi
  if [ -d "$venv_dir" ] && [ ! -x "$venv_python" ]; then
    rm -rf "$venv_dir"
  fi
  if [ ! -d "$venv_dir" ]; then
    if ! "$system_python" -m venv "$venv_dir" >/dev/null 2>&1; then
      return 1
    fi
  fi
  "$venv_python" -m pip install --quiet --upgrade pip >/dev/null 2>&1 || true
  if ! "$venv_python" -m pip install --quiet -e "$PLUGIN_DIR" >/dev/null 2>&1; then
    return 1
  fi
  echo "$venv_python"
  return 0
}

locate_packaged_runtime_bin() {
  local path="$1"
  local label="$2"
  if [[ ! -x "$path" ]]; then
    err "Missing packaged Kotlin $label runtime: $path"
    return 1
  fi
}

build_kotlin_runtime_distributions() {
  # Installed runtime path: Gradle application installDist bin scripts at:
  # runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli
  # runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp
  # Development fallback: point SKILL_BILL_KOTLIN_CLI or
  # SKILL_BILL_KOTLIN_MCP at a local command such as a Gradle run wrapper.
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    warn "Skipping packaged Kotlin runtime distribution build because SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1."
    return 0
  fi

  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"
  if [[ ! -x "$gradlew" ]]; then
    err "Missing Gradle wrapper: $gradlew"
    return 1
  fi

  info "Building packaged Kotlin runtime distributions..."
  (
    cd "$RUNTIME_KOTLIN_DIR"
    ./gradlew -q :runtime-cli:installDist :runtime-mcp:installDist
  )
  locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI"
  locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP"
  ok "Kotlin runtime distributions ready"
}

configure_telemetry_level() {
  local level="$1"
  python3 - "$level" "$SKILL_BILL_REVIEW_DB" <<'PY'
from __future__ import annotations

from pathlib import Path
import sys

from skill_bill.config import set_telemetry_level

level = sys.argv[1]
db_path = Path(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2] else None
set_telemetry_level(level, db_path=db_path)
PY
}

if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
  build_kotlin_runtime_distributions
  info "Installing skill-bill CLI and MCP server..."
  if SKILL_BILL_PYTHON="$(ensure_skill_bill_venv)"; then
    ok "skill-bill CLI installed"
    register_mcp_json() {
      local config_path="$1"
      local label="$2"
      if "$SKILL_BILL_PYTHON" -c "
import json, sys, os
path = sys.argv[1]
try:
    settings = json.loads(open(path).read())
except (FileNotFoundError, json.JSONDecodeError):
    settings = {}
servers = settings.get('mcpServers', {})
servers['skill-bill'] = {
    'type': 'stdio',
    'command': sys.executable,
    'args': ['-c', 'from skill_bill.launcher import mcp_main; mcp_main()']
}
# MCP registration intentionally calls the Python launcher shim until that
# entrypoint is retired. The launcher resolves default MCP execution to the
# packaged runtime-mcp bin script above.
settings['mcpServers'] = servers
os.makedirs(os.path.dirname(path), exist_ok=True)
open(path, 'w').write(json.dumps(settings, indent=2, sort_keys=True) + '\n')
" "$config_path" 2>/dev/null; then
        ok "  skill-bill MCP server registered ($label)"
      else
        warn "  Could not register MCP server ($label)."
      fi
    }
    register_mcp_toml() {
      local config_path="$1"
      local label="$2"
      if "$SKILL_BILL_PYTHON" -c "
import sys, os
path = sys.argv[1]
python_cmd = sys.executable
section = '[mcp_servers.skill-bill]'
lines = []
if os.path.exists(path):
    lines = open(path).read().splitlines()
filtered = []
skip = False
for line in lines:
    if line.strip() == section:
        skip = True
        continue
    if skip and (line.startswith('[') or not line.strip()):
        if line.startswith('['):
            skip = False
            filtered.append(line)
        continue
    if not skip:
        filtered.append(line)
while filtered and not filtered[-1].strip():
    filtered.pop()
filtered.append('')
filtered.append(section)
filtered.append(f'command = \"{python_cmd}\"')
filtered.append('args = [\"-c\", \"from skill_bill.launcher import mcp_main; mcp_main()\"]')
# MCP registration intentionally calls the Python launcher shim until that
# entrypoint is retired. The launcher resolves default MCP execution to the
# packaged runtime-mcp bin script above.
filtered.append('')
os.makedirs(os.path.dirname(path), exist_ok=True)
open(path, 'w').write('\n'.join(filtered))
" "$config_path" 2>/dev/null; then
        ok "  skill-bill MCP server registered ($label)"
      else
        warn "  Could not register MCP server ($label)."
      fi
    }
    register_mcp_jsonc_opencode() {
      local config_path="$1"
      local label="$2"
      if "$SKILL_BILL_PYTHON" -c "
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
    settings = {}
else:
    if raw.strip():
        settings = json.loads(strip_trailing_commas(strip_jsonc(raw)))
    else:
        settings = {}

if not isinstance(settings, dict):
    sys.exit(1)

mcp = settings.get('mcp', {})
if not isinstance(mcp, dict):
    mcp = {}
mcp['skill-bill'] = {
    'type': 'local',
    'command': [sys.executable, '-c', 'from skill_bill.launcher import mcp_main; mcp_main()'],
    'enabled': True,
}
# MCP registration intentionally calls the Python launcher shim until that
# entrypoint is retired. The launcher resolves default MCP execution to the
# packaged runtime-mcp bin script above.
settings['mcp'] = mcp
os.makedirs(os.path.dirname(path), exist_ok=True)
open(path, 'w').write(json.dumps(settings, indent=2, sort_keys=True) + '\n')
" "$config_path" 2>/dev/null; then
        ok "  skill-bill MCP server registered ($label)"
      else
        warn "  Could not register MCP server ($label)."
      fi
    }
    for i in "${!AGENT_NAMES[@]}"; do
      case "${AGENT_NAMES[$i]}" in
        claude)  register_mcp_json "$HOME/.claude.json" "claude" ;;
        copilot) register_mcp_json "$HOME/.copilot/mcp-config.json" "copilot" ;;
        codex)   register_mcp_toml "$HOME/.codex/config.toml" "codex" ;;
        glm)     register_mcp_json "$HOME/.glm/mcp-config.json" "glm" ;;
        opencode) register_mcp_jsonc_opencode "$HOME/.config/opencode/opencode.json" "opencode" ;;
      esac
    done
  else
    warn "Could not install skill-bill CLI (python3 or pip may be unavailable)."
  fi
fi

if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
  if ! configure_telemetry_level "$TELEMETRY_LEVEL" >/dev/null 2>&1; then
    warn "Telemetry setup failed."
    TELEMETRY_LEVEL="setup_failed"
  fi
elif [[ -e "$SKILL_BILL_CONFIG_PATH" || -e "$SKILL_BILL_REVIEW_DB" ]]; then
  configure_telemetry_level "off" >/dev/null 2>&1 || warn "Telemetry setup failed."
fi

printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Platforms:       $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}")"
if [[ "$TELEMETRY_LEVEL" == "setup_failed" ]]; then
  info "Telemetry:       setup failed (python3 may be unavailable)"
else
  info "Telemetry:       $TELEMETRY_LEVEL"
fi
for i in "${!AGENT_NAMES[@]}"; do
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"
  info "Installed agent: $agent → $agent_dir"
done

echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
if [[ "$TELEMETRY_LEVEL" != "off" && "$TELEMETRY_LEVEL" != "setup_failed" ]]; then
  info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
fi
info "Run './install.sh' again to reinstall with a different agent or platform selection."
echo ""
