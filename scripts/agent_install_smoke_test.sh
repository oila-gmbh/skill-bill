#!/usr/bin/env bash
# Per-agent install smoke test: runs `install apply` into a throwaway home for each
# agent, then asserts skills landed, native subagents linked in the right format, and
# MCP registration did not fail. Reuses the already-installed runtime (no download) and
# never touches the caller's real agent directories.
#
# Usage: scripts/agent_install_smoke_test.sh [agent ...]   (default: all six)
#   SKILL_BILL_BIN, SKILL_BILL_RUNTIME_ROOT override discovery.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${SKILL_BILL_BIN:-$HOME/.local/bin/skill-bill}"
RUNTIME_ROOT="${SKILL_BILL_RUNTIME_ROOT:-$HOME/.skill-bill/runtime}"
MCP_BIN="$RUNTIME_ROOT/runtime-mcp/bin/runtime-mcp"
if [[ $# -gt 0 ]]; then AGENTS=("$@"); else AGENTS=(copilot claude codex opencode junie zcode); fi

[[ -x "$BIN" ]] || { echo "FATAL: skill-bill not executable at $BIN" >&2; exit 2; }
[[ -d "$RUNTIME_ROOT" ]] || { echo "FATAL: no installed runtime at $RUNTIME_ROOT (run ./install.sh first)" >&2; exit 2; }
[[ -x "$MCP_BIN" ]] || echo "WARN: mcp bin missing at $MCP_BIN — MCP checks will report failure" >&2

declare -a RESULTS
overall=0

for agent in "${AGENTS[@]}"; do
  W="$(mktemp -d)"; FAKE="$W/home"; mkdir -p "$FAKE"
  # seed the agent's root so canonical (non-fallback) paths resolve, as if it were installed
  case "$agent" in
    copilot)  mkdir -p "$FAKE/.copilot" ;;
    claude)   mkdir -p "$FAKE/.claude" ;;
    codex)    mkdir -p "$FAKE/.codex" ;;
    opencode) mkdir -p "$FAKE/.config/opencode" ;;
    junie)    mkdir -p "$FAKE/.junie" ;;
    zcode)    mkdir -p "$FAKE/.zcode" ;;
  esac

  echo "── $agent ──────────────────────────────────────────────"
  rc=0
  "$BIN" --home "$FAKE" install apply \
    --repo-root "$REPO_ROOT" \
    --agent-mode manual --agent "$agent" \
    --platform-mode all \
    --telemetry off \
    --mcp register \
    --runtime-install-root "$RUNTIME_ROOT" \
    --runtime-mcp-bin "$MCP_BIN" \
    --format json >"$W/out.json" 2>"$W/err.txt"
  rc=$?

  python3 - "$W/out.json" "$agent" "$rc" <<'PY'
import json, sys, os, glob
out_path, agent, rc = sys.argv[1], sys.argv[2], int(sys.argv[3])
checks = []
def chk(name, ok, detail=""): checks.append((name, bool(ok), detail))

chk("apply_exit_0", rc == 0, f"rc={rc}")
data = None
try:
    data = json.load(open(out_path))
    chk("json_parse", True)
except Exception as e:
    chk("json_parse", False, str(e))

if data is not None:
    chk("no_top_level_failures", not data.get("failures"), str(data.get("failures")))

    mine = [a for a in data.get("agents", []) if a.get("agent") == agent]
    chk("agent_applied", bool(mine))
    skills_path = mine[0].get("path") if mine else None
    if skills_path:
        n = len(glob.glob(os.path.join(skills_path, "*")))
        chk("skills_installed", n > 0, f"{n} skills in {skills_path}")

    nas = [x for x in data.get("native_agents", []) if x.get("agent") == agent]
    if nas:
        bad = [x for x in nas if x.get("status") != "linked" or x.get("issue")]
        chk("native_agents_linked", not bad, f"{len(nas)} total, {len(bad)} not-linked")
        ext = ".toml" if agent == "codex" else ".md"
        missing = [x["path"] for x in nas if not os.path.exists(x["path"])]
        wrong = [x["path"] for x in nas if not x["path"].endswith(ext)]
        chk("native_files_on_disk", not missing, f"{len(missing)} missing")
        chk(f"native_files_are_{ext.strip('.')}", not wrong, f"{len(wrong)} wrong ext")
        sample = nas[0]["path"]
        if os.path.exists(sample):
            txt = open(sample).read()
            if ext == ".toml":
                try:
                    import tomllib; tomllib.loads(txt); chk("native_sample_parses", True)
                except Exception as e:
                    chk("native_sample_parses", False, str(e))
            else:
                chk("native_sample_has_frontmatter", txt.lstrip().startswith("---"))

    mcp = data.get("mcp_registration", {})
    outcomes = [o for o in mcp.get("outcomes", []) if o.get("agent") == agent]
    failed = [o for o in outcomes if o.get("status") == "failed"]
    chk("mcp_no_failure", not failed,
        "; ".join((o.get("issue") or {}).get("message", o.get("message", "")) for o in failed))
    for o in outcomes:
        cp = o.get("config_path")
        if cp:
            chk("mcp_config_on_disk", os.path.exists(cp), cp)
            if os.path.exists(cp) and cp.endswith(".json"):
                try:
                    json.load(open(cp)); chk("mcp_config_valid_json", True)
                except Exception as e:
                    chk("mcp_config_valid_json", False, str(e))

allok = all(ok for _, ok, _ in checks)
for name, ok, detail in checks:
    tag = "PASS" if ok else "FAIL"
    suffix = f" — {detail}" if (detail and not ok) else ""
    print(f"    [{tag}] {name}{suffix}")
print(f"  RESULT: {'PASS' if allok else 'FAIL'}")
sys.exit(0 if allok else 1)
PY
  agent_rc=$?
  if [[ $agent_rc -eq 0 ]]; then RESULTS+=("$agent: PASS"); else RESULTS+=("$agent: FAIL"); overall=1; fi
  [[ $rc -ne 0 ]] && { echo "  --- apply stderr (tail) ---"; tail -5 "$W/err.txt" | sed 's/^/    /'; }
  rm -rf "$W"
  echo
done

echo "════ summary ════"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo
[[ $overall -eq 0 ]] && echo "All agents passed the install smoke test." || echo "Some agents FAILED — see per-agent checks above."
exit $overall
