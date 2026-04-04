#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
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

declare -a SUPPORTED_AGENTS=(copilot claude glm codex)
declare -a SKILL_NAMES=()
declare -a SKILL_PATHS=()
declare -a INSTALL_SKILL_NAMES=()
declare -a INSTALL_SKILL_PATHS=()
declare -a INSTALL_TARGET_NAMES=()
declare -a PLATFORM_PACKAGES=()
declare -a REQUIRED_PLATFORM_PACKAGES=(agent-config)
declare -a SELECTED_PLATFORM_PACKAGES=()
declare -a LEGACY_SKILL_NAMES=()
INSTALL_PREFIX="bill"
TELEMETRY_ENABLED="true"

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

normalize_prefix_token() {
  local value
  value="$(trim_string "$1")"
  value="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  value="${value#/}"
  while [[ "$value" == *- ]]; do
    value="${value%-}"
  done
  printf '%s' "$value"
}

normalize_platform_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_/-'
}

normalize_agent_token() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]'
}

is_valid_prefix() {
  [[ "$1" =~ ^[a-z][a-z0-9-]*$ ]]
}

alias_skill_name() {
  local canonical_name="$1"

  if [[ "$INSTALL_PREFIX" == "bill" ]] || [[ "$canonical_name" != bill-* ]]; then
    printf '%s' "$canonical_name"
    return 0
  fi

  printf '%s-%s' "$INSTALL_PREFIX" "${canonical_name#bill-}"
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
    agent-config) printf 'Agent config' ;;
    backend-kotlin) printf 'Kotlin backend' ;;
    kotlin) printf 'Kotlin' ;;
    kmp) printf 'KMP' ;;
    php) printf 'PHP' ;;
    go) printf 'Go' ;;
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
    [[ "$package" == "base" ]] && continue
    discovered+=("$package")
  done < <(find "$SKILLS_DIR" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)

  PLATFORM_PACKAGES=()
  for package in backend-kotlin kotlin kmp php go; do
    if array_contains "$package" "${discovered[@]:-}"; then
      PLATFORM_PACKAGES+=("$package")
    fi
  done

  for package in "${discovered[@]:-}"; do
    if array_contains "$package" "${REQUIRED_PLATFORM_PACKAGES[@]:-}"; then
      continue
    fi
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
    backendkotlin|kotlinbackend)
      printf 'backend-kotlin\n'
      return 0
      ;;
    agentconfig|skillrepo|skillsinfra)
      printf '__deprecated_agent_config__\n'
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
    php)
      printf 'php\n'
      return 0
      ;;
    go|golang)
      printf 'go\n'
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
    if [[ -d "$SKILLS_DIR/$package" ]] && ! array_contains "$package" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
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
    info "Base skills and Agent config skills are always installed."
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
      if [[ "$resolved" == "__deprecated_agent_config__" ]]; then
        info "Agent config is now always installed. The '$token' alias is no longer needed."
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

prompt_for_skill_prefix() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose the user-facing skill prefix."
    info "Press Enter to keep the default prefix: bill"
    printf "${CYAN}▸${NC} Enter prefix: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(normalize_prefix_token "$input")"
    if [[ -z "$normalized" ]]; then
      INSTALL_PREFIX="bill"
      return 0
    fi

    if ! is_valid_prefix "$normalized"; then
      warn "Prefix must start with a letter and use only lowercase letters, digits, or hyphens."
      continue
    fi

    INSTALL_PREFIX="$normalized"
    return 0
  done
}

prompt_for_telemetry_preference() {
  local input
  local normalized

  while true; do
    echo ""
    info "Anonymous telemetry can stay enabled locally and sync through the hosted relay by default or a custom proxy override."
    info "Default: enabled. You can opt out now or later."
    printf "${CYAN}▸${NC} Enable telemetry by default? [Y/n]: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|y|yes)
        TELEMETRY_ENABLED="true"
        return 0
        ;;
      n|no)
        TELEMETRY_ENABLED="false"
        return 0
        ;;
      *)
        warn "Enter y, yes, n, no, or press Enter for the default."
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
  done < <(find "$SKILLS_DIR" -type f -name 'SKILL.md' | sort)
}

build_install_skill_names() {
  local idx
  local skill_dir
  local package_name
  local canonical_name

  INSTALL_SKILL_NAMES=()
  INSTALL_SKILL_PATHS=()
  INSTALL_TARGET_NAMES=()

  for idx in "${!SKILL_NAMES[@]}"; do
    skill_dir="${SKILL_PATHS[$idx]}"
    package_name="$(basename "$(dirname "$skill_dir")")"
    canonical_name="${SKILL_NAMES[$idx]}"

    if [[ "$package_name" == "base" ]] || array_contains "$package_name" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
      INSTALL_SKILL_NAMES+=("$canonical_name")
      INSTALL_SKILL_PATHS+=("$skill_dir")
      INSTALL_TARGET_NAMES+=("$(alias_skill_name "$canonical_name")")
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

install_skill_link() {
  local target="$1"
  local source="$2"
  local label="$3"

  if [[ -e "$target" || -L "$target" ]]; then
      remove_if_allowed "$target"
    fi

  ln -s "$source" "$target"
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

rewrite_markdown_file() {
  local source_file="$1"
  local target_file="$2"

  if [[ "$INSTALL_PREFIX" == "bill" ]]; then
    cp "$source_file" "$target_file"
    return 0
  fi

  SKILL_BILL_INSTALL_PREFIX="$INSTALL_PREFIX" perl -0pe \
    's/\bbill-([a-z0-9-]+)/$ENV{"SKILL_BILL_INSTALL_PREFIX"} . "-" . $1/ge' \
    "$source_file" > "$target_file"
}

install_generated_skill_dir() {
  local target="$1"
  local source="$2"
  local label="$3"
  local source_path
  local relative_path
  local target_path

  if [[ -e "$target" || -L "$target" ]]; then
    remove_if_allowed "$target"
  fi

  mkdir -p "$target"
  {
    printf 'managed_by=skill-bill\n'
    printf 'canonical_name=%s\n' "$(basename "$source")"
    printf 'installed_name=%s\n' "$(basename "$target")"
    printf 'prefix=%s\n' "$INSTALL_PREFIX"
  } > "$target/$MANAGED_INSTALL_MARKER"

  while IFS= read -r source_path; do
    relative_path="${source_path#$source/}"
    target_path="$target/$relative_path"

    if [[ -d "$source_path" ]]; then
      mkdir -p "$target_path"
      continue
    fi

    mkdir -p "$(dirname "$target_path")"
    if [[ "$source_path" == *.md ]]; then
      rewrite_markdown_file "$source_path" "$target_path"
    else
      cp "$source_path" "$target_path"
    fi
  done < <(find "$source" -mindepth 1 | sort)

  ok "  $label"
}

install_skill() {
  local target="$1"
  local source="$2"
  local label="$3"

  if [[ "$INSTALL_PREFIX" == "bill" ]]; then
    install_skill_link "$target" "$source" "$label"
  else
    install_generated_skill_dir "$target" "$source" "$label"
  fi
}

parse_args "$@"
build_skill_names
build_legacy_skill_names
build_platform_packages

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, glm, codex"
info "Install behavior: replace existing Skill Bill installs and reinstall the selected platforms."
prompt_for_agent_selection
prompt_for_platform_selection
prompt_for_skill_prefix
prompt_for_telemetry_preference
build_install_skill_names

echo ""
info "Plugin:  $PLUGIN_DIR"
info "Agents selected: $(format_agent_list "${AGENT_NAMES[@]}")"
info "Skills found: ${#SKILL_NAMES[@]}"
info "Skills selected: ${#INSTALL_SKILL_NAMES[@]} (base + $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}"))"
info "Command prefix: ${INSTALL_PREFIX}-"
info "Telemetry:      $([[ "$TELEMETRY_ENABLED" == "true" ]] && printf 'enabled' || printf 'disabled')"
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
    target_skill="${INSTALL_TARGET_NAMES[$idx]}"
    install_skill "$agent_dir/$target_skill" "${INSTALL_SKILL_PATHS[$idx]}" "$target_skill → plugin ($skill)"
  done
  echo ""
done

SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
export SKILL_BILL_CONFIG_PATH="${SKILL_BILL_CONFIG_PATH:-${SKILL_BILL_STATE_DIR}/config.json}"
export SKILL_BILL_REVIEW_DB="${SKILL_BILL_REVIEW_DB:-${SKILL_BILL_STATE_DIR}/review-metrics.db}"

if [[ "$TELEMETRY_ENABLED" == "true" ]]; then
  if ! python3 "$PLUGIN_DIR/scripts/review_metrics.py" telemetry enable --format json >/dev/null; then
    warn "Telemetry setup failed."
    TELEMETRY_ENABLED="setup_failed"
  fi
elif [[ -e "$SKILL_BILL_CONFIG_PATH" || -e "$SKILL_BILL_REVIEW_DB" ]]; then
  python3 "$PLUGIN_DIR/scripts/review_metrics.py" telemetry disable --format json >/dev/null || warn "Telemetry setup failed."
fi

printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Platforms:       $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}")"
info "Command prefix:  ${INSTALL_PREFIX}-"
if [[ "$TELEMETRY_ENABLED" == "true" ]]; then
  info "Telemetry:       enabled by default"
elif [[ "$TELEMETRY_ENABLED" == "setup_failed" ]]; then
  info "Telemetry:       setup failed (python3 may be unavailable)"
else
  info "Telemetry:       disabled"
fi
for i in "${!AGENT_NAMES[@]}"; do
  agent="${AGENT_NAMES[$i]}"
  agent_dir="${AGENT_PATHS[$i]}"
  info "Installed agent: $agent → $agent_dir"
done

echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
if [[ "$INSTALL_PREFIX" != "bill" ]]; then
  info "Custom prefixes install generated alias copies. Re-run './install.sh' after editing skills."
fi
info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
info "Run './install.sh' again to reinstall with a different agent or platform selection."
echo ""
