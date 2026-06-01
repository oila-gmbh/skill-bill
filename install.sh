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
DESKTOP_APP_INSTALLED_PATH=""
DESKTOP_APP_LAUNCHER_PATH=""
# When the prebuilt path extracts a desktop app payload it records the extracted
# app tree here; install_desktop_app honors it instead of the Gradle build dir.
DESKTOP_APP_PREBUILT_SOURCE_PATH=""

# Prebuilt-first install source. Default is to fetch + verify the prebuilt
# release artifacts; --from-source restores today's Gradle build path.
INSTALL_SOURCE="prebuilt"
RELEASE_TAG="${SKILL_BILL_RELEASE_TAG:-}"
DESKTOP_APP_ONLY=0
RELEASE_REPO="${SKILL_BILL_RELEASE_REPO:-Sermilion/skill-bill}"
# Offline / test overrides. When SKILL_BILL_RELEASE_DIR is set, assets are copied
# from that local directory (no network). When SKILL_BILL_RELEASE_BASE_URL is set,
# curl is pointed at that base URL (supports file://) instead of the GitHub API.
SKILL_BILL_RELEASE_DIR="${SKILL_BILL_RELEASE_DIR:-}"
SKILL_BILL_RELEASE_BASE_URL="${SKILL_BILL_RELEASE_BASE_URL:-}"

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
  err "Refusing to run install.sh during skill-bill goal-continuation."
  err "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  exit 64
fi

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
Usage: ./install.sh [--from-source] [--release TAG]
                    [--with-desktop-app|--no-desktop-app] [--desktop-app-dir PATH]
                    [--desktop-app-only]

By default the installer downloads and checksum-verifies the prebuilt runtime
images from the matching GitHub release — no JDK and no Gradle build required.

Options:
  --from-source            Build the runtime (and desktop app) from source with
                           Gradle instead of fetching prebuilt artifacts. Ignores
                           --release. Requires a JDK.
  --release TAG            Install a specific release tag instead of the latest
                           stable release. Ignored with --from-source.
  --with-desktop-app       Install the optional desktop app from the prebuilt
                           installer (non-interactive).
  --no-desktop-app         Skip desktop app installation (non-interactive).
  --desktop-app-dir PATH   Override the per-user desktop app install directory.
  --desktop-app-only       Install ONLY the prebuilt desktop app (skip the full
                           installer). Use this to add the desktop app later
                           without re-running the full install.
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
      --from-source)
        INSTALL_SOURCE="source"
        shift
        ;;
      --desktop-app-only)
        DESKTOP_APP_ONLY=1
        shift
        ;;
      --release)
        if [[ $# -lt 2 || -z "$(trim_string "$2")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
        RELEASE_TAG="$2"
        shift 2
        ;;
      --release=*)
        RELEASE_TAG="${1#--release=}"
        if [[ -z "$(trim_string "$RELEASE_TAG")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
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

# Prebuilt-path dependency gate. The prebuilt install needs only near-universal
# tools — NO JDK and NO gh CLI — so verify them up front and fail loudly with a
# single message listing everything missing. Skipped under --from-source, where
# Gradle (and its JDK) are the real dependencies.
check_prebuilt_dependencies() {
  local missing=()

  if ! command -v curl >/dev/null 2>&1; then
    missing+=("curl (to download release assets)")
  fi
  if ! command -v tar >/dev/null 2>&1; then
    missing+=("tar (to unpack runtime images)")
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    missing+=("unzip (to unpack .zip runtime images)")
  fi
  if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
    missing+=("shasum or sha256sum (to verify .sha256 checksums)")
  fi

  if [[ ${#missing[@]} -gt 0 ]]; then
    err "Cannot run the prebuilt install: missing required tools."
    local item
    for item in "${missing[@]}"; do
      err "  - $item"
    done
    err "Install the tools above, or re-run with --from-source to build from source instead."
    return 1
  fi
}

# Map `uname -m` to the canonical arch segment used in release asset names.
detect_host_arch() {
  local uname_m
  uname_m="$(uname -m 2>/dev/null || printf 'unknown')"
  case "$uname_m" in
    arm64|aarch64)
      printf 'arm64'
      ;;
    x86_64|amd64)
      printf 'x64'
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

# Canonical `<os>-<arch>` token (macos-arm64/macos-x64/windows-x64/linux-x64).
# Returns an explicit unsupported signal (empty stdout, exit 1) for any host that
# is not one of the four published targets so the prebuilt path can auto-fall back
# to --from-source.
HOST_TOKEN_UNSUPPORTED=""
host_token() {
  local os arch token
  os="$(desktop_host_os)"
  arch="$(detect_host_arch)"
  token="$os-$arch"
  case "$token" in
    macos-arm64|macos-x64|windows-x64|linux-x64)
      printf '%s' "$token"
      return 0
      ;;
    *)
      HOST_TOKEN_UNSUPPORTED="$token"
      return 1
      ;;
  esac
}

# Work directory for all downloads. Removed by an EXIT trap so a failed/partial
# fetch never leaves artifacts behind. The trap is registered ONCE at top level
# (not inside prebuilt_work_dir) because prebuilt_work_dir is frequently called
# inside $(...) command substitutions — a trap registered there would fire when
# the subshell exits and wipe the directory mid-install.
PREBUILT_WORK_DIR=""
cleanup_prebuilt_work_dir() {
  if [[ -n "$PREBUILT_WORK_DIR" && -d "$PREBUILT_WORK_DIR" ]]; then
    rm -rf "$PREBUILT_WORK_DIR"
  fi
}
trap cleanup_prebuilt_work_dir EXIT

# Create the shared download work dir once, in the PARENT shell, before any
# command-substitution helper needs it. Idempotent.
init_prebuilt_work_dir() {
  if [[ -z "$PREBUILT_WORK_DIR" ]]; then
    PREBUILT_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/skill-bill-release.XXXXXX")"
  fi
}

prebuilt_work_dir() {
  init_prebuilt_work_dir
  printf '%s' "$PREBUILT_WORK_DIR"
}

# Compute the SHA-256 of a file as a bare lowercase hex string.
compute_sha256() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    sha256sum "$file" | awk '{print $1}'
  fi
}

# Fetch a single named release asset into the work dir and print its local path.
# Resolution order (overrides win, no network when an override is set):
#   1. SKILL_BILL_RELEASE_DIR  → copy the named file from that local directory.
#   2. SKILL_BILL_RELEASE_BASE_URL → curl "<base>/<name>" (supports file://).
#   3. GitHub release download URL for RELEASE_REPO/RELEASE_TAG.
# Fails loudly and removes any partial download on error.
fetch_release_asset() {
  local name="$1"
  local work_dir dest
  work_dir="$(prebuilt_work_dir)"
  dest="$work_dir/$name"

  if [[ -n "$SKILL_BILL_RELEASE_DIR" ]]; then
    if [[ ! -f "$SKILL_BILL_RELEASE_DIR/$name" ]]; then
      err "Release asset not found in SKILL_BILL_RELEASE_DIR: $name"
      return 1
    fi
    cp "$SKILL_BILL_RELEASE_DIR/$name" "$dest"
    printf '%s' "$dest"
    return 0
  fi

  local url
  if [[ -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    url="${SKILL_BILL_RELEASE_BASE_URL%/}/$name"
  else
    local ref
    if [[ -n "$RELEASE_TAG" ]]; then
      ref="download/$RELEASE_TAG"
    else
      ref="latest/download"
    fi
    url="https://github.com/$RELEASE_REPO/releases/$ref/$name"
  fi

  if ! curl -fsSL "$url" -o "$dest"; then
    err "Failed to download release asset: $name"
    err "  from: $url"
    rm -f "$dest"
    return 1
  fi
  printf '%s' "$dest"
}

# Verify a downloaded asset against its `.sha256` sibling (format `<hex>␣␣<name>`).
# On mismatch or missing checksum, fail loudly and remove the asset so no partial
# state survives.
verify_sha256() {
  local asset_path="$1"
  local name
  name="$(basename "$asset_path")"
  local checksum_path
  checksum_path="$(fetch_release_asset "$name.sha256")" || return 1

  local expected actual
  expected="$(awk '{print $1}' "$checksum_path" | head -n1)"
  if [[ -z "$expected" ]]; then
    err "Empty or malformed checksum for $name"
    rm -f "$asset_path" "$checksum_path"
    return 1
  fi
  actual="$(compute_sha256 "$asset_path")"
  if [[ "$actual" != "$expected" ]]; then
    err "Checksum mismatch for $name"
    err "  expected: $expected"
    err "  actual:   $actual"
    rm -f "$asset_path"
    return 1
  fi
  ok "  verified checksum: $name"
}

# List the asset filenames available for the target release. Offline
# (SKILL_BILL_RELEASE_DIR) → directory listing; otherwise → GitHub Releases API
# (pinned RELEASE_TAG or latest stable). Prints one name per line.
list_release_asset_names() {
  if [[ -n "$SKILL_BILL_RELEASE_DIR" ]]; then
    if [[ ! -d "$SKILL_BILL_RELEASE_DIR" ]]; then
      err "SKILL_BILL_RELEASE_DIR is not a directory: $SKILL_BILL_RELEASE_DIR"
      return 1
    fi
    (cd "$SKILL_BILL_RELEASE_DIR" && find . -maxdepth 1 -type f -printf '%f\n' 2>/dev/null \
      || ls -1 "$SKILL_BILL_RELEASE_DIR")
    return 0
  fi

  local api_url
  if [[ -n "$RELEASE_TAG" ]]; then
    api_url="https://api.github.com/repos/$RELEASE_REPO/releases/tags/$RELEASE_TAG"
  else
    api_url="https://api.github.com/repos/$RELEASE_REPO/releases/latest"
  fi
  local json
  if ! json="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api_url")"; then
    err "Failed to query release metadata: $api_url"
    return 1
  fi
  # Extract asset names from the JSON without requiring jq. The GitHub API emits
  # one "name":"<asset>" per asset inside the "assets" array.
  printf '%s' "$json" | grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
}

# Resolve the prebuilt asset filenames for this host by SUFFIX matching, not by
# exact filename: runtime-cli `.zip`, runtime-mcp `.zip`, and the desktop
# installer (.dmg/.msi/.deb/.rpm) whose names end with `<host-token>.<ext>`.
# Writes to the named output vars. Returns 1 (unsupported signal) when the host
# token is unsupported OR no runtime asset matches it, so the caller auto-falls
# back to --from-source.
RESOLVED_RUNTIME_CLI_ASSET=""
RESOLVED_RUNTIME_MCP_ASSET=""
RESOLVED_DESKTOP_ASSET=""
resolve_release_assets() {
  local token
  if ! token="$(host_token)"; then
    return 1
  fi

  local names name
  if ! names="$(list_release_asset_names)"; then
    return 1
  fi

  RESOLVED_RUNTIME_CLI_ASSET=""
  RESOLVED_RUNTIME_MCP_ASSET=""
  RESOLVED_DESKTOP_ASSET=""

  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    case "$name" in
      *.sha256) continue ;;
    esac
    case "$name" in
      runtime-cli-*"$token".zip)
        RESOLVED_RUNTIME_CLI_ASSET="$name"
        ;;
      runtime-mcp-*"$token".zip)
        RESOLVED_RUNTIME_MCP_ASSET="$name"
        ;;
      *"$token".dmg|*"$token".msi|*"$token".deb|*"$token".rpm)
        RESOLVED_DESKTOP_ASSET="$name"
        ;;
    esac
  done <<< "$names"

  if [[ -z "$RESOLVED_RUNTIME_CLI_ASSET" || -z "$RESOLVED_RUNTIME_MCP_ASSET" ]]; then
    HOST_TOKEN_UNSUPPORTED="$token"
    return 1
  fi
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

# AC4 default gating: report PURELY whether this host is a real desktop session.
# Linux: $DISPLAY or $WAYLAND_DISPLAY set → desktop; macOS/Windows → always a
# desktop; unknown → not a desktop. Returns 0 (yes/desktop) or 1 (no/headless).
# TTY-awareness is the caller's concern, not this helper's.
desktop_session_default_yes() {
  local os
  os="$(desktop_host_os)"
  case "$os" in
    linux)
      if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
        return 0
      fi
      return 1
      ;;
    macos|windows)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

prompt_for_desktop_app_install() {
  local input
  local normalized

  if [[ "$DESKTOP_APP_INSTALL" != "prompt" ]]; then
    return 0
  fi

  # Gated default driven solely by whether this is a real desktop session.
  # Headless (SSH/CI/no-DISPLAY) → skip; desktop → install. Explicit
  # --with/--no-desktop-app already short-circuited above via the != prompt guard.
  local default_choice
  if desktop_session_default_yes; then
    default_choice="yes"
  else
    default_choice="no"
  fi

  # Non-interactive (no TTY): resolve to the gated default WITHOUT blocking on read.
  if [[ ! -t 0 ]]; then
    DESKTOP_APP_INSTALL="$default_choice"
    return 0
  fi

  local default_hint
  if [[ "$default_choice" == "yes" ]]; then
    default_hint="1"
  else
    default_hint="2"
  fi

  while true; do
    echo ""
    info "Install the optional Skill Bill desktop app for this user?"
    if [[ "$default_choice" == "yes" ]]; then
      printf "  1. install (default)\n"
      printf "  2. skip\n"
    else
      printf "  1. install\n"
      printf "  2. skip (default)\n"
    fi
    printf "${CYAN}▸${NC} Enter desktop app choice [${default_hint}]: "
    if ! read -r input; then
      input=""
    fi

    normalized="$(printf '%s' "$(trim_string "$input")" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
      "")
        DESKTOP_APP_INSTALL="$default_choice"
        return 0
        ;;
      1|install|yes|y)
        DESKTOP_APP_INSTALL="yes"
        return 0
        ;;
      2|skip|no|n)
        DESKTOP_APP_INSTALL="no"
        return 0
        ;;
      *)
        warn "Enter 1, 2, install, skip, or press Enter for the default (${default_choice})."
        ;;
    esac
  done
}

# AC6: print exactly what the installer will change on the system and how to
# reverse it, BEFORE any mutation runs. `mode` is "full" (the whole install) or
# "desktop-only" (the --desktop-app-only path).
print_install_plan() {
  local mode="${1:-full}"
  local os
  os="$(desktop_host_os)"
  echo ""
  printf "${CYAN}━━━ What this installer will change ━━━${NC}\n"
  echo ""
  if [[ "$mode" == "desktop-only" ]]; then
    info "Desktop app: install the prebuilt SkillBill app under ${DESKTOP_APP_INSTALL_DIR:-$(default_desktop_app_install_dir "$os")}"
    info "Desktop launcher: $RUNTIME_LAUNCHER_BIN_DIR/skillbill-desktop"
    if [[ "$os" == "linux" ]]; then
      info "Linux desktop entry: ${XDG_DATA_HOME:-$HOME/.local/share}/applications/skillbill.desktop"
    fi
  else
    info "Clean-slate reset: re-runs ./uninstall.sh first, wiping ~/.skill-bill and removing prior Skill Bill agent symlinks, launchers, and MCP registrations."
    info "Agent symlinks: links Skill Bill skills into your selected agents' skill/command directories."
    info "Runtime: installs the Kotlin runtime under $RUNTIME_INSTALL_ROOT"
    info "Launchers: $RUNTIME_LAUNCHER_BIN_DIR/skill-bill, $RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
    info "MCP registration: registers the skill-bill MCP server with your selected agents."
    if [[ "$DESKTOP_APP_INSTALL" != "no" ]]; then
      info "Desktop app (if chosen): installs under ${DESKTOP_APP_INSTALL_DIR:-$(default_desktop_app_install_dir "$os")} with a skillbill-desktop launcher."
    fi
  fi
  echo ""
  info "Reverse everything with: $PLUGIN_DIR/uninstall.sh"
  echo ""
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

# Unpack a runtime image archive into a directory and print the top-level image
# directory that contains bin/<base> (the layout install_packaged_runtime_distribution
# expects as its source_dir). The Badass runtimeZip keeps the installDist layout
# under a single top-level directory, so we locate bin/<base> robustly rather than
# assuming a fixed top-dir name.
unpack_runtime_image() {
  local archive="$1"
  local base="$2"
  local extract_dir="$3"

  mkdir -p "$extract_dir"
  case "$archive" in
    *.zip)
      unzip -q -o "$archive" -d "$extract_dir"
      ;;
    *.tar.gz|*.tgz)
      tar -xzf "$archive" -C "$extract_dir"
      ;;
    *)
      err "Unsupported runtime image format: $archive"
      return 1
      ;;
  esac

  local bin_path
  bin_path="$(find "$extract_dir" -type f -path "*/bin/$base" -print 2>/dev/null | head -n1)"
  if [[ -z "$bin_path" ]]; then
    err "Could not locate bin/$base inside $archive"
    return 1
  fi
  # The source_dir for install_packaged_runtime_distribution is the directory two
  # levels above bin/<base> (i.e. the image root that contains bin/ and lib/).
  printf '%s' "$(dirname "$(dirname "$bin_path")")"
}

# Prebuilt runtime install: resolve + fetch + verify the runtime-cli/runtime-mcp
# image zips for this host, unpack each, and feed the unpacked image dirs into the
# EXISTING install_packaged_runtime_distribution (cp→.tmp + atomic mv). No Gradle,
# no JDK.
install_prebuilt_runtime_distributions() {
  local work_dir cli_archive mcp_archive cli_src mcp_src
  init_prebuilt_work_dir
  work_dir="$(prebuilt_work_dir)"

  info "Fetching prebuilt runtime images for host token: $(host_token)"
  cli_archive="$(fetch_release_asset "$RESOLVED_RUNTIME_CLI_ASSET")" || return 1
  verify_sha256 "$cli_archive" || return 1
  mcp_archive="$(fetch_release_asset "$RESOLVED_RUNTIME_MCP_ASSET")" || return 1
  verify_sha256 "$mcp_archive" || return 1

  cli_src="$(unpack_runtime_image "$cli_archive" "runtime-cli" "$work_dir/extract-cli")" || return 1
  mcp_src="$(unpack_runtime_image "$mcp_archive" "runtime-mcp" "$work_dir/extract-mcp")" || return 1

  info "Installing packaged Kotlin runtime to: $RUNTIME_INSTALL_ROOT"
  install_packaged_runtime_distribution "$cli_src" "$RUNTIME_CLI_INSTALL_DIR" "CLI"
  install_packaged_runtime_distribution "$mcp_src" "$RUNTIME_MCP_INSTALL_DIR" "MCP"
  locate_packaged_runtime_bin "$RUNTIME_CLI_BIN" "CLI"
  locate_packaged_runtime_bin "$RUNTIME_MCP_BIN" "MCP"
  ok "Kotlin runtime installed from prebuilt release"
}

# Dispatcher: prebuilt by default, Gradle build on --from-source or when no
# prebuilt artifact matches this host (auto-fallback with an explicit message).
# Preserves the SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD test escape hatch by
# routing through build_kotlin_runtime_distributions for the source path.
install_runtime_distributions() {
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    info "Installing runtime from source (--from-source); ignoring any --release tag."
    build_kotlin_runtime_distributions
    return 0
  fi

  # The test escape hatch short-circuits to the durable/build copy path without a
  # network fetch, regardless of source. Honor it on the prebuilt path too.
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    build_kotlin_runtime_distributions
    return 0
  fi

  check_prebuilt_dependencies || exit 1

  if resolve_release_assets; then
    install_prebuilt_runtime_distributions
    return 0
  fi

  INSTALL_SOURCE="source"
  warn "No prebuilt runtime artifact matched this host (token: ${HOST_TOKEN_UNSUPPORTED:-unknown}); falling back to a from-source Gradle build."
  build_kotlin_runtime_distributions
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
  SKILL_BILL_RUNTIME_EXECUTABLE="$RUNTIME_CLI_BIN" "$RUNTIME_CLI_BIN" --home "$HOME" "$@"
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

  # Only the from-source path builds the desktop app with Gradle. On the prebuilt
  # path the installer downloads + extracts the published installer instead (see
  # fetch_and_extract_desktop_app), so skip the Gradle build entirely.
  if [[ "$INSTALL_SOURCE" != "source" ]]; then
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

# Prebuilt desktop install: download + verify the host's desktop installer asset
# and EXTRACT the app payload WITHOUT running the system installer, so the per-user
# install stays self-contained and reversible by uninstall.sh. Extraction is
# format-specific:
#   .deb → ar x + extract data.tar.{xz,gz,zst}
#   .rpm → rpm2cpio | cpio -idm (or bsdtar -xf)
#   .dmg → hdiutil attach -nobrowse, copy .app, detach (macOS)
#   .msi → msiexec /a /qn TARGETDIR= (or lessmsi) (Windows)
# The located app tree is recorded in DESKTOP_APP_PREBUILT_SOURCE_PATH, which the
# EXISTING install_desktop_app consumes via desktop_app_source_path.
extract_deb_desktop_payload() {
  local asset="$1"
  local extract_dir="$2"
  mkdir -p "$extract_dir"
  (
    cd "$extract_dir"
    ar x "$asset"
  )
  local data_archive
  data_archive="$(find "$extract_dir" -maxdepth 1 -type f -name 'data.tar.*' -print 2>/dev/null | head -n1)"
  if [[ -z "$data_archive" ]]; then
    err "No data.tar.* payload found inside $asset"
    return 1
  fi
  local payload_dir="$extract_dir/payload"
  mkdir -p "$payload_dir"
  if command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$data_archive" -C "$payload_dir"
  else
    case "$data_archive" in
      *.xz)  tar -xJf "$data_archive" -C "$payload_dir" ;;
      *.gz)  tar -xzf "$data_archive" -C "$payload_dir" ;;
      *.zst)
        if tar --help 2>&1 | grep -q -- '--zstd'; then
          tar --zstd -xf "$data_archive" -C "$payload_dir"
        else
          err "tar lacks zstd support and bsdtar is unavailable for: $data_archive"
          return 1
        fi
        ;;
      *)
        err "Unsupported .deb data archive format: $data_archive"
        return 1
        ;;
    esac
  fi
  printf '%s' "$payload_dir"
}

extract_rpm_desktop_payload() {
  local asset="$1"
  local extract_dir="$2"
  local payload_dir="$extract_dir/payload"
  mkdir -p "$payload_dir"
  if command -v rpm2cpio >/dev/null 2>&1 && command -v cpio >/dev/null 2>&1; then
    (
      cd "$payload_dir"
      rpm2cpio "$asset" | cpio -idm --quiet
    )
  elif command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$asset" -C "$payload_dir"
  else
    err "Cannot extract .rpm: need rpm2cpio+cpio or bsdtar."
    return 1
  fi
  printf '%s' "$payload_dir"
}

extract_dmg_desktop_payload() {
  local asset="$1"
  local extract_dir="$2"
  local payload_dir="$extract_dir/payload"
  mkdir -p "$payload_dir"
  local mount_point
  mount_point="$(mktemp -d "$extract_dir/mount.XXXXXX")"
  if ! hdiutil attach -nobrowse -quiet -mountpoint "$mount_point" "$asset"; then
    err "Failed to attach DMG: $asset"
    return 1
  fi
  local app_src
  app_src="$(find "$mount_point" -maxdepth 2 -type d -name "*.app" -print 2>/dev/null | head -n1)"
  if [[ -n "$app_src" ]]; then
    cp -R "$app_src" "$payload_dir/"
  fi
  hdiutil detach -quiet "$mount_point" || true
  if [[ -z "$app_src" ]]; then
    err "No .app bundle found inside DMG: $asset"
    return 1
  fi
  printf '%s' "$payload_dir"
}

extract_msi_desktop_payload() {
  local asset="$1"
  local extract_dir="$2"
  local payload_dir="$extract_dir/payload"
  mkdir -p "$payload_dir"
  local target
  if command -v cygpath >/dev/null 2>&1; then
    target="$(cygpath -w "$payload_dir")"
  else
    target="$payload_dir"
  fi
  if command -v msiexec >/dev/null 2>&1; then
    msiexec /a "$asset" /qn TARGETDIR="$target"
  elif command -v lessmsi >/dev/null 2>&1; then
    lessmsi x "$asset" "$payload_dir/"
  else
    err "Cannot extract .msi: need msiexec or lessmsi."
    return 1
  fi
  printf '%s' "$payload_dir"
}

# Find the installed-app tree inside an extracted installer payload. jpackage lays
# the app down under .../<Name>.app (macOS), .../opt/<name> or .../<Name> with a
# bin/<Name> launcher (Linux .deb/.rpm), or a directory containing bin/<Name>.bat
# (Windows). Returns the directory desktop_app_source_path should treat as the
# prebuilt app root.
locate_extracted_desktop_app() {
  local payload_dir="$1"
  local os="$2"
  local app_path

  if [[ "$os" == "macos" ]]; then
    app_path="$(find "$payload_dir" -maxdepth 6 -type d -name "$DESKTOP_APP_DEFAULT_NAME.app" -print 2>/dev/null | head -n1)"
    if [[ -n "$app_path" ]]; then
      printf '%s' "$app_path"
      return 0
    fi
    app_path="$(find "$payload_dir" -maxdepth 6 -type d -name "*.app" -print 2>/dev/null | head -n1)"
    if [[ -n "$app_path" ]]; then
      printf '%s' "$app_path"
      return 0
    fi
    err "Could not locate a .app bundle in extracted payload: $payload_dir"
    return 1
  fi

  local launcher_name="$DESKTOP_APP_DEFAULT_NAME"
  if [[ "$os" == "windows" ]]; then
    launcher_name="$DESKTOP_APP_DEFAULT_NAME.bat"
  fi
  local launcher_path
  launcher_path="$(find "$payload_dir" -type f -path "*/bin/$launcher_name" -print 2>/dev/null | head -n1)"
  if [[ -z "$launcher_path" ]]; then
    err "Could not locate bin/$launcher_name in extracted payload: $payload_dir"
    return 1
  fi
  # The app root is the directory that contains bin/<launcher> (one level above bin/).
  printf '%s' "$(dirname "$(dirname "$launcher_path")")"
}

fetch_and_extract_desktop_app() {
  if [[ "$DESKTOP_APP_INSTALL" != "yes" ]]; then
    return 0
  fi
  if [[ -z "$RESOLVED_DESKTOP_ASSET" ]]; then
    err "No prebuilt desktop installer asset matched this host."
    return 1
  fi

  local os work_dir asset extract_dir payload_dir app_path
  os="$(desktop_host_os)"
  init_prebuilt_work_dir
  work_dir="$(prebuilt_work_dir)"
  extract_dir="$work_dir/desktop-extract"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"

  info "Fetching prebuilt desktop installer: $RESOLVED_DESKTOP_ASSET"
  asset="$(fetch_release_asset "$RESOLVED_DESKTOP_ASSET")" || return 1
  verify_sha256 "$asset" || return 1

  info "Extracting desktop app payload (no system installer is run)"
  case "$RESOLVED_DESKTOP_ASSET" in
    *.deb) payload_dir="$(extract_deb_desktop_payload "$asset" "$extract_dir")" || return 1 ;;
    *.rpm) payload_dir="$(extract_rpm_desktop_payload "$asset" "$extract_dir")" || return 1 ;;
    *.dmg) payload_dir="$(extract_dmg_desktop_payload "$asset" "$extract_dir")" || return 1 ;;
    *.msi) payload_dir="$(extract_msi_desktop_payload "$asset" "$extract_dir")" || return 1 ;;
    *)
      err "Unsupported desktop installer format: $RESOLVED_DESKTOP_ASSET"
      return 1
      ;;
  esac

  app_path="$(locate_extracted_desktop_app "$payload_dir" "$os")" || return 1
  DESKTOP_APP_PREBUILT_SOURCE_PATH="$app_path"
  ok "Desktop app payload extracted"
}

desktop_app_source_path() {
  local os="$1"

  # Prebuilt path: the extracted installer payload is the source of truth.
  if [[ -n "$DESKTOP_APP_PREBUILT_SOURCE_PATH" ]]; then
    printf '%s' "$DESKTOP_APP_PREBUILT_SOURCE_PATH"
    return 0
  fi

  local app_root="$RUNTIME_KOTLIN_DIR/runtime-desktop/build/compose/binaries/main/app"
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
      DESKTOP_APP_LAUNCHER_PATH="$launcher_path"
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
  DESKTOP_APP_LAUNCHER_PATH="$launcher_path"
  ok "  linked desktop launcher → $launcher_path"
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
  DESKTOP_APP_INSTALLED_PATH="$target_path"

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

# AC5: the v1 desktop installers ship UNSIGNED (runtime-kotlin/agent/decisions.md,
# 2026-05-29 "Ship desktop installers UNSIGNED for v1"). After a successful desktop
# install, surface the OS-specific "open anyway" steps VERBATIM from that decision.
# Linux has no OS-level signing gate, so nothing is printed there.
print_desktop_unsigned_hint() {
  if [[ "$DESKTOP_APP_INSTALL" != "yes" ]]; then
    return 0
  fi
  local os
  os="$(desktop_host_os)"
  case "$os" in
    macos)
      echo ""
      warn "The desktop app is unsigned for v1. To open it the first time:"
      info "  macOS (Gatekeeper): Right-click (or Control-click) the app in Finder -> Open -> Open in the confirmation dialog. Alternatively: System Settings -> Privacy & Security -> Open Anyway."
      ;;
    windows)
      echo ""
      warn "The desktop app is unsigned for v1. To open it the first time:"
      info "  Windows (SmartScreen): On the \"Windows protected your PC\" dialog, click More info -> Run anyway."
      ;;
    *)
      :
      ;;
  esac
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

# Prepare the desktop app payload right before installing it. On --from-source the
# Gradle build already ran; on the prebuilt path resolve the desktop asset (if not
# already resolved by the runtime step) and fetch+extract it. Idempotent.
prepare_desktop_app_payload() {
  if [[ "$DESKTOP_APP_INSTALL" != "yes" ]]; then
    return 0
  fi
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    # build_desktop_app_distribution already produced the Gradle output.
    return 0
  fi
  if [[ -z "$RESOLVED_DESKTOP_ASSET" ]]; then
    if ! resolve_release_assets; then
      warn "No prebuilt desktop installer matched this host (token: ${HOST_TOKEN_UNSUPPORTED:-unknown}); skipping desktop app."
      DESKTOP_APP_INSTALL="no"
      return 0
    fi
  fi
  if [[ -z "$RESOLVED_DESKTOP_ASSET" ]]; then
    warn "No prebuilt desktop installer asset is published for this host; skipping desktop app."
    DESKTOP_APP_INSTALL="no"
    return 0
  fi
  fetch_and_extract_desktop_app
}

# AC11 desktop-only path (--desktop-app-only): install ONLY the prebuilt desktop
# app — no runtime build, no agent/platform/telemetry prompts, no runtime apply,
# no pre-install uninstall. Re-runnable/idempotent. Honors --release and the
# offline seams.
run_desktop_only_install() {
  print_install_plan "desktop-only"
  check_prebuilt_dependencies || exit 1
  if ! resolve_release_assets; then
    err "Cannot install the desktop app: no prebuilt release assets matched this host (token: ${HOST_TOKEN_UNSUPPORTED:-unknown})."
    err "Re-run the full installer with --from-source to build the desktop app from source."
    exit 1
  fi
  if [[ -z "$RESOLVED_DESKTOP_ASSET" ]]; then
    err "No prebuilt desktop installer asset is published for this host."
    exit 1
  fi

  DESKTOP_APP_INSTALL="yes"
  fetch_and_extract_desktop_app
  install_desktop_app

  echo ""
  printf "${GREEN}━━━ Desktop app installed ━━━${NC}\n"
  echo ""
  info "Desktop app:     ${DESKTOP_APP_INSTALLED_PATH:-installed for $(desktop_host_os)}"
  if [[ -n "$DESKTOP_APP_LAUNCHER_PATH" ]]; then
    info "Desktop launcher: $DESKTOP_APP_LAUNCHER_PATH"
  fi
  print_desktop_unsigned_hint
  echo ""
  info "Reverse with: $PLUGIN_DIR/uninstall.sh"
  echo ""
}

run_full_install() {
  print_install_plan "full"
  run_pre_install_uninstall
  install_runtime_distributions
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
  prepare_desktop_app_payload
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
    info "Desktop app:     ${DESKTOP_APP_INSTALLED_PATH:-installed for $(desktop_host_os)}"
    if [[ -n "$DESKTOP_APP_LAUNCHER_PATH" ]]; then
      info "Desktop launcher: $DESKTOP_APP_LAUNCHER_PATH"
    fi
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

  print_desktop_unsigned_hint

  echo ""
  info "Edit skills in: $PLUGIN_DIR/skills/"
  if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
    info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
  fi
  info "Add the desktop app later (no full reinstall) with: $PLUGIN_DIR/install.sh --desktop-app-only"
  info "Run './install.sh' again to reinstall with different agent, platform, telemetry, or desktop app choices."
  echo ""
}

parse_args "$@"
if [[ "$DESKTOP_APP_ONLY" -eq 1 ]]; then
  run_desktop_only_install
else
  run_full_install
fi
