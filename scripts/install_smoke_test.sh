#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_SH="$REPO_ROOT/install.sh"

FAKE_HOME=""
RELEASE_DIR=""
STUB_TMP=""
WORK_TMPDIRS=()
cleanup() {
  [[ -n "$FAKE_HOME" ]] && rm -rf "$FAKE_HOME"
  [[ -n "$RELEASE_DIR" ]] && rm -rf "$RELEASE_DIR"
  [[ -n "$STUB_TMP" ]] && rm -f "$STUB_TMP"
  local d; for d in "${WORK_TMPDIRS[@]+"${WORK_TMPDIRS[@]}"}"; do rm -rf "$d"; done
}
trap cleanup EXIT

pass() { printf '[PASS] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; exit 1; }

assert_file_exists() {
  local label="$1" path="$2"
  [[ -e "$path" ]] && { pass "$label"; return; }
  fail "$label — not found: $path"
}

assert_file_absent() {
  local label="$1" path="$2"
  [[ ! -e "$path" ]] && { pass "$label"; return; }
  fail "$label — unexpectedly present: $path"
}

host_token() {
  local s m
  s="$(uname -s)" m="$(uname -m)"
  case "$s" in
    Darwin) [[ "$m" == arm64 ]] && printf 'macos-arm64' || printf 'macos-x64' ;;
    Linux)  printf 'linux-x64' ;;
    MINGW*|MSYS*|CYGWIN*) printf 'windows-x64' ;;
    *)      printf 'linux-x64' ;;
  esac
}

make_stub_runtime_cli() {
  local stub_path="$1"
  cat >"$stub_path" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

args=("$@")
i=0
home_dir=""
cmd_parts=()

while [[ $i -lt ${#args[@]} ]]; do
  arg="${args[$i]}"
  if [[ "$arg" == "--home" ]]; then
    i=$(( i + 1 ))
    home_dir="${args[$i]}"
  else
    cmd_parts+=("$arg")
  fi
  i=$(( i + 1 ))
done

[[ ${#cmd_parts[@]} -eq 0 ]] && { echo "stub: no subcommand" >&2; exit 1; }

sub="${cmd_parts[0]:-}"
sub2="${cmd_parts[1]:-}"
cmd="$sub${sub2:+ $sub2}"

flag_value() {
  local flag="$1"; shift
  local a found=0
  for a in "$@"; do
    [[ $found -eq 1 ]] && { printf '%s' "$a"; return; }
    [[ "$a" == "$flag" ]] && found=1
  done
}

has_flag() {
  local flag="$1"; shift
  local a
  for a in "$@"; do [[ "$a" == "$flag" ]] && return 0; done
  return 1
}

case "$cmd" in
  "version")
    echo "skill-bill version 0.0.0-smoke"
    ;;

  "install")
    if has_flag "--help" "${cmd_parts[@]}"; then
      echo "  replay-last-selection   replay the last successful install selection"
      echo "  reconcile               reconcile authored source"
      echo "  apply                   apply install"
      echo "  claude-roots            list claude config roots"
      echo "  detect-agents           detect configured agents"
    fi
    ;;

  "install reconcile")
    if has_flag "--apply" "${cmd_parts[@]}"; then
      upstream_skills="$(flag_value --upstream-skills "${cmd_parts[@]}")"
      skills="$(flag_value --skills "${cmd_parts[@]}")"
      if [[ -n "${upstream_skills:-}" && -n "${skills:-}" && -d "$upstream_skills" ]]; then
        mkdir -p "$skills"
        if has_flag "--accept-conflicts" "${cmd_parts[@]}"; then
          cp -R "$upstream_skills/." "$skills/"
        else
          cp -Rn "$upstream_skills/." "$skills/"
        fi
      fi
    else
      if [[ "${SKILL_BILL_SMOKE_HAS_CONFLICTS:-}" == "1" ]]; then
        echo "reconcile_outcome: kind=conflict upstream_hash=aaa path=skills/bill-code-review/content.md"
        echo "reconcile_summary: has_conflicts=true conflict_count=1"
      else
        echo "reconcile_summary: has_conflicts=false conflict_count=0"
      fi
    fi
    ;;

  "install apply")
    skills_src="$(flag_value --skills "${cmd_parts[@]}")"
    effective_home="${home_dir:-$HOME}"
    if has_flag "--agent-target" "${cmd_parts[@]}"; then
      agent_target_raw="$(flag_value --agent-target "${cmd_parts[@]}")"
      target_dir="${agent_target_raw#*=}"
    else
      target_dir="$effective_home/.claude"
    fi
    if [[ -n "${skills_src:-}" && -d "$skills_src" ]]; then
      rm -rf "$target_dir/skills"
      mkdir -p "$target_dir/skills"
      cp -R "$skills_src/." "$target_dir/skills/"
    fi
    ;;

  "install agent-path")
    agent="${cmd_parts[2]:-}"
    effective_home="${home_dir:-$HOME}"
    case "$agent" in
      copilot)  echo "$effective_home/.copilot/skills" ;;
      claude)   echo "$effective_home/.claude/skills" ;;
      codex)
        if [[ -e "$effective_home/.codex" || -e "$effective_home/.codex/skills" ]]; then
          echo "$effective_home/.codex/skills"
        else
          echo "$effective_home/.agents/skills"
        fi
        ;;
      opencode) echo "$effective_home/.config/opencode/skills" ;;
      junie)    echo "$effective_home/.junie/skills" ;;
      *) echo "unknown agent: $agent" >&2; exit 1 ;;
    esac
    ;;

  "install claude-roots")
    echo "${home_dir:-$HOME}/.claude"
    ;;

  "install detect-agents")
    ;;

  *)
    ;;
esac
STUB
  chmod +x "$stub_path"
}

make_runtime_zip() {
  local token="$1" name="$2" bin_name="$3" stub_src="$4" out_dir="$5"
  local work zip_name
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  mkdir -p "$work/$name-0.0.0-smoke/bin"
  cp "$stub_src" "$work/$name-0.0.0-smoke/bin/$bin_name"
  zip_name="${name}-0.0.0-smoke-${token}.zip"
  python3 - "$work" "$name-0.0.0-smoke" "$out_dir/$zip_name" <<'PY'
import sys, os, zipfile
src_root, top_dir, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for dirpath, _, files in os.walk(os.path.join(src_root, top_dir)):
        for fname in files:
            fpath = os.path.join(dirpath, fname)
            arcname = os.path.relpath(fpath, src_root)
            info = zipfile.ZipInfo(arcname)
            st = os.stat(fpath)
            info.external_attr = (st.st_mode & 0xFFFF) << 16
            with open(fpath, 'rb') as f:
                zf.writestr(info, f.read())
PY
  rm -rf "$work"
  local sha
  sha="$(cd "$out_dir" && sha256sum "$zip_name" 2>/dev/null || shasum -a 256 "$zip_name")"
  printf '%s' "$sha" >"$out_dir/$zip_name.sha256"
}

# Build the skills-bundle asset a headless/piped install fetches when it cannot trust a
# CWD-relative skills/ dir. Mirrors the real release bundle (see .github/workflows/
# release.yml): tars the repo's top-level skills/, platform-packs/, orchestration/, and
# uninstall.sh so the extracted root satisfies both install.sh's
# `[[ -d "$extract_dir/skills" ]]` branch and the downstream orchestration/uninstall
# staging. Writes a sibling .sha256 in the same form as make_runtime_zip.
make_skills_bundle() {
  local out_dir="$1"
  local bundle_name="skill-bill-skills-0.0.0-smoke.tar.gz"
  (cd "$REPO_ROOT" && tar -czf "$out_dir/$bundle_name" skills platform-packs orchestration uninstall.sh)
  local sha
  sha="$(cd "$out_dir" && sha256sum "$bundle_name" 2>/dev/null || shasum -a 256 "$bundle_name")"
  printf '%s' "$sha" >"$out_dir/$bundle_name.sha256"
}

setup_release_dir() {
  local token="$1" stub_src="$2"
  RELEASE_DIR="$(mktemp -d)"
  make_runtime_zip "$token" "runtime-cli" "runtime-cli" "$stub_src" "$RELEASE_DIR"
  make_runtime_zip "$token" "runtime-mcp" "runtime-mcp" "$stub_src" "$RELEASE_DIR"
  make_skills_bundle "$RELEASE_DIR"
}

seed_selection_json() {
  local fake_home="$1"
  local mcp_bin="$fake_home/.skill-bill/runtime/runtime-mcp/bin/runtime-mcp"
  mkdir -p "$fake_home/.skill-bill"
  printf '%s\n' \
    '{"contract_version":"1.0","selected_agents":["claude"],"platform_pack_selection":{"mode":"none","selected_slugs":[]},"telemetry_level":"off","mcp_registration":{"register":false,"runtime_mcp_bin":"'"$mcp_bin"'"}}' \
    >"$fake_home/.skill-bill/install-selection.json"
}

run_install() {
  local fake_home="$1"; shift
  local extra_env=()
  while [[ $# -gt 0 && "$1" == *=* ]]; do
    extra_env+=("$1"); shift
  done
  env \
    HOME="$fake_home" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
    "${extra_env[@]}" \
    bash "$INSTALL_SH" --reuse-last-selection --no-desktop-app "$@" \
    </dev/null
}

run_interactive_install_with_blank_defaults() {
  local fake_home="$1"
  env \
    HOME="$fake_home" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
    bash "$INSTALL_SH" --no-desktop-app \
    <<< $'\n\n\n\n'
}

run_piped_install_with_eof_defaults() {
  local fake_home="$1"
  (
    cd "$REPO_ROOT"
    env \
      HOME="$fake_home" \
      SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
      SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
      SKILL_BILL_BIN_DIR="$fake_home/.local/bin" \
      bash -c 'cat install.sh | bash' \
      </dev/null
  )
}

run_piped_bootstrap_latest_release() {
  local fake_home="$1"
  local work
  work="$(mktemp -d)"
  WORK_TMPDIRS+=("$work")
  cat >"$work/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
url="${@: -1}"
case "$url" in
  https://api.github.com/repos/Sermilion/skill-bill/releases/latest)
    printf '{"tag_name":"v9.9.9"}\n'
    ;;
  https://raw.githubusercontent.com/Sermilion/skill-bill/v9.9.9/install.sh)
    cat <<'INSTALLER'
#!/usr/bin/env bash
set -euo pipefail
printf 'release_bootstrap=%s\n' "${SKILL_BILL_RELEASE_INSTALLER_BOOTSTRAPPED:-}"
printf 'release_args:'
for arg in "$@"; do
  printf ' [%s]' "$arg"
done
printf '\n'
INSTALLER
    ;;
  *)
    printf 'unexpected curl url: %s\n' "$url" >&2
    exit 1
    ;;
esac
CURL
  chmod +x "$work/curl"
  (
    cd "$REPO_ROOT"
    env \
      HOME="$fake_home" \
      PATH="$work:$PATH" \
      bash -c 'cat install.sh | bash -s -- --no-desktop-app' \
      </dev/null
  )
}

echo "=== install smoke test ==="
echo ""

TOKEN="$(host_token)"
STUB_TMP="$(mktemp)"
make_stub_runtime_cli "$STUB_TMP"
setup_release_dir "$TOKEN" "$STUB_TMP"

echo "--- scenario 1: piped main installer bootstraps latest release installer ---"
FAKE_HOME="$(mktemp -d)"
BOOTSTRAP_OUTPUT="$(run_piped_bootstrap_latest_release "$FAKE_HOME")"
pass "piped main installer bootstrapped latest release installer"

if [[ "$BOOTSTRAP_OUTPUT" == *"Standalone installer: using release installer v9.9.9."* ]]; then
  pass "bootstrap reports latest release tag"
else
  fail "bootstrap did not report latest release tag"
fi

if [[ "$BOOTSTRAP_OUTPUT" == *"release_bootstrap=1"* &&
  "$BOOTSTRAP_OUTPUT" == *"release_args: [--release] [v9.9.9] [--no-desktop-app]"* ]]; then
  pass "release installer receives pinned latest release args"
else
  fail "release installer args unexpected: $BOOTSTRAP_OUTPUT"
fi

echo ""
echo "--- scenario 2: blank interactive defaults ---"
FAKE_HOME="$(mktemp -d)"
INTERACTIVE_OUTPUT="$(run_interactive_install_with_blank_defaults "$FAKE_HOME")"
pass "interactive install with blank defaults exited 0"

if [[ "$INTERACTIVE_OUTPUT" == *"Agents:         copilot, claude, codex, opencode, junie"* ]]; then
  pass "blank agent selection defaults to all when none detected"
else
  fail "blank agent selection summary unexpected"
fi

if [[ "$INTERACTIVE_OUTPUT" == *"Platforms:      base only"* ]]; then
  pass "blank platform selection defaults to base only"
else
  fail "blank platform selection summary unexpected"
fi

echo ""
echo "--- scenario 3: piped install EOF defaults ---"
FAKE_HOME="$(mktemp -d)"
PIPED_OUTPUT="$(run_piped_install_with_eof_defaults "$FAKE_HOME")"
pass "piped install with EOF defaults exited 0"

if [[ "$PIPED_OUTPUT" == *"Agents:         copilot, claude, codex, opencode, junie"* ]]; then
  pass "piped EOF agent selection defaults to all when none detected"
else
  fail "piped EOF agent selection summary unexpected"
fi

if [[ "$PIPED_OUTPUT" == *"Platforms:      base only"* ]]; then
  pass "piped EOF platform selection defaults to base only"
else
  fail "piped EOF platform selection summary unexpected"
fi

echo ""
echo "--- scenario 4: base install (AC#1) ---"
FAKE_HOME="$(mktemp -d)"
mkdir -p "$FAKE_HOME/.claude"
seed_selection_json "$FAKE_HOME"

run_install "$FAKE_HOME"
pass "install.sh exited 0"

LAUNCHER="$FAKE_HOME/.local/bin/skill-bill"
assert_file_exists "launcher exists" "$LAUNCHER"

LAUNCHER_VERSION="$(HOME="$FAKE_HOME" "$LAUNCHER" --home "$FAKE_HOME" version 2>/dev/null || true)"
if [[ "$LAUNCHER_VERSION" == *"skill-bill"* ]]; then
  pass "skill-bill version succeeds"
else
  fail "skill-bill version output unexpected: '$LAUNCHER_VERSION'"
fi

SKILL_FIRST="$(find "$FAKE_HOME/.claude/skills" -mindepth 1 -maxdepth 1 2>/dev/null | head -n1 || true)"
if [[ -n "${SKILL_FIRST:-}" ]]; then
  pass "at least one skill installed under agent directory"
else
  fail "no skills found under $FAKE_HOME/.claude/skills"
fi

echo ""
echo "--- scenario 5: --clean flag (AC#2) ---"

SENTINEL="$FAKE_HOME/.skill-bill/skills/__smoke_extra_file__"
mkdir -p "$(dirname "$SENTINEL")"
printf 'extra' >"$SENTINEL"
assert_file_exists "sentinel planted" "$SENTINEL"

FAKE_AGENT_SKILL="$FAKE_HOME/.claude/skills/__smoke_stale_agent_skill__"
mkdir -p "$FAKE_AGENT_SKILL"
printf 'stale' >"$FAKE_AGENT_SKILL/content.md"
assert_file_exists "stale agent-dir skill planted" "$FAKE_AGENT_SKILL/content.md"

run_install "$FAKE_HOME" --clean
pass "install.sh --clean exited 0"

assert_file_absent "sentinel removed after --clean" "$SENTINEL"

assert_file_absent "stale agent-dir skill removed after --clean" "$FAKE_AGENT_SKILL"

SKILL_FIRST_CLEAN="$(find "$FAKE_HOME/.claude/skills" -mindepth 1 -maxdepth 1 2>/dev/null | head -n1 || true)"
if [[ -n "${SKILL_FIRST_CLEAN:-}" ]]; then
  pass "skills still present after --clean"
else
  fail "no skills found after --clean under $FAKE_HOME/.claude/skills"
fi

echo ""
echo "--- scenario 6: --prefer-upstream conflict (AC#3) ---"

TARGET_SKILL="$FAKE_HOME/.skill-bill/skills/bill-code-review"
if [[ ! -d "$TARGET_SKILL" ]]; then
  TARGET_SKILL="$(find "$FAKE_HOME/.skill-bill/skills" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -n1 || true)"
fi
if [[ -z "${TARGET_SKILL:-}" ]]; then
  fail "no skill directory found under $FAKE_HOME/.skill-bill/skills to seed a local edit"
fi

CONTENT_FILE="$(find "$TARGET_SKILL" -name 'content.md' 2>/dev/null | head -n1 || true)"
if [[ -z "${CONTENT_FILE:-}" ]]; then
  CONTENT_FILE="$TARGET_SKILL/content.md"
  printf '# upstream content\n' >"$CONTENT_FILE"
fi

ORIGINAL_CONTENT="$(cat "$CONTENT_FILE")"
printf '\n# smoke local edit\n' >>"$CONTENT_FILE"
pass "local edit seeded in $(basename "$CONTENT_FILE") under $(basename "$TARGET_SKILL")"

env \
  HOME="$FAKE_HOME" \
  SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
  SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
  SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
  SKILL_BILL_SMOKE_HAS_CONFLICTS=1 \
  bash "$INSTALL_SH" --reuse-last-selection --no-desktop-app --prefer-upstream \
  </dev/null
pass "install.sh --prefer-upstream exited 0"

AFTER_CONTENT="$(cat "$CONTENT_FILE")"
if [[ "$AFTER_CONTENT" == "$ORIGINAL_CONTENT" ]]; then
  pass "upstream version restored after --prefer-upstream (local edit overwritten)"
else
  fail "content not restored after --prefer-upstream; file still contains local edit"
fi

echo ""
echo "--- scenario 7: piped install from a foreign dir ignores stray skills/ (AC#2) ---"
FAKE_HOME="$(mktemp -d)"
FOREIGN_DIR="$(mktemp -d)"
WORK_TMPDIRS+=("$FOREIGN_DIR")
mkdir -p "$FOREIGN_DIR/skills/__stray__"
printf 'stray local skill\n' >"$FOREIGN_DIR/skills/__stray__/content.md"

FOREIGN_OUTPUT="$(
  cd "$FOREIGN_DIR"
  env \
    HOME="$FAKE_HOME" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 \
    SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
    bash -c 'cat "$1" | bash -s -- --no-desktop-app' _ "$INSTALL_SH" \
    </dev/null
)"
pass "piped install from foreign dir exited 0"

if [[ "$FOREIGN_OUTPUT" == *"Skills bundle extracted; PLUGIN_DIR set to"* ]]; then
  pass "piped install fetched the skills bundle despite a stray skills/ in CWD"
else
  fail "piped install did not fetch the skills bundle: $FOREIGN_OUTPUT"
fi

BUNDLE_PLUGIN_DIR="$(printf '%s\n' "$FOREIGN_OUTPUT" | sed -n 's/.*Skills bundle extracted; PLUGIN_DIR set to: //p' | tail -n1)"
if [[ -n "$BUNDLE_PLUGIN_DIR" && "$BUNDLE_PLUGIN_DIR" != "$FOREIGN_DIR" ]]; then
  pass "PLUGIN_DIR re-pointed away from the foreign CWD"
else
  fail "PLUGIN_DIR not re-pointed away from foreign CWD: '$BUNDLE_PLUGIN_DIR'"
fi

echo ""
echo "--- scenario 8: fresh machine footprint gate skips pre-install cleanup (AC#3) ---"
FAKE_HOME="$(mktemp -d)"

GATE_OUTPUT="$(
  env \
    HOME="$FAKE_HOME" \
    SKILL_BILL_RELEASE_DIR="$RELEASE_DIR" \
    SKILL_BILL_BIN_DIR="$FAKE_HOME/.local/bin" \
    bash "$INSTALL_SH" --no-desktop-app \
    <<< $'\n\n\n\n'
)"
pass "fresh-machine interactive install exited 0"

if [[ "$GATE_OUTPUT" == *"No prior Skill Bill install detected"* ]]; then
  pass "footprint gate skipped pre-install cleanup on a fresh machine"
else
  fail "footprint gate message not found: $GATE_OUTPUT"
fi

if [[ "$GATE_OUTPUT" == *"Agents:"* ]]; then
  pass "fresh-machine install completed end-to-end"
else
  fail "fresh-machine install did not complete: $GATE_OUTPUT"
fi

echo ""
echo "=== all scenarios passed ==="
