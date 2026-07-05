#!/usr/bin/env bash
set -euo pipefail

ORIGINAL_ARGS=("$@")
INSTALLER_SCRIPT_SOURCE="${BASH_SOURCE[0]:-}"
INSTALLER_FROM_STDIN=0
if [[ -z "$INSTALLER_SCRIPT_SOURCE" || ! -f "$INSTALLER_SCRIPT_SOURCE" ]]; then
  INSTALLER_FROM_STDIN=1
fi

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
# For Linux .deb/.rpm payloads this records the full extraction root (above the
# app tree), so install_linux_desktop_entry can locate the packaged icon. The
# prebuilt source is never RUNTIME_KOTLIN_DIR-relative (the release bundle has
# no runtime-kotlin checkout), so the icon must come from inside this payload.
DESKTOP_APP_PREBUILT_PAYLOAD_DIR=""
# SKILL-76 subtask 2: space-separated skill-relative paths whose both-changed
# reconcile conflict the user accepted (overwrote). Surfaced in the install summary.
RECONCILE_CONFLICT_PATHS=""

# Install source. `auto` means a full local checkout installs from source, while a
# standalone downloaded installer falls back to published prebuilt release assets.
INSTALL_SOURCE="auto"
RELEASE_TAG="${SKILL_BILL_RELEASE_TAG:-}"
DESKTOP_APP_ONLY=0
REUSE_LAST_SELECTION=0
PREFER_UPSTREAM=0
CLEAN_INSTALL=0
RELEASE_REPO="${SKILL_BILL_RELEASE_REPO:-oila-gmbh/skill-bill}"
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

declare -a SUPPORTED_AGENTS=(copilot claude codex opencode junie zcode)
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
                    [--desktop-app-only] [--reuse-last-selection]

By default a local checkout builds and installs from source. A standalone
downloaded installer uses checksum-verified prebuilt release images.

Options:
  --from-source            Force building the runtime (and desktop app) from
                           source with Gradle. Ignores --release. Requires a JDK.
  --release TAG            Install a specific release tag instead of the latest
                           stable release. Ignored with --from-source.
  --with-desktop-app       Install the optional desktop app from the prebuilt
                           installer (non-interactive).
  --no-desktop-app         Skip desktop app installation (non-interactive).
  --desktop-app-dir PATH   Override the per-user desktop app install directory.
  --desktop-app-only       Install ONLY the prebuilt desktop app (skip the full
                           installer). Use this to add the desktop app later
                           without re-running the full install.
  --reuse-last-selection   Reuse the latest successful agent, platform,
                           telemetry, and MCP choices from ~/.skill-bill.
                           Desktop app install uses the normal non-interactive
                           default unless --with-desktop-app or --no-desktop-app
                           is provided.
  --prefer-upstream        When a skill changed both upstream and locally,
                           overwrite the local copy with the upstream version
                           instead of keeping local. Useful for non-interactive
                           installs: curl ... | bash -s -- --prefer-upstream
  --clean                  Wipe ~/.skill-bill/skills/, ~/.skill-bill/platform-packs/,
                           and ~/.skill-bill/orchestration/ before staging the
                           candidate tree. Useful for a clean-slate install. Composable
                           with --prefer-upstream.
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
      --reuse-last-selection)
        REUSE_LAST_SELECTION=1
        shift
        ;;
      --prefer-upstream)
        PREFER_UPSTREAM=1
        shift
        ;;
      --clean)
        CLEAN_INSTALL=1
        shift
        ;;
      --release)
        if [[ $# -lt 2 || -z "$(trim_string "$2")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
        RELEASE_TAG="$2"
        if [[ "$INSTALL_SOURCE" == "auto" ]]; then
          INSTALL_SOURCE="prebuilt"
        fi
        shift 2
        ;;
      --release=*)
        RELEASE_TAG="${1#--release=}"
        if [[ -z "$(trim_string "$RELEASE_TAG")" ]]; then
          err "--release requires a tag."
          exit 1
        fi
        if [[ "$INSTALL_SOURCE" == "auto" ]]; then
          INSTALL_SOURCE="prebuilt"
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

  if [[ "$DESKTOP_APP_ONLY" -eq 1 && "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    err "--reuse-last-selection cannot be combined with --desktop-app-only."
    err "Run the full installer with --reuse-last-selection, or run --desktop-app-only by itself."
    exit 1
  fi
}

local_source_checkout_available() {
  [[ -d "$SKILLS_DIR" ]] &&
    [[ -d "$PLATFORM_PACKS_DIR" ]] &&
    [[ -d "$PLUGIN_DIR/orchestration" ]] &&
    [[ -x "$RUNTIME_KOTLIN_DIR/gradlew" ]]
}

resolve_install_source() {
  if [[ "$INSTALL_SOURCE" != "auto" ]]; then
    return 0
  fi
  if [[ -n "$RELEASE_TAG" || -n "$SKILL_BILL_RELEASE_DIR" || -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    INSTALL_SOURCE="prebuilt"
    return 0
  fi
  if local_source_checkout_available; then
    INSTALL_SOURCE="source"
  else
    INSTALL_SOURCE="prebuilt"
  fi
}

resolve_release_installer_tag() {
  if [[ -n "$RELEASE_TAG" ]]; then
    printf '%s' "$RELEASE_TAG"
    return 0
  fi
  local api_url tag
  api_url="https://api.github.com/repos/$RELEASE_REPO/releases/latest"
  tag="$(curl -fsSL "$api_url" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
  if [[ -z "$tag" ]]; then
    err "Failed to resolve latest release tag from: $api_url"
    return 1
  fi
  printf '%s' "$tag"
}

bootstrap_release_installer_if_needed() {
  if [[ "$INSTALLER_FROM_STDIN" -ne 1 ]]; then
    return 0
  fi
  if [[ "${SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "$SKILL_BILL_RELEASE_DIR" || -n "$SKILL_BILL_RELEASE_BASE_URL" ]]; then
    return 0
  fi
  if [[ "$INSTALL_SOURCE" == "source" ]]; then
    return 0
  fi

  if ! command -v curl >/dev/null 2>&1; then
    err "curl is required to resolve and fetch the release installer."
    exit 1
  fi

  local tag installer_url installer
  tag="$(resolve_release_installer_tag)" || exit 1
  installer_url="https://raw.githubusercontent.com/$RELEASE_REPO/$tag/install.sh"
  info "Standalone installer: using release installer $tag."
  if ! installer="$(curl -fsSL "$installer_url")"; then
    err "Failed to fetch release installer: $installer_url"
    exit 1
  fi

  local bootstrap_args=("${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")
  if [[ -z "$RELEASE_TAG" ]]; then
    bootstrap_args=(--release "$tag" "${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")
  fi

  export SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED=1
  exec bash -s -- "${bootstrap_args[@]+"${bootstrap_args[@]}"}" <<<"$installer"
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

# SKILL-76 subtask 2 (F-006): the staged reconcile candidate dirs (~/.skill-bill/
# .candidate-*) must ALWAYS be reaped on crash/signal, not only on the happy path.
# Composed into the single EXIT trap below so it never clobbers the prebuilt-work-dir
# cleanup. Guarded (discard_authored_candidates is a no-op when the dirs are absent),
# and it only ever removes the .candidate-* staging dirs, never a mid-commit live tree
# (the runtime apply moves into the live skills/ dir, which is not a candidate path).
cleanup_install_exit() {
  cleanup_prebuilt_work_dir
  # discard_authored_candidates is defined later in this file; on an early-exit before
  # its definition the candidate dirs do not exist yet, so guard the call.
  if declare -f discard_authored_candidates >/dev/null 2>&1; then
    discard_authored_candidates
  fi
}
trap cleanup_install_exit EXIT

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

# Resolve the skills bundle asset name for the current release.
# Queries list_release_asset_names (offline or GitHub API) and returns the
# first filename matching skill-bill-skills-*.tar.gz. Fails loudly when not found.
resolve_skills_bundle_asset_name() {
  local names name
  if ! names="$(list_release_asset_names)"; then
    err "Failed to list release assets while resolving skills bundle name."
    return 1
  fi
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    case "$name" in
      skill-bill-skills-*.tar.gz)
        printf '%s' "$name"
        return 0
        ;;
    esac
  done <<< "$names"
  err "No skill-bill-skills-*.tar.gz asset found in release."
  return 1
}

# Bootstrap PLUGIN_DIR from a GitHub release when no trusted local tree is present.
# For a non-piped install the local tree wins when SKILLS_DIR exists and this is a no-op.
# A piped install (INSTALLER_FROM_STDIN=1) cannot trust a CWD-relative skills/ dir, so it
# always fetches the bundle: print an info line, resolve the bundle asset name, fetch and
# verify the .tar.gz, extract into a subdir of PREBUILT_WORK_DIR, then re-point PLUGIN_DIR,
# SKILLS_DIR, and PLATFORM_PACKS_DIR to the extracted root.
bundle_bootstrap_if_needed() {
  if [[ "$INSTALLER_FROM_STDIN" -ne 1 && -d "$SKILLS_DIR" ]]; then
    return 0
  fi

  if [[ "$INSTALLER_FROM_STDIN" -eq 1 ]]; then
    info "Piped install — fetching skills bundle from release."
  else
    info "SKILLS_DIR not found — fetching skills bundle from release."
  fi
  check_prebuilt_dependencies || return 1
  init_prebuilt_work_dir

  local asset_name
  if ! asset_name="$(resolve_skills_bundle_asset_name)"; then
    err "Cannot proceed without a skills bundle."
    return 1
  fi

  local asset_path
  if ! asset_path="$(fetch_release_asset "$asset_name")"; then
    err "Failed to fetch skills bundle: $asset_name"
    return 1
  fi

  verify_sha256 "$asset_path" || return 1

  local extract_dir
  extract_dir="$PREBUILT_WORK_DIR/skills-bundle"
  mkdir -p "$extract_dir"
  tar -xzf "$asset_path" -C "$extract_dir"

  if [[ -d "$extract_dir/skills" ]]; then
    PLUGIN_DIR="$extract_dir"
  else
    local subdir
    subdir="$(find "$extract_dir" -mindepth 2 -maxdepth 2 -type d -name skills -print 2>/dev/null | head -n1)"
    if [[ -n "$subdir" ]]; then
      PLUGIN_DIR="$(dirname "$subdir")"
    else
      err "Skills bundle layout is unrecognised: no skills/ directory found under $extract_dir"
      return 1
    fi
  fi
  SKILLS_DIR="$PLUGIN_DIR/skills"
  PLATFORM_PACKS_DIR="$PLUGIN_DIR/platform-packs"

  ok "Skills bundle extracted; PLUGIN_DIR set to: $PLUGIN_DIR"
}

# Wipe the three skill-state subdirectories when --clean was passed.
# Runs after bundle_bootstrap_if_needed and before copy_in_authored_source.
clean_install_state_if_requested() {
  if [[ "$CLEAN_INSTALL" -ne 1 ]]; then
    return 0
  fi
  if [[ -z "$SKILL_BILL_STATE_DIR" ]]; then
    err "--clean: SKILL_BILL_STATE_DIR is empty; refusing to wipe."
    return 1
  fi
  info "--clean: wiping prior skill state under $SKILL_BILL_STATE_DIR"
  rm -rf \
    "$SKILL_BILL_STATE_DIR/skills" \
    "$SKILL_BILL_STATE_DIR/platform-packs" \
    "$SKILL_BILL_STATE_DIR/orchestration"
  ok "Prior skill state wiped."
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

  # Non-interactive (no TTY) and reuse mode resolve to the gated default WITHOUT
  # blocking on read. The shared install-selection record owns runtime choices
  # only; explicit --with/--no-desktop-app remains the desktop override.
  if ! prompt_input_available || [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
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
    if ! read_prompt_input input; then
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

# One-time migration: older installs kept config.json inside the wiped
# ~/.skill-bill/. Move it to the durable XDG location (~/.config/skill-bill/,
# outside the wipe zone) BEFORE the pre-install uninstall so user settings —
# external_addon_sources, telemetry choices, install_id — survive every install.
# Skipped when the user pins a config path via SKILL_BILL_CONFIG_PATH, or when
# the durable copy already exists (never clobber it).
migrate_legacy_config_to_durable_path() {
  if [[ -n "${SKILL_BILL_CONFIG_PATH:-}" ]]; then
    return 0
  fi
  local legacy="$SKILL_BILL_STATE_DIR/config.json"
  local durable="$HOME/.config/skill-bill/config.json"
  if [[ -f "$legacy" && ! -f "$durable" ]]; then
    mkdir -p "$(dirname "$durable")"
    if mv "$legacy" "$durable"; then
      info "Migrated Skill Bill config to durable location: $durable"
    else
      warn "Could not migrate config to $durable; leaving it at $legacy."
    fi
  fi
}

# Every install starts from a clean slate: removes agent symlinks, native
# subagent symlinks, MCP registrations, runtime launchers, and wipes
# ~/.skill-bill/ (including the installed-skills staging cache, runtime
# binaries, while preserving persistent state DBs). This guarantees that
# generator changes — which the staging-cache content hash does not see —
# actually land on the next install without deleting durable workflow state.
#
# Tests and dev iteration can opt out with SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1.
run_pre_install_uninstall() {
  if [[ "${SKILL_BILL_SKIP_PREINSTALL_UNINSTALL:-}" == "1" ]]; then
    warn "Skipping pre-install uninstall because SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1."
    return 0
  fi
  if [[ ! -x "$RUNTIME_CLI_BIN" && ! -d "$SKILL_BILL_STATE_DIR" ]]; then
    info "No prior Skill Bill install detected; skipping pre-install cleanup."
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
  # PRESERVE the copied-in self-contained source (skills/, platform-packs/,
  # orchestration/ + the reserved baseline-manifest path) and durable *.db state
  # across the pre-install wipe, while still clearing runtime/ and
  # installed-skills/.
  # This flag is ONLY set for the install-driven pre-install uninstall; an
  # explicit ./uninstall.sh (flag unset) still fully removes ~/.skill-bill.
  SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1 bash "$uninstall_script"
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

# Copy the clone's authored skill/platform/orchestration source into the
# Skill Bill state dir as REAL files (not symlinks) BEFORE any skill linking,
# so that deleting the clone after a successful install leaves a fully
# functional install. Uses the same atomic copy idiom as
# install_packaged_runtime_distribution (rm -rf tmp; cp -R src tmp; rm -rf
# target; mv tmp target). Only authored source lives in these trees:
# content.md, native-agents/, platform.yaml. Generated SKILL.md wrappers and
# support pointers are render OUTPUT into installed-skills staging and never
# appear under the clone's skills/, so an unfiltered cp -R is source-safe.
# SKILL-76 subtask 2 candidate paths: the clone source is staged into these
# .candidate dirs FIRST (no swap), reconciled against the existing copy + baseline,
# and only swapped into place once the conflict decision is accept/no-conflict. An
# abort discards the candidates and leaves the existing install fully intact.
# SKILL-76 subtask 2: the candidate clone source is staged into a SINGLE candidate
# REPO ROOT containing skills/ + platform-packs/, so reconcile --upstream-repo-root,
# --upstream-skills, and --upstream-platform-packs all point at the same staged tree
# (F-008: support-pointer source and skill source come from one tree).
SKILL_BILL_CANDIDATE_ROOT="$SKILL_BILL_STATE_DIR/.candidate-source"
SKILL_BILL_CANDIDATE_SKILLS="$SKILL_BILL_CANDIDATE_ROOT/skills"
SKILL_BILL_CANDIDATE_PLATFORM_PACKS="$SKILL_BILL_CANDIDATE_ROOT/platform-packs"
SKILL_BILL_CANDIDATE_ORCHESTRATION="$SKILL_BILL_CANDIDATE_ROOT/orchestration"
SKILL_BILL_BASELINE_MANIFEST="$SKILL_BILL_STATE_DIR/baseline-manifest.json"

# Stage one source tree into a candidate dir without touching the live target.
stage_authored_candidate() {
  local source_dir="$1"
  local candidate_dir="$2"
  local label="$3"
  if [[ ! -d "$source_dir" ]]; then
    err "Missing authored $label source: $source_dir"
    return 1
  fi
  rm -rf "$candidate_dir"
  mkdir -p "$(dirname "$candidate_dir")"
  cp -R "$source_dir" "$candidate_dir"
}

# Reap the staged candidate dirs. Guarded so it is safe to call from the EXIT trap
# (no-op when the dirs are already gone). Never touches the live source trees.
discard_authored_candidates() {
  rm -rf \
    "$SKILL_BILL_CANDIDATE_ROOT" \
    "$SKILL_BILL_CANDIDATE_ORCHESTRATION"
}

# Step 1 (decision strictly BEFORE any live-tree mutation): stage the clone's
# authored source into candidate dirs. No live tree is mutated here.
copy_in_authored_source() {
  info "Staging authored skill source candidates under: $SKILL_BILL_STATE_DIR"
  mkdir -p "$SKILL_BILL_CANDIDATE_ROOT"
  stage_authored_candidate "$SKILLS_DIR" "$SKILL_BILL_CANDIDATE_SKILLS" "skills source"
  stage_authored_candidate "$PLATFORM_PACKS_DIR" "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS" "platform-packs source"
  stage_authored_candidate "$PLUGIN_DIR/orchestration" "$SKILL_BILL_CANDIDATE_ORCHESTRATION" "orchestration source"
  ok "Authored source candidates staged under $SKILL_BILL_STATE_DIR"
}

# Adopt-always non-skill-keyed trees from the candidate into the live state dir.
# orchestration/ is NOT part of the per-skill baseline in this subtask (it is shared
# source, not a skill), so it is replaced wholesale from the candidate every install.
#
# The platform-packs tree is intentionally NOT copied here. The runtime `install reconcile
# --apply` is the SOLE writer of ALL reconciled skill dirs in BOTH skills/ AND
# platform-packs/, and it also adopts the non-skill platform-pack files (platform.yaml,
# addon markdown, pack-level metadata) from upstream. Blanket-copying the pack tree here
# would clobber per-skill pack content BEFORE the apply classifies it, silently defeating
# keep-local/conflict for platform-pack skills.
adopt_non_skill_source_trees() {
  # orchestration: wholesale atomic replace.
  if [[ -d "$SKILL_BILL_CANDIDATE_ORCHESTRATION" ]]; then
    rm -rf "$SKILL_BILL_STATE_DIR/orchestration"
    mv "$SKILL_BILL_CANDIDATE_ORCHESTRATION" "$SKILL_BILL_STATE_DIR/orchestration"
  fi
}

# Parse the line-oriented machine report (mirrors the SKILL-74 line protocol) into the
# RECONCILE_* shell state. FAIL-CLOSED: if the `reconcile_summary:` line is absent the
# caller MUST abort. Sets RECONCILE_HAS_CONFLICTS / RECONCILE_CONFLICT_COUNT and appends
# conflicting paths to RECONCILE_CONFLICT_PATHS. Each per-outcome line is
# `reconcile_outcome: kind=<k> [upstream_hash=<hex>] path=<p>` with `path` LAST so a path
# containing spaces survives the trailing-remainder extraction.
RECONCILE_HAS_CONFLICTS=""
RECONCILE_CONFLICT_COUNT=""
parse_reconcile_report() {
  local report="$1"
  RECONCILE_HAS_CONFLICTS=""
  RECONCILE_CONFLICT_COUNT=""
  RECONCILE_CONFLICT_PATHS=""
  local summary_line conflict_lines line
  summary_line="$( { printf '%s\n' "$report" | grep -m1 '^reconcile_summary:'; } || true )"
  if [[ -z "$summary_line" ]]; then
    return 1
  fi
  # Extract has_conflicts / conflict_count from the summary key=value tokens.
  RECONCILE_HAS_CONFLICTS="$( { printf '%s' "$summary_line" | grep -o 'has_conflicts=[a-z]*' | cut -d= -f2; } || true )"
  RECONCILE_CONFLICT_COUNT="$( { printf '%s' "$summary_line" | grep -o 'conflict_count=[0-9]*' | cut -d= -f2; } || true )"
  if [[ -z "$RECONCILE_HAS_CONFLICTS" || -z "$RECONCILE_CONFLICT_COUNT" ]]; then
    return 1
  fi
  # Collect conflicting skill paths from the per-outcome lines. `kind` is anchored as the
  # first token so this filter cannot collide with a path that contains "kind=conflict",
  # and `path=` is the LAST token so the trailing-remainder extraction below keeps paths
  # that contain spaces intact (no [^ ]* truncation).
  conflict_lines="$( { printf '%s\n' "$report" | grep '^reconcile_outcome: kind=conflict '; } || true )"
  if [[ -n "$conflict_lines" ]]; then
    while IFS= read -r line; do
      [[ -n "$line" ]] || continue
      local p
      p="$( { printf '%s' "$line" | sed -n 's/^.* path=//p'; } || true )"
      [[ -n "$p" ]] && RECONCILE_CONFLICT_PATHS+="$p "
    done <<< "$conflict_lines"
  fi
  return 0
}

# Step 2-4: reconcile the staged candidate against the existing local copy + the
# baseline manifest, drive the interactive conflict decision, and ONLY then call the
# runtime per-skill APPLY (which owns the per-skill file ops + baseline refresh). Runs
# AFTER the runtime CLI is installed so run_runtime_cli is available.
#
# - First install (no existing skills copy, no baseline): every skill is new-upstream
#   → apply installs them all, refreshes baseline, no prompt.
# - keep-local: a user edit survives (local!=baseline, upstream==baseline) — apply
#   leaves the live skill untouched.
# - adopt: an untouched local adopts new upstream + refreshes baseline.
# - locally-authored: a skill with no upstream counterpart is NEVER deleted.
# - conflict (both changed): TTY → WARN + prompt y/n; NO-TTY → abort with a
#   clear message. y → apply --accept-conflicts; n → discard, change nothing.
#   ALL conflicts are reported in the install summary. No sidecar.
#
# The shell performs NO whole-tree rm/mv swap of skills/ — the runtime per-skill apply
# is the sole writer of the live skill dirs.
reconcile_and_commit_authored_source() {
  local report status accept_conflicts=0
  info "Reconciling staged source against existing copy and baseline manifest..."
  # Compute the per-skill plan against UPSTREAM=candidate, LOCAL=existing copy. The
  # upstream repo root, skills, and platform-packs all come from the one staged
  # candidate tree (F-008). No mutation here.
  report="$(run_runtime_cli install reconcile \
    --repo-root "$SKILL_BILL_STATE_DIR" \
    --skills "$SKILL_BILL_STATE_DIR/skills" \
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs" \
    --upstream-repo-root "$SKILL_BILL_CANDIDATE_ROOT" \
    --upstream-skills "$SKILL_BILL_CANDIDATE_SKILLS" \
    --upstream-platform-packs "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS")" || status=$?
  if [[ -n "${status:-}" ]]; then
    err "Reconciliation failed; leaving the existing install untouched."
    discard_authored_candidates
    return 1
  fi

  # FAIL-CLOSED: a missing/unparseable summary line aborts the install rather than
  # treating the absence of conflicts as "no conflicts".
  if ! parse_reconcile_report "$report"; then
    err "Could not parse the reconcile machine report; aborting the install. Nothing was changed."
    discard_authored_candidates
    return 1
  fi

  if [[ "$RECONCILE_HAS_CONFLICTS" == "true" || "${RECONCILE_CONFLICT_COUNT:-0}" -ne 0 ]]; then
    warn "Reconcile conflict: both upstream and your local copy changed for:"
    local p
    for p in $RECONCILE_CONFLICT_PATHS; do
      warn "  conflict: $p"
    done
    # TEST-ONLY SEAM: SKILL_BILL_RECONCILE_CONFLICT_CHOICE supplies the conflict decision
    # (y/n) and bypasses ONLY the TTY check, so integration tests can drive the
    # y branch under piped stdin (where [[ ! -t 0 ]] would otherwise abort). When the
    # env var is UNSET/empty, production behavior is unchanged: TTY -> prompt,
    # no-TTY -> keep local. Mirrors the SKILL_BILL_SKIP_PREINSTALL_UNINSTALL opt-out style.
    if [[ -n "${SKILL_BILL_RECONCILE_CONFLICT_CHOICE:-}" ]]; then
      local answer="$SKILL_BILL_RECONCILE_CONFLICT_CHOICE"
      info "Using SKILL_BILL_RECONCILE_CONFLICT_CHOICE=$answer for the conflict decision (test seam)."
      case "$answer" in
        [Yy]|[Yy][Ee][Ss])
          info "Accepting upstream for conflicting skills; your local edits will be overwritten."
          accept_conflicts=1
          ;;
        *)
          err "Aborting the whole install at your request; nothing was changed."
          discard_authored_candidates
          return 1
          ;;
      esac
    elif [[ "$PREFER_UPSTREAM" -eq 1 ]]; then
      info "Accepting upstream for conflicting skills (--prefer-upstream); your local edits will be overwritten."
      accept_conflicts=1
    elif ! prompt_input_available; then
      warn "Aborting: no TTY is attached to prompt for conflict resolution. Your local copies were not changed."
      local p
      for p in $RECONCILE_CONFLICT_PATHS; do
        warn "  kept local: $p"
      done
      warn "To take the upstream version instead, re-run with --prefer-upstream:"
      warn "  curl -fsSL https://raw.githubusercontent.com/oila-gmbh/skill-bill/main/install.sh | bash -s -- --prefer-upstream"
    else
      printf '%s' "Overwrite your local copy with the upstream version for the conflicting skills? [y/n]: "
      local answer=""
      if ! read_prompt_input answer; then
        answer="n"
      fi
      case "$answer" in
        [Yy]|[Yy][Ee][Ss])
          info "Accepting upstream for conflicting skills; your local edits will be overwritten."
          accept_conflicts=1
          ;;
        *)
          err "Aborting the whole install at your request; nothing was changed."
          discard_authored_candidates
          return 1
          ;;
      esac
    fi
  fi

  # Decision is accept / no-conflict. Hand the per-skill file ops to the runtime apply
  # while the candidate remains a complete repo root for support-pointer validation. The
  # runtime is the SOLE writer of the live skill dirs in BOTH skills/ AND platform-packs/
  # (and of the non-skill platform-pack files): keep-local + locally-authored skills are
  # preserved by construction.
  local apply_args=(
    install reconcile --apply
    --repo-root "$SKILL_BILL_STATE_DIR"
    --skills "$SKILL_BILL_STATE_DIR/skills"
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs"
    --upstream-repo-root "$SKILL_BILL_CANDIDATE_ROOT"
    --upstream-skills "$SKILL_BILL_CANDIDATE_SKILLS"
    --upstream-platform-packs "$SKILL_BILL_CANDIDATE_PLATFORM_PACKS"
  )
  if [[ "$accept_conflicts" -eq 1 ]]; then
    apply_args+=(--accept-conflicts)
  fi
  if ! run_runtime_cli "${apply_args[@]}" >/dev/null; then
    err "Runtime reconcile apply failed; some skills may not have been updated."
    discard_authored_candidates
    return 1
  fi
  adopt_non_skill_source_trees
  # RESERVED SEAM (subtask 2): the baseline manifest lives at
  # "$SKILL_BILL_BASELINE_MANIFEST" and is part of the preserved self-contained source
  # set (uninstall.sh preserve-mode). The runtime apply already refreshed it.
  discard_authored_candidates
  ok "Authored source reconciled and committed into $SKILL_BILL_STATE_DIR"
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

# Dispatcher: source builds for local checkouts, prebuilt downloads for release
# installs, and source fallback when no prebuilt artifact matches this host.
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
  rm -rf \
    "$RUNTIME_KOTLIN_DIR/runtime-cli/build/install/runtime-cli" \
    "$RUNTIME_KOTLIN_DIR/runtime-mcp/build/install/runtime-mcp"
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

runtime_cli_supports_selection_replay() {
  local runtime_bin="$1"
  [[ -x "$runtime_bin" ]] || return 1
  "$runtime_bin" install --help 2>/dev/null | grep -q "replay-last-selection"
}

build_selection_replay_runtime_cli() {
  if [[ "${SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD:-}" == "1" ]]; then
    return 1
  fi

  local gradlew="$RUNTIME_KOTLIN_DIR/gradlew"
  if [[ ! -x "$gradlew" ]]; then
    return 1
  fi

  info "Preparing source runtime CLI for saved selection replay..."
  (
    cd "$RUNTIME_KOTLIN_DIR"
    ./gradlew -q :runtime-cli:installDist
  )
}

run_selection_runtime_cli() {
  local runtime_bin=""
  if runtime_cli_supports_selection_replay "$RUNTIME_CLI_BUILD_BIN"; then
    runtime_bin="$RUNTIME_CLI_BUILD_BIN"
  elif runtime_cli_supports_selection_replay "$RUNTIME_CLI_BIN"; then
    runtime_bin="$RUNTIME_CLI_BIN"
  elif build_selection_replay_runtime_cli && runtime_cli_supports_selection_replay "$RUNTIME_CLI_BUILD_BIN"; then
    runtime_bin="$RUNTIME_CLI_BUILD_BIN"
  else
    err "Cannot reuse saved install selections: no Skill Bill runtime CLI is available before cleanup."
    err "The runtime CLI must support 'install replay-last-selection'."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  SKILL_BILL_RUNTIME_EXECUTABLE="$runtime_bin" "$runtime_bin" --home "$HOME" "$@"
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

install_skill_bill_launcher() {
  local launcher_path="$RUNTIME_LAUNCHER_BIN_DIR/skill-bill"
  local marker="# skill-bill managed launcher"

  if [[ -e "$launcher_path" && ! -L "$launcher_path" ]]; then
    if ! grep -qF "$marker" "$launcher_path" 2>/dev/null; then
      warn "  skipped $launcher_path (exists and is not a Skill Bill managed launcher)"
      return 0
    fi
  fi

  rm -f "$launcher_path"
  cat > "$launcher_path" <<LAUNCHER
#!/usr/bin/env bash
$marker
set -euo pipefail

runtime_cli="$RUNTIME_CLI_BIN"
installer_url="https://raw.githubusercontent.com/oila-gmbh/skill-bill/main/install.sh"

shell_quote() {
  case "\$1" in
    (*[!A-Za-z0-9_./:=@%+-]*|'')
      printf "'%s'" "\$(printf '%s' "\$1" | sed "s/'/'\\\\''/g")"
      ;;
    (*)
      printf '%s' "\$1"
      ;;
  esac
}

if [[ "\${1:-}" == "update" ]]; then
  shift
  installer_args=(--reuse-last-selection)
  dry_run=0
  format=text
  release_selected=0
  passthrough=()
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      --dry-run)
        dry_run=1
        passthrough+=("\$1")
        shift
        ;;
      --format)
        format="\${2:-text}"
        passthrough+=("\$1" "\${2:-}")
        shift 2
        ;;
      --format=*)
        format="\${1#--format=}"
        passthrough+=("\$1")
        shift
        ;;
      *)
        if [[ "\$1" == "--release" || "\$1" == --release=* ]]; then
          release_selected=1
        fi
        installer_args+=("\$1")
        passthrough+=("\$1")
        shift
        ;;
    esac
  done

  command="curl -fsSL \$installer_url | bash -s --"
  for arg in "\${installer_args[@]}"; do
    command+=" \$(shell_quote "\$arg")"
  done

  if [[ "\$dry_run" -eq 1 ]]; then
    exec "\$runtime_cli" update "\${passthrough[@]+\${passthrough[@]}}"
  fi

  if [[ "\$release_selected" -eq 0 ]]; then
    check_output="\$("\$runtime_cli" update-check 2>&1)"
    check_status="\$(printf '%s\n' "\$check_output" | awk -F': ' '/^status:/{print \$2; exit}')"
    if [[ "\$check_status" != "update_available" ]]; then
      exec "\$runtime_cli" update "\${passthrough[@]+\${passthrough[@]}}"
    fi
  fi

  exec bash -c "\$command"
fi

exec "\$runtime_cli" "\$@"
LAUNCHER
  chmod +x "$launcher_path"
  ok "  installed skill-bill launcher → $RUNTIME_CLI_BIN"
}

install_runtime_launchers() {
  mkdir -p "$RUNTIME_LAUNCHER_BIN_DIR"
  info "Installing runtime launchers to: $RUNTIME_LAUNCHER_BIN_DIR"
  install_skill_bill_launcher
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
  if [[ "$os" == "linux" ]]; then
    DESKTOP_APP_PREBUILT_PAYLOAD_DIR="$payload_dir"
  fi
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

# Locate the app icon for the Linux desktop entry. Prebuilt (piped curl) installs
# never have a runtime-kotlin checkout, so the icon must come from inside the
# jpackage deb/rpm payload that was already extracted; local/dev installs fall
# back to the icon next to the Gradle build.
linux_desktop_icon_src() {
  if [[ -n "$DESKTOP_APP_PREBUILT_PAYLOAD_DIR" ]]; then
    find "$DESKTOP_APP_PREBUILT_PAYLOAD_DIR" -type f -iname '*.png' \
      \( -ipath '*icons*hicolor*apps*' -o -ipath '*/lib/*' \) -print 2>/dev/null | head -n1
    return 0
  fi
  printf '%s' "$RUNTIME_KOTLIN_DIR/runtime-desktop/icons/icon.png"
}

install_linux_desktop_entry() {
  local app_target="$1"
  local desktop_file="${XDG_DATA_HOME:-$HOME/.local/share}/applications/skillbill.desktop"
  local icon_file="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/512x512/apps/skillbill.png"
  local executable="$app_target/bin/$DESKTOP_APP_DEFAULT_NAME"
  local icon_src
  icon_src="$(linux_desktop_icon_src)"

  if [[ ! -x "$executable" ]]; then
    warn "  skipped Linux desktop entry because executable is missing: $executable"
    return 0
  fi

  mkdir -p "$(dirname "$desktop_file")" "$(dirname "$icon_file")"
  if [[ -n "$icon_src" && -f "$icon_src" ]]; then
    cp "$icon_src" "$icon_file"
  else
    warn "  skipped desktop icon because no packaged icon was found"
  fi
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

prompt_input_available() {
  { : < /dev/tty; } 2>/dev/null
}

read_prompt_input() {
  local target_var="$1"
  if prompt_input_available; then
    IFS= read -r "$target_var" < /dev/tty
  else
    IFS= read -r "$target_var"
  fi
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

apply_default_agent_selection() {
  local detected_output
  local supported_agent

  detected_output="$(run_runtime_cli install detect-agents 2>/dev/null || true)"
  if [[ -n "$(trim_string "$detected_output")" ]]; then
    AGENT_SELECTION_MODE="detected"
    AGENT_NAMES=()
    AGENT_PATHS=()
    info "No agents entered; using detected configured agents."
    return 0
  fi

  info "No configured agents detected; defaulting to every supported agent."
  AGENT_SELECTION_MODE="manual"
  AGENT_NAMES=()
  AGENT_PATHS=()
  for supported_agent in "${SUPPORTED_AGENTS[@]:-}"; do
    add_agent_selection "$supported_agent"
  done
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
    if ! read_prompt_input input; then
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
    printf "${CYAN}▸${NC} Enter agents [detected/all]: "
    if ! read_prompt_input input; then
      input=""
    fi

    if [[ -z "$(trim_string "$input")" ]]; then
      apply_default_agent_selection
      return 0
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
    printf "${CYAN}▸${NC} Enter platforms [base only] (e.g. 1,3 or %s): " "$option_number"
    if ! read_prompt_input input; then
      input=""
    fi

    if [[ -z "$(trim_string "$input")" ]]; then
      PLATFORM_SELECTION_MODE="none"
      SELECTED_PLATFORM_PACKAGES=()
      info "No platforms entered; installing base skills only."
      return 0
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
    if ! read_prompt_input input; then
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

replay_last_install_selection() {
  local output
  local error_output
  local stderr_file
  local kind
  local value
  local extra

  stderr_file="$(mktemp)"
  if ! output="$(
    run_selection_runtime_cli install replay-last-selection \
      --skills "$SKILLS_DIR" \
      --platform-packs "$PLATFORM_PACKS_DIR" 2>"$stderr_file"
  )"; then
    err "Cannot reuse saved install selections."
    if [[ -n "$(trim_string "$output")" ]]; then
      err "$(trim_string "$output")"
    fi
    error_output="$(trim_string "$(cat "$stderr_file")")"
    rm -f "$stderr_file"
    if [[ -n "$error_output" ]]; then
      err "$error_output"
    fi
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  rm -f "$stderr_file"

  AGENT_SELECTION_MODE="manual"
  AGENT_NAMES=()
  AGENT_PATHS=()
  PLATFORM_SELECTION_MODE="none"
  SELECTED_PLATFORM_PACKAGES=()
  TELEMETRY_LEVEL="anonymous"
  MCP_REGISTRATION="register"

  while IFS=$'\t' read -r kind value extra; do
    [[ -z "${kind:-}" ]] && continue
    case "$kind" in
      agent)
        if [[ -z "${value:-}" || -z "${extra:-}" ]]; then
          err "Cannot reuse saved install selections: malformed replay agent entry."
          exit 1
        fi
        AGENT_NAMES+=("$value")
        AGENT_PATHS+=("$extra")
        ;;
      platform-mode)
        case "$value" in
          none|selected|all)
            PLATFORM_SELECTION_MODE="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown platform mode '$value'."
            exit 1
            ;;
        esac
        ;;
      platform)
        if [[ -z "${value:-}" ]]; then
          err "Cannot reuse saved install selections: malformed replay platform entry."
          exit 1
        fi
        SELECTED_PLATFORM_PACKAGES+=("$value")
        ;;
      telemetry)
        case "$value" in
          anonymous|full|off)
            TELEMETRY_LEVEL="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown telemetry level '$value'."
            exit 1
            ;;
        esac
        ;;
      mcp)
        case "$value" in
          register|skip)
            MCP_REGISTRATION="$value"
            ;;
          *)
            err "Cannot reuse saved install selections: unknown MCP registration choice '$value'."
            exit 1
            ;;
        esac
        ;;
      *)
        err "Cannot reuse saved install selections: unknown replay field '$kind'."
        exit 1
        ;;
    esac
  done <<< "$output"

  if [[ ${#AGENT_NAMES[@]} -eq 0 ]]; then
    err "Cannot reuse saved install selections: saved selection has no agents."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  if [[ "$PLATFORM_SELECTION_MODE" == "selected" && ${#SELECTED_PLATFORM_PACKAGES[@]} -eq 0 ]]; then
    err "Cannot reuse saved install selections: selected platform mode has no saved platform slugs."
    err "Run ./install.sh without --reuse-last-selection to choose install options again."
    exit 1
  fi
  if [[ "$PLATFORM_SELECTION_MODE" == "all" ]]; then
    SELECTED_PLATFORM_PACKAGES=("${PLATFORM_PACKAGES[@]}")
  fi

  ok "Reusing latest successful install selections from $SKILL_BILL_STATE_DIR/install-selection.json"
}

build_runtime_install_args() {
  local i

  RUNTIME_INSTALL_ARGS=(
    install
    apply
    --repo-root "$SKILL_BILL_STATE_DIR"
    --skills "$SKILL_BILL_STATE_DIR/skills"
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs"
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
      # claude pins no single --agent-target so the runtime fans skills across every discovered
      # config root (~/.claude plus ~/.claude-<name> profiles); other agents keep their single path.
      if [[ "${AGENT_NAMES[$i]}" != "claude" ]]; then
        RUNTIME_INSTALL_ARGS+=(--agent-target "${AGENT_NAMES[$i]}=${AGENT_PATHS[$i]}")
      fi
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

apply_external_addon_overlay() {
  if [[ ! -d "$SKILL_BILL_STATE_DIR/platform-packs" ]]; then
    return 0
  fi
  info "Applying external addon overlay onto installed platform packs."
  local status=0
  run_runtime_cli install apply-external-addons \
    --repo-root "$SKILL_BILL_STATE_DIR" \
    --platform-packs "$SKILL_BILL_STATE_DIR/platform-packs" || status=$?
  if [[ "$status" -ne 0 ]]; then
    err "External addon overlay failed; aborting the install."
    return "$status"
  fi
  ok "External addon overlay completed"
}

print_claude_roots_summary() {
  local roots root
  roots="$(run_runtime_cli install claude-roots 2>/dev/null)" || return 0
  [[ -z "$roots" ]] && return 0
  while IFS= read -r root; do
    [[ -z "$root" ]] && continue
    info "Claude config root: $root"
  done <<< "$roots"
}

print_postinstall_path_warning() {
  if path_contains_dir "$RUNTIME_LAUNCHER_BIN_DIR"; then
    return 0
  fi
  local rc_file
  case "${SHELL:-}" in
    */fish) rc_file="${HOME}/.config/fish/config.fish" ;;
    */zsh)  rc_file="${HOME}/.zshrc" ;;
    *)      rc_file="${HOME}/.bashrc" ;;
  esac
  warn "Launcher directory is not on PATH: $RUNTIME_LAUNCHER_BIN_DIR"
  case "${SHELL:-}" in
    */fish)
      warn "  Add it permanently (copy-paste, or add to $rc_file):"
      warn "    fish_add_path $RUNTIME_LAUNCHER_BIN_DIR"
      ;;
    *)
      warn "  Add it for the current session (copy-paste):"
      warn "    export PATH=\"$RUNTIME_LAUNCHER_BIN_DIR:\$PATH\""
      warn "  Or add that line to $rc_file, then: source $rc_file"
      ;;
  esac
}

print_postinstall_agent_warning() {
  [[ "$AGENT_SELECTION_MODE" != "detected" ]] && return 0
  local detected
  detected="$(run_runtime_cli install detect-agents 2>/dev/null)" || return 0
  [[ -n "$detected" ]] && return 0
  warn "No supported agents were detected — skills are not linked to any agent."
  warn "  Install a supported agent (claude, codex, copilot, opencode, junie, or zcode),"
  warn "  then re-run ./install.sh. Choose 'manual' to select your agent explicitly."
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
  bundle_bootstrap_if_needed
  print_install_plan "full"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    build_platform_packages
    replay_last_install_selection
  fi
  clean_install_state_if_requested
  migrate_legacy_config_to_durable_path
  run_pre_install_uninstall
  copy_in_authored_source
  install_runtime_distributions
  reconcile_and_commit_authored_source
  if [[ "$REUSE_LAST_SELECTION" -ne 1 ]]; then
    build_platform_packages
  fi

  echo ""
  printf "${CYAN}━━━ Skill Bill Installer ━━━${NC}\n"
  echo ""
  info "Supported agents: copilot, claude, codex, opencode, junie, zcode"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Install behavior: reuse saved choices, then delegate planning and apply to the Kotlin runtime."
  else
    info "Install behavior: collect choices, then delegate planning and apply to the Kotlin runtime."
    prompt_for_agent_selection
    prompt_for_platform_selection
    prompt_for_telemetry_preference
  fi
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
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Selections:     reused latest successful install selection"
  fi
  echo ""

  apply_external_addon_overlay
  apply_runtime_install
  install_desktop_app

  printf "${GREEN}━━━ Installation complete ━━━${NC}\n"
  echo ""
  info "Source of truth: $PLUGIN_DIR/skills/"
  info "Staging cache:   $SKILL_BILL_STATE_DIR/installed-skills"
  # SKILL-76 AC-7: report every reconcile conflict that was accepted (overwritten)
  # in the install summary. No sidecar file; the summary is the single record.
  if [[ -n "${RECONCILE_CONFLICT_PATHS:-}" ]]; then
    info "Reconcile:       overwrote local edits with upstream for conflicting skills:"
    local conflict_path
    for conflict_path in $RECONCILE_CONFLICT_PATHS; do
      info "  conflict:      $conflict_path"
    done
  fi
  info "Platforms:       $SELECTED_PLATFORM_LABEL"
  info "Launchers:       $RUNTIME_LAUNCHER_BIN_DIR/skill-bill, $RUNTIME_LAUNCHER_BIN_DIR/skill-bill-mcp"
  info "Telemetry:       $TELEMETRY_LEVEL"
  info "MCP:             $MCP_REGISTRATION"
  if [[ "$REUSE_LAST_SELECTION" -eq 1 ]]; then
    info "Selections:      reused latest successful install selection"
  fi
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

  print_claude_roots_summary

  print_desktop_unsigned_hint

  print_postinstall_path_warning
  print_postinstall_agent_warning

  echo ""
  info "Edit skills in: $PLUGIN_DIR/skills/"
  if [[ "$TELEMETRY_LEVEL" != "off" ]]; then
    info "Telemetry uses the default Skill Bill relay automatically. Override it with SKILL_BILL_TELEMETRY_PROXY_URL or ~/.skill-bill/config.json."
  fi
  info "Add the desktop app later (no full reinstall) with: $PLUGIN_DIR/install.sh --desktop-app-only"
  info "Run './install.sh' again to reinstall with different agent, platform, telemetry, or desktop app choices."
  info "Next step:       open your agent and run /bill-feature-task or /bill-code-review"
  echo ""
}

parse_args "$@"
bootstrap_release_installer_if_needed
resolve_install_source
if [[ "$DESKTOP_APP_ONLY" -eq 1 ]]; then
  run_desktop_only_install
else
  run_full_install
fi
