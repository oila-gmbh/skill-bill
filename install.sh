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
declare -a PLATFORM_PACKAGES=()
declare -a SELECTED_PLATFORM_PACKAGES=()
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

set_primary_agent() {
  local target="$1"
  local reordered_names=()
  local reordered_paths=()
  local idx

  for idx in "${!AGENT_NAMES[@]}"; do
    if [[ "${AGENT_NAMES[$idx]}" == "$target" ]]; then
      reordered_names+=("${AGENT_NAMES[$idx]}")
      reordered_paths+=("${AGENT_PATHS[$idx]}")
    fi
  done

  for idx in "${!AGENT_NAMES[@]}"; do
    if [[ "${AGENT_NAMES[$idx]}" != "$target" ]]; then
      reordered_names+=("${AGENT_NAMES[$idx]}")
      reordered_paths+=("${AGENT_PATHS[$idx]}")
    fi
  done

  AGENT_NAMES=("${reordered_names[@]}")
  AGENT_PATHS=("${reordered_paths[@]}")
  PRIMARY="${AGENT_NAMES[0]}"
  PRIMARY_DIR="${AGENT_PATHS[0]}"
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

prompt_for_primary_agent() {
  local input
  local normalized
  local idx

  if [[ ${#AGENT_NAMES[@]} -eq 1 ]]; then
    PRIMARY="${AGENT_NAMES[0]}"
    PRIMARY_DIR="${AGENT_PATHS[0]}"
    return 0
  fi

  while true; do
    echo ""
    info "Selected agents: $(format_agent_list "${AGENT_NAMES[@]}")"
    info "Choose the primary agent:"
    for idx in "${!AGENT_NAMES[@]}"; do
      printf "  %s. %s\n" "$((idx + 1))" "${AGENT_NAMES[$idx]}"
    done
    printf "${CYAN}▸${NC} Enter primary agent: "
    read -r input
    input="$(trim_string "$input")"

    if [[ -z "$input" ]]; then
      warn "No primary agent provided. Choose one of the selected agents."
      continue
    fi

    if [[ "$input" =~ ^[0-9]+$ ]]; then
      idx=$((input - 1))
      if (( idx >= 0 && idx < ${#AGENT_NAMES[@]} )); then
        set_primary_agent "${AGENT_NAMES[$idx]}"
        return 0
      fi
      warn "Unknown primary selection: $input"
      continue
    fi

    normalized="$(normalize_agent_token "$input")"
    for idx in "${!AGENT_NAMES[@]}"; do
      if [[ "$normalized" == "${AGENT_NAMES[$idx]}" ]]; then
        set_primary_agent "${AGENT_NAMES[$idx]}"
        return 0
      fi
    done

    warn "Unknown primary selection: $input"
  done
}

display_platform_name() {
  case "$1" in
    backend-kotlin) printf 'Kotlin backend' ;;
    kotlin) printf 'Kotlin' ;;
    kmp) printf 'KMP' ;;
    php) printf 'PHP' ;;
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
  for package in backend-kotlin kotlin kmp php; do
    if array_contains "$package" "${discovered[@]:-}"; then
      PLATFORM_PACKAGES+=("$package")
    fi
  done

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
    backendkotlin|kotlinbackend)
      printf 'backend-kotlin\n'
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
    return 0
  fi

  while true; do
    echo ""
    info "Available platforms:"
    for i in "${!PLATFORM_PACKAGES[@]}"; do
      package="${PLATFORM_PACKAGES[$i]}"
      printf "  %s. %s (%s)\n" "$((i + 1))" "$(display_platform_name "$package")" "$package"
    done
    option_number=$(( ${#PLATFORM_PACKAGES[@]} + 1 ))
    printf "  %s. all (install every platform package)\n" "$option_number"
    info "Base skills are always installed."
    info "Choose one or more platform options (comma-separated)."
    printf "${CYAN}▸${NC} Enter platforms (e.g. Kotlin backend, Kotlin, KMP or PHP): "
    read -r input

    if [[ -z "$(trim_string "$input")" ]]; then
      warn "No platforms provided. Choose at least one platform."
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
      warn "No valid platforms selected. Choose at least one platform."
      continue
    fi

    return 0
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

  INSTALL_SKILL_NAMES=()
  INSTALL_SKILL_PATHS=()

  for idx in "${!SKILL_NAMES[@]}"; do
    skill_dir="${SKILL_PATHS[$idx]}"
    package_name="$(basename "$(dirname "$skill_dir")")"

    if [[ "$package_name" == "base" ]] || array_contains "$package_name" "${SELECTED_PLATFORM_PACKAGES[@]:-}"; then
      INSTALL_SKILL_NAMES+=("${SKILL_NAMES[$idx]}")
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
build_platform_packages

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, glm, codex"
info "Install mode: $MODE"
if [[ "$MODE" == safe ]]; then
  info "Safe mode replaces symlinks, migrates legacy plugin installs, and skips local directory/file conflicts."
elif [[ "$MODE" == override ]]; then
  info "Override mode runs the uninstaller first, then reinstalls only the selected platforms."
fi
prompt_for_agent_selection
prompt_for_primary_agent
prompt_for_platform_selection
build_install_skill_names

echo ""
info "Plugin:  $PLUGIN_DIR"
info "Primary: $PRIMARY ($PRIMARY_DIR)"
info "Agents selected: $(format_agent_list "${AGENT_NAMES[@]}")"
info "Skills found: ${#SKILL_NAMES[@]}"
info "Skills selected: ${#INSTALL_SKILL_NAMES[@]} (base + $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}"))"
echo ""

if [[ "$MODE" == override ]]; then
  info "Override mode removes existing Skill Bill symlinks before reinstalling the selected platforms."
  bash "$PLUGIN_DIR/uninstall.sh"
  echo ""
fi

mkdir -p "$PRIMARY_DIR"
info "Installing to primary ($PRIMARY): $PRIMARY_DIR"
if [[ "$MODE" != override ]]; then
  remove_legacy_skill_paths "$PRIMARY_DIR"
fi
for idx in "${!INSTALL_SKILL_NAMES[@]}"; do
  skill="${INSTALL_SKILL_NAMES[$idx]}"
  install_skill_link "$PRIMARY_DIR/$skill" "${INSTALL_SKILL_PATHS[$idx]}" "$skill → plugin"
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
  if [[ "$MODE" != override ]]; then
    remove_legacy_skill_paths "$agent_dir"
  fi

  for skill in "${INSTALL_SKILL_NAMES[@]}"; do
    install_skill_link "$agent_dir/$skill" "$PRIMARY_DIR/$skill" "$skill"
  done
  echo ""
done

printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Primary agent:   $PRIMARY → $PRIMARY_DIR"
info "Platforms:       $(format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}")"
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
info "Run './install.sh --mode safe' again to add missing platform packages."
echo ""
