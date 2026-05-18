#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_DIR="$PLUGIN_DIR/skills"
PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"
RUNTIME_KOTLIN_DIR="$PLUGIN_DIR/runtime-kotlin"
RUNTIME_CLI_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
RUNTIME_MCP_BUILD_BIN="$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp"
SKILL_BILL_STATE_DIR="${HOME}/.skill-bill"
RUNTIME_INSTALL_ROOT="${SKILL_BILL_RUNTIME_DIR:-$SKILL_BILL_STATE_DIR/runtime}"
RUNTIME_CLI_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-cli"
RUNTIME_MCP_INSTALL_DIR="$RUNTIME_INSTALL_ROOT/runtime-mcp"
RUNTIME_CLI_BIN="$RUNTIME_CLI_INSTALL_DIR/bin/runtime-cli"
RUNTIME_MCP_BIN="$RUNTIME_MCP_INSTALL_DIR/bin/runtime-mcp"
RUNTIME_LAUNCHER_BIN_DIR="${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}"
DESKTOP_APP_DEFAULT_NAME="SkillBill"
DESKTOP_APP_INSTALL="prompt"
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

declare -a SUPPORTED_AGENTS=(copilot claude codex opencode junie)
declare -a AGENT_NAMES=()
declare -a AGENT_PATHS=()
declare -a PLATFORM_PACKAGES=()
declare -a SELECTED_PLATFORM_PACKAGES=()
declare -a RUNTIME_INSTALL_ARGS=()

AGENT_SELECTION_MODE="manual"
PLATFORM_SELECTION_MODE="none"
TELEMETRY_LEVEL="anonymous"
MCP_REGISTRATION="register"

usage() {
  cat <<USAGE
Usage: ./install.sh [--with-desktop-app|--no-desktop-app] [--desktop-app-dir PATH]

Options:
  --with-desktop-app       Build and install the optional desktop app.
  --no-desktop-app         Skip desktop app installation.
  --desktop-app-dir PATH   Override the per-user desktop app install directory.
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --with-desktop-app|--install-desktop-app)
        DESKTOP_APP_INSTALL="yes"
        shift
        ;;
      --no-desktop-app|--skip-desktop-app)
        DESKTOP_APP_INSTALL="no"
        shift
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
      printf '%s' "$HOME/Applications"
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

prompt_for_desktop_app_install() {
  local input
  local normalized

  if [[ "$DESKTOP_APP_INSTALL" != "prompt" ]]; then
    return 0
  fi

  while true; do
    echo ""
    info "Install the optional Skill Bill desktop app for this user?"
    printf "  1. install (default)\n"
    printf "  2. skip\n"
    printf "${CYAN}▸${NC} Enter desktop app choice [1]: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|1|install|yes|y)
        DESKTOP_APP_INSTALL="yes"
        return 0
        ;;
      2|skip|no|n)
        DESKTOP_APP_INSTALL="no"
        return 0
        ;;
      *)
        warn "Enter 1, 2, install, skip, or press Enter for install."
        ;;
    esac
  done
}

# Every install starts from a clean slate: removes agent symlinks, native
# subagent symlinks, MCP registrations, runtime launchers, and wipes
# ~/.skill-bill/ (including the installed-skills staging cache, runtime
# binaries, and any persistent state DBs). This guarantees that generator
# changes — which the staging-cache content hash does not see — actually
# land on the next install.
#
# Tests and dev iteration can opt out with SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1.
run_pre_install_uninstall() {
  if [[ "${SKILL_BILL_SKIP_PREINSTALL_UNINSTALL:-}" == "1" ]]; then
    warn "Skipping pre-install uninstall because SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1."
    return 0
  fi
  local uninstall_script="$PLUGIN_DIR/uninstall.sh"
  if [[ ! -x "$uninstall_script" ]]; then
    err "Cannot run pre-install cleanup: $uninstall_script is missing or not executable."
    exit 1
  fi
  echo ""
  printf "${CYAN}━━━ Pre-install cleanup ━━━${NC}\n"
  echo ""
  info "Running uninstall.sh first so every install starts from a clean slate."
  bash "$uninstall_script"
}

locate_packaged_runtime_bin() {
  local path="$1"
  local label="$2"
  if [[ ! -x "$path" ]]; then
    err "Missing packaged Kotlin $label runtime: $path"
    return 1
  fi
}

install_packaged_runtime_distribution() {
  local source_dir="$1"
  local target_dir="$2"
  local label="$3"
  local tmp_dir="$target_dir.tmp"

  if [[ ! -d "$source_dir" ]]; then
    err "Missing packaged Kotlin $label distribution: $source_dir"
    return 1
  fi

  rm -rf "$tmp_dir"
  mkdir -p "$(dirname "$target_dir")"
  cp -R "$source_dir" "$tmp_dir"
  rm -rf "$target_dir"
  mv "$tmp_dir" "$target_dir"
}

install_packaged_runtime_distributions() {
  info "Installing packaged Kotlin runtime to: $RUNTIME_INSTALL_ROOT"
  install_packaged_runtime_distribution \
    "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" \
    "$RUNTIME_CLI_INSTALL_DIR" \
    "CLI"
  install_packaged_runtime_distribution \
    "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp" \
    "$RUNTIME_MCP_INSTALL_DIR" \
    "MCP"
  locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI"
  locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP"
  ok "Kotlin runtime installed"
}

build_kotlin_runtime_distributions() {
  # Build output path: Gradle application installDist bin scripts at:
  # runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli
  # runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp
  # Install path: durable copied distributions under ~/.skill-bill/runtime/.
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    warn "Skipping packaged Kotlin runtime distribution build because SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1."
    if [[ -x "$RUNTIME_CLI_BUILD_BIN" && -x "$RUNTIME_MCP_BUILD_BIN" ]]; then
      install_packaged_runtime_distributions
    else
      locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI"
      locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP"
    fi
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
  locate_packaged_runtime_bin "$RUNTIME_CLI_BUILD_BIN" "CLI"
  locate_packaged_runtime_bin "$RUNTIME_MCP_BUILD_BIN" "MCP"
  ok "Kotlin runtime distributions ready"
  install_packaged_runtime_distributions
}

run_runtime_cli() {
  "$RUNTIME_CLI_BIN" --home "$HOME" "$@"
}

path_contains_dir() {
  local candidate="$1"
  case ":${PATH:-}:" in
    *":$candidate:"*) return 0 ;;
    *) return 1 ;;
  esac
}

install_runtime_launcher() {
  local name="$1"
  local target="$2"
  local link_path="$RUNTIME_LAUNCHER_BIN_DIR/$name"

  if [[ -e "$link_path" && ! -L "$link_path" ]]; then
    warn "  skipped $link_path (exists and is not a symlink)"
    return 0
  fi

  ln -sfn "$target" "$link_path"
  ok "  linked $name → $target"
}

install_runtime_launchers() {
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  info "Installing runtime launchers to: $RUNTIME_LAUNCHER_BIN_DIR"
  install_runtime_launcher "skill-bill" "$RUNTIME_CLI_BIN"
  install_runtime_launcher "skill-bill-mcp" "$RUNTIME_MCP_BIN"

  if path_contains_dir "$RUNTIME_LAUNCHER_BIN_DIR"; then
    ok "  launcher directory is on PATH"
  else
    warn "  launcher directory is not on PATH: $RUNTIME_LAUNCHER_BIN_DIR"
    warn "  set SKILL_BILL_BIN_DIR to a PATH directory before running ./install.sh, or add this directory to PATH"
  fi
}

build_desktop_app_distribution() {
  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"

  if [[ "$DESKTOP_APP_INSTALL" != "yes" ]]; then
    return 0
  fi

  if [[ ! -x "$gradlew" ]]; then
    err "Missing Gradle wrapper: $gradlew"
    return 1
  fi

  info "Building desktop app distributable for the current host..."
  (
    cd "$RUNTIME_KOTLIN_DIR"
    ./gradlew -q :runtime-desktop:prepareDesktopAppDistributable
  )
  ok "Desktop app distributable ready"
}

desktop_app_source_path() {
  local app_root="$RUNTIME_KOTLIN_DIR/runtime-desktop/build/compose/binaries/main/app"
  local os="$1"

  if [[ "$os" == "macos" && -d "$app_root/$DESKTOP_APP_DEFAULT_NAME.app" ]]; then
    printf '%s' "$app_root/$DESKTOP_APP_DEFAULT_NAME.app"
    return 0
  fi

  printf '%s' "$app_root/$DESKTOP_APP_DEFAULT_NAME"
}

make_desktop_binaries_executable() {
  local app_path="$1"
  find "$app_path" -type f \( \
    -path '*/Contents/MacOS/*' -o \
    -path '*/bin/*' \
  \) -exec chmod u+x {} + 2>/dev/null || true
}

install_desktop_launcher() {
  local os="$1"
  local app_target="$2"
  local launcher_path
  local executable
  local windows_executable

  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  case "$os" in
    macos)
      launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
      executable="$app_target/Contents/MacOS/$DESKTOP_APP_DEFAULT_NAME"
      ;;
    windows)
      launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop.cmd"
      executable="$app_target/bin/$DESKTOP_APP_DEFAULT_NAME.bat"
      if command -v cygpath >/dev/null 2>&1; then
        windows_executable="$(cygpath -w "$executable")"
      else
        windows_executable="$executable"
      fi
      cat > "$launcher_path" <<CMD
@echo off
call "$windows_executable" %*
CMD
      ok "  linked desktop launcher → $launcher_path"
      return 0
      ;;
    *)
      launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
      executable="$app_target/bin/$DESKTOP_APP_DEFAULT_NAME"
      ;;
  esac

  if [[ ! -x "$executable" ]]; then
    warn "  skipped desktop launcher because executable is missing: $executable"
    return 0
  fi
  ln -sfn "$executable" "$launcher_path"
  ok "  linked desktop launcher → $executable"
}

install_linux_desktop_entry() {
  local app_target="$1"
  local desktop_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/skillbill.desktop"
  local icon_file="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/512x512/apps/skillbill.png"
  local executable="$app_target/bin/$DESKTOP_APP_DEFAULT_NAME"
  local icon_src="$RUNTIME_KOTLIN_DIR/runtime-desktop/icons/icon.png"

  if [[ ! -x "$executable" ]]; then
    warn "  skipped Linux desktop entry because executable is missing: $executable"
    return 0
  fi

  mkdir -p "$(dirname "$desktop_file")" "$(dirname "$icon_file")"
  cp "$icon_src" "$icon_file"
  cat > "$desktop_file" <<DESKTOP
[Desktop Entry]
Type=Application
Name=SkillBill
GenericName=SkillBill Desktop Runtime
Comment=Compose Desktop runtime for SkillBill
Exec=$executable
Icon=skillbill
Terminal=false
Categories=Development;IDE;
StartupWMClass=SkillBill
DESKTOP
  ok "  installed Linux desktop entry → $desktop_file"
}

install_desktop_app() {
  local os
  local source_path
  local install_root
  local target_path

  if [[ "$DESKTOP_APP_INSTALL" != "yes" ]]; then
    return 0
  fi

  os="$(desktop_host_os)"
  source_path="$(desktop_app_source_path "$os")"
  if [[ ! -d "$source_path" ]]; then
    err "Missing desktop app distributable: $source_path"
    return 1
  fi

  install_root="${DESKTOP_APP_INSTALL_DIR:-$(default_desktop_app_install_dir "$os")}"
  install_root="$(host_path "$install_root")"
  case "$os" in
    macos)
      target_path="$install_root/$DESKTOP_APP_DEFAULT_NAME.app"
      ;;
    *)
      target_path="$install_root/$DESKTOP_APP_DEFAULT_NAME"
      ;;
  esac

  info "Installing desktop app to: $target_path"
  rm -rf "$target_path"
  mkdir -p "$install_root"
  cp -R "$source_path" "$target_path"
  make_desktop_binaries_executable "$target_path"
  install_desktop_launcher "$os" "$target_path"
  if [[ "$os" == "linux" ]]; then
    install_linux_desktop_entry "$target_path"
  fi
  ok "Desktop app installed"
}

get_agent_path() {
  run_runtime_cli install agent-path "$1"
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

prompt_for_agent_mode() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose agent selection mode."
    printf "  1. manual - choose one or more supported agents\n"
    printf "  2. detected - let the runtime detect configured agents from your home directory\n"
    printf "${CYAN}▸${NC} Enter agent mode [1]: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      ""|1|manual)
        AGENT_SELECTION_MODE="manual"
        return 0
        ;;
      2|detected|auto)
        AGENT_SELECTION_MODE="detected"
        AGENT_NAMES=()
        AGENT_PATHS=()
        return 0
        ;;
      *)
        warn "Enter 1, 2, manual, detected, or press Enter for manual."
        ;;
    esac
  done
}

prompt_for_manual_agent_selection() {
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

prompt_for_agent_selection() {
  prompt_for_agent_mode
  if [[ "$AGENT_SELECTION_MODE" == "manual" ]]; then
    prompt_for_manual_agent_selection
  fi
}

display_platform_name() {
  local label="${1//-/ }"
  printf '%s' "$label"
}

build_platform_packages() {
  local pack_dir
  local package

  PLATFORM_PACKAGES=()
  if [[ ! -d "$PLATFORM_PACKS_DIR" ]]; then
    return 0
  fi

  while IFS= read -r pack_dir; do
    package="$(basename "$pack_dir")"
    if [[ ${#PLATFORM_PACKAGES[@]} -eq 0 ]] || ! array_contains "$package" "${PLATFORM_PACKAGES[@]}"; then
      PLATFORM_PACKAGES+=("$package")
    fi
  done < <(find "$PLATFORM_PACKS_DIR" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/platform.yaml' ';' -print | sort)
}

resolve_platform_selection() {
  local token="$1"
  local normalized
  local package
  local index
  local all_index
  local none_index

  token="$(trim_string "$token")"
  [[ -n "$token" ]] || return 1

  if [[ "$token" =~ ^[0-9]+$ ]]; then
    index=$((token - 1))
    if (( index >= 0 && index < ${#PLATFORM_PACKAGES[@]} )); then
      printf '%s\n' "${PLATFORM_PACKAGES[$index]}"
      return 0
    fi
    all_index=${#PLATFORM_PACKAGES[@]}
    none_index=$(( ${#PLATFORM_PACKAGES[@]} + 1 ))
    if (( index == all_index )); then
      printf '__all__\n'
      return 0
    fi
    if (( index == none_index )); then
      printf '__none__\n'
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
    none|base|baseskills|baseonly)
      printf '__none__\n'
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
  local none_option_number
  local package
  local token
  local resolved
  local invalid_tokens=()
  local raw_tokens=()

  if [[ ${#PLATFORM_PACKAGES[@]} -eq 0 ]]; then
    PLATFORM_SELECTION_MODE="none"
    SELECTED_PLATFORM_PACKAGES=()
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
    none_option_number=$(( ${#PLATFORM_PACKAGES[@]} + 2 ))
    printf "  %s. all (install every platform pack)\n" "$option_number"
    printf "  %s. base only (skip optional platform packs)\n" "$none_option_number"
    info "Base skills are always installed by the runtime plan."
    info "Optional platform packs are resolved by the runtime from platform-packs/ manifests."
    info "Choose one or more optional platform numbers (comma-separated). Names still work if you prefer them."
    printf "${CYAN}▸${NC} Enter platforms (e.g. 1,3 or %s): " "$option_number"
    read -r input

    if [[ -z "$(trim_string "$input")" ]]; then
      warn "No platforms provided. Choose a platform, all, or base only."
      continue
    fi

    PLATFORM_SELECTION_MODE="selected"
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
        PLATFORM_SELECTION_MODE="all"
        SELECTED_PLATFORM_PACKAGES=("${PLATFORM_PACKAGES[@]}")
        break
      fi
      if [[ "$resolved" == "__none__" ]]; then
        PLATFORM_SELECTION_MODE="none"
        SELECTED_PLATFORM_PACKAGES=()
        break
      fi
      if [[ ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]] || ! array_contains "$resolved" "${SELECTED_PLATFORM_PACKAGES[@]}"; then
        SELECTED_PLATFORM_PACKAGES+=("$resolved")
      fi
    done

    if [[ ${#invalid_tokens[@]} -gt 0 ]]; then
      warn "Unknown platform selection: $(printf '%s, ' "${invalid_tokens[@]}" | sed 's/, $//')"
      continue
    fi

    if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]]; then
      warn "No valid platforms selected. Choose a platform, all, or base only."
      continue
    fi

    return 0
  done
}

prompt_for_telemetry_preference() {
  local input
  local normalized

  while true; do
    echo ""
    info "Choose a telemetry level. You can change it later with the Skill Bill telemetry command."
    printf "  1. anonymous (default) - aggregate counts, no content\n"
    printf "  2. full - includes finding details, learnings, rejection notes\n"
    printf "  3. off - no telemetry\n"
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

build_runtime_install_args() {
  local i

  RUNTIME_INSTALL_ARGS=(
    install
    apply
    --repo-root "$PLUGIN_DIR"
    --skills "$SKILLS_DIR"
    --platform-packs "$PLATFORM_PACKS_DIR"
    --agent-mode "$AGENT_SELECTION_MODE"
    --platform-mode "$PLATFORM_SELECTION_MODE"
    --telemetry "$TELEMETRY_LEVEL"
    --mcp "$MCP_REGISTRATION"
    --replace-existing-skill-bill-links
    --runtime-install-root "$RUNTIME_INSTALL_ROOT"
    --runtime-cli-build-dir "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli"
    --runtime-mcp-build-dir "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp"
    --runtime-cli-install-dir "$RUNTIME_CLI_INSTALL_DIR"
    --runtime-mcp-install-dir "$RUNTIME_MCP_INSTALL_DIR"
    --runtime-launcher-bin-dir "$RUNTIME_LAUNCHER_BIN_DIR"
    --runtime-mcp-bin "$RUNTIME_MCP_BIN"
  )

  if [[ "$AGENT_SELECTION_MODE" == "manual" && ${#AGENT_NAMES[@]} -gt 0 ]]; then
    for i in "${!AGENT_NAMES[@]}"; do
      RUNTIME_INSTALL_ARGS+=(--agent "${AGENT_NAMES[$i]}")
      RUNTIME_INSTALL_ARGS+=(--agent-target "${AGENT_NAMES[$i]}=${AGENT_PATHS[$i]}")
    done
  fi

  if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -gt 0 ]]; then
    for i in "${!SELECTED_PLATFORM_PACKAGES[@]}"; do
      RUNTIME_INSTALL_ARGS+=(--platform "${SELECTED_PLATFORM_PACKAGES[$i]}")
    done
  fi
}

apply_runtime_install() {
  local status=0

  info "Applying install through the runtime plan/apply path."
  run_runtime_cli "${RUNTIME_INSTALL_ARGS[@]}" || status=$?
  if [[ "$status" -ne 0 ]]; then
    err "Runtime install apply failed."
    return "$status"
  fi
  ok "Runtime install apply completed"
}

selected_agent_label() {
  if [[ "$AGENT_SELECTION_MODE" == "detected" ]]; then
    printf 'runtime detection'
    return 0
  fi
  format_agent_list "${AGENT_NAMES[@]}"
}

selected_platform_label() {
  case "$PLATFORM_SELECTION_MODE" in
    all)
      printf 'all'
      ;;
    none)
      printf 'base only'
      ;;
    *)
      format_platform_list "${SELECTED_PLATFORM_PACKAGES[@]}"
      ;;
  esac
}

parse_args "$@"
run_pre_install_uninstall
build_kotlin_runtime_distributions
build_platform_packages

echo ""
printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
echo ""
info "Supported agents: copilot, claude, codex, opencode, junie"
info "Install behavior: collect choices, then delegate planning and apply to the Kotlin runtime."
prompt_for_agent_selection
prompt_for_platform_selection
prompt_for_telemetry_preference
prompt_for_desktop_app_install
install_runtime_launchers
build_desktop_app_distribution
build_runtime_install_args

echo ""
SELECTED_PLATFORM_LABEL="$(selected_platform_label)"
info "Plugin:         $PLUGIN_DIR"
info "Agents:         $(selected_agent_label)"
info "Platforms:      $SELECTED_PLATFORM_LABEL"
info "Telemetry:      $TELEMETRY_LEVEL"
info "MCP:            $MCP_REGISTRATION"
info "Desktop app:    $DESKTOP_APP_INSTALL"
echo ""

apply_runtime_install
install_desktop_app

printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
echo ""
info "Source of truth: $PLUGIN_DIR/skills/"
info "Staging cache:   $SKILL_BILL_STATE_DIR/installed-skills"
info "Platforms:       $SELECTED_PLATFORM_LABEL"
info "Launchers:       $RUNTIME_LAUNCHER_BIN_DIR/skill-bill, $RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
info "Telemetry:       $TELEMETRY_LEVEL"
info "MCP:             $MCP_REGISTRATION"
if [[ "$DESKTOP_APP_INSTALL" == "yes" ]]; then
  info "Desktop app:     installed for $(desktop_host_os)"
else
  info "Desktop app:     skipped"
fi

if [[ "$AGENT_SELECTION_MODE" == "manual" && ${#AGENT_NAMES[@]} -gt 0 ]]; then
  for i in "${!AGENT_NAMES[@]}"; do
    info "Installed agent: ${AGENT_NAMES[$i]} → ${AGENT_PATHS[$i]}"
  done
else
  info "Installed agents were resolved by runtime detection."
fi

echo ""
info "Edit skills in: $PLUGIN_DIR/skills/"
if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
  info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
fi
info "Run './install.sh' again to reinstall with different agent, platform, telemetry, or desktop app choices."
echo ""
