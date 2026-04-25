#!/usr/bin/env bash
# Bootstrap for the skill-bill MCP server (invoked by .mcp.json).
#
# Requirements: Python >=3.10 available on PATH (as `python3`, `python3.10`,
# `python3.11`, `python3.12`, or `python3.13`). Uses only Python's stdlib
# (`venv`, `ensurepip`) — no third-party installer required.
#
# On first run, provisions .venv/ and installs the project into it. On later
# runs, just exec's the cached interpreter (fast path, no overhead).
#
# On any failure (missing Python, pip install fails, etc.) this script prints
# a clear diagnostic to stderr and sleeps before exiting, so the MCP client's
# auto-restart loop is throttled (~60s between retries) instead of spamming
# the IDE with hundreds of restart notifications per second.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv_dir="$repo_root/.venv"
venv_python="$venv_dir/bin/python"

fail_throttled() {
  # Print heredoc from stdin to stderr, then sleep to throttle retries.
  cat >&2
  echo "" >&2
  echo "[skill-bill mcp] Sleeping 60s before exit to throttle the MCP auto-restart loop." >&2
  sleep 60
  exit 127
}

# --- Find a Python >=3.10 interpreter ---------------------------------------
find_python() {
  local candidate
  # Prefer newer versions first. Fall back to generic `python3` last and
  # verify its version meets the floor.
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

# --- Ensure .venv exists with deps installed --------------------------------
ensure_venv() {
  local system_python
  if ! system_python="$(find_python)"; then
    fail_throttled <<EOF
[skill-bill mcp] ERROR: No Python >=3.10 found on PATH.

The skill-bill MCP server requires Python 3.10 or newer. Checked for:
  python3.13, python3.12, python3.11, python3.10, python3

To fix:
  macOS (Homebrew):  brew install python@3.12
  Linux (apt):       sudo apt install python3.12 python3.12-venv
  Other platforms:   https://www.python.org/downloads/

After installing, restart this MCP server from your IDE.
EOF
  fi

  # Fast path: venv exists and the `mcp` dep imports cleanly.
  if [ -x "$venv_python" ] && "$venv_python" -c 'import mcp' >/dev/null 2>&1; then
    return 0
  fi

  echo "[skill-bill mcp] First-run setup: creating .venv/ and installing deps (using $system_python)..." >&2

  # Recreate venv if it exists but is broken/stale.
  if [ -d "$venv_dir" ] && [ ! -x "$venv_python" ]; then
    rm -rf "$venv_dir"
  fi

  if [ ! -d "$venv_dir" ]; then
    if ! "$system_python" -m venv "$venv_dir" >&2; then
      fail_throttled <<EOF
[skill-bill mcp] ERROR: \`$system_python -m venv $venv_dir\` failed.

On Debian/Ubuntu this usually means the venv module isn't installed:
  sudo apt install python3-venv

Check the error output above for specifics.
EOF
    fi
  fi

  if ! "$venv_python" -m pip install --quiet --upgrade pip >&2; then
    fail_throttled <<EOF
[skill-bill mcp] ERROR: Failed to upgrade pip inside $venv_dir.
Check the error output above for specifics.
EOF
  fi

  if ! "$venv_python" -m pip install --quiet -e "$repo_root" >&2; then
    fail_throttled <<EOF
[skill-bill mcp] ERROR: Failed to install skill-bill into $venv_dir.
Check the error output above for specifics. You can retry manually with:
  $system_python -m venv $venv_dir
  $venv_python -m pip install -e $repo_root
EOF
  fi

  echo "[skill-bill mcp] Setup complete." >&2
}

ensure_venv
exec "$venv_python" -c 'from skill_bill.launcher import mcp_main; mcp_main()'
