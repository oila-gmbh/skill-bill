# SKILL-97 — Fix fresh `curl … | bash` install path

## Outcome

A first-time user can install Skill Bill on a clean machine with the documented
one-liner —

```bash
curl -fsSL https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh | bash
```

— from any working directory, with no manual arguments or environment-variable
workarounds. Today this command fails at three independent points along the
release/bundle install path, none of which are exercised by the existing smoke
test.

## Background

A real fresh install (reported by an external user on macOS) failed three times
in sequence, each failure masking the next:

1. **`ORIGINAL_ARGS[@]: unbound variable`** — With no positional arguments,
   `ORIGINAL_ARGS` (set at `install.sh:4`) is an empty array. Under
   `set -euo pipefail` (`install.sh:2`), expanding an empty array with
   `"${ORIGINAL_ARGS[@]}"` raises an unbound-variable error on bash < 4.4
   (the default `/bin/bash` on macOS is 3.2, and many minimal Linux/Docker
   images ship < 4.4). The expansions live at `install.sh:270`, `272`, and the
   `exec` at `276`.

2. **`PLUGIN_DIR` hijacked by the current directory** — When the script is run
   from stdin (`curl … | bash`), `$0` is `bash`, so
   `PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"` (`install.sh:11`) resolves to
   the *current working directory*, and `SKILLS_DIR="$PLUGIN_DIR/skills"`
   (`install.sh:12`). If that directory happens to contain a `skills/`
   subfolder (e.g. running from `~/.copilot`, which has one), then
   `bundle_bootstrap_if_needed` (`install.sh:558-560`) sees `SKILLS_DIR` exists
   and early-returns *without* fetching the release bundle. The installer then
   wrongly treats the user's CWD as the Skill Bill source tree and looks for
   `$PLUGIN_DIR/uninstall.sh`, which does not exist
   (`Cannot run pre-install cleanup: …/uninstall.sh is missing`).

3. **Pre-install cleanup hard-fails on a fresh machine** — `run_full_install`
   always calls `run_pre_install_uninstall` (`install.sh:2564`), which runs the
   bundle's `uninstall.sh`. The release skills bundle ships skills only — no
   `runtime-kotlin/` source and no Gradle wrapper (see comment at
   `uninstall.sh:151-154`). The uninstaller's `build_kotlin_runtime_distribution`
   needs *either* a Gradle wrapper *or* an already-installed runtime CLI to run
   "runtime-driven cleanup." On a fresh machine neither exists, so it aborts at
   `uninstall.sh:159-161` (`Missing Gradle wrapper … No installed runtime CLI to
   fall back on either`). The pre-install uninstall exists only to wipe a
   *prior* install; on a first install there is nothing to clean, yet it fails
   trying.

**Why CI never caught these.** `scripts/install_smoke_test.sh` masks all three:
every install scenario sets `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1` (hides #3),
the bootstrap scenario passes `--no-desktop-app` so `ORIGINAL_ARGS` is never
empty and runs on a modern bash (hides #1), and scenarios run from `$REPO_ROOT`
or with `SKILL_BILL_RELEASE_DIR` set so the CWD always has a real `skills/`
(hides #2).

## Scope

Edit `install.sh`, `uninstall.sh`, and `scripts/install_smoke_test.sh` only.
Three code fixes plus regression coverage:

### Fix 1 — Guard empty-array expansions (done)

Replace the three bare expansions with the `${arr[@]+"${arr[@]}"}` guard so an
empty array expands to nothing instead of erroring under `set -u`:

- `install.sh:270` → `local bootstrap_args=("${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")`
- `install.sh:272` → `bootstrap_args=(--release "$tag" "${ORIGINAL_ARGS[@]+"${ORIGINAL_ARGS[@]}"}")`
- `install.sh:276` → `exec bash -s -- "${bootstrap_args[@]+"${bootstrap_args[@]}"}" <<<"$installer"`

This change is already applied in the working tree.

### Fix 2 — Never trust the CWD as the source tree on a piped install

When the installer is read from stdin (`INSTALLER_FROM_STDIN=1`, already
detected at `install.sh:6-9`), the current directory is not a trustworthy
plugin source. Change the early-return guard in `bundle_bootstrap_if_needed`
(`install.sh:558-561`) so a local `$SKILLS_DIR` is only honored for a non-piped
invocation:

```bash
bundle_bootstrap_if_needed() {
  if [[ "$INSTALLER_FROM_STDIN" -ne 1 && -d "$SKILLS_DIR" ]]; then
    return 0
  fi
  ...
}
```

A piped install then always fetches the release bundle and re-points
`PLUGIN_DIR`/`SKILLS_DIR`/`PLATFORM_PACKS_DIR` to the extracted bundle root,
regardless of what is in the CWD. A local checkout run as `./install.sh` or
`bash install.sh` (`INSTALLER_FROM_STDIN=0`) keeps using the local tree,
unchanged.

### Fix 3 — Pre-install cleanup is a no-op when there is no prior install

Gate `run_pre_install_uninstall` (`install.sh:823`) on detecting a prior-install
footprint. When none exists, print an info line and `return 0` before requiring
the bundle's `uninstall.sh`. A prior install is present when either the
installed runtime CLI binary exists (`$RUNTIME_CLI_BIN`) or the state directory
`$SKILL_BILL_STATE_DIR` (`~/.skill-bill`) exists:

```bash
run_pre_install_uninstall() {
  if [[ "${SKILL_BILL_SKIP_PREINSTALL_UNINSTALL:-}" == "1" ]]; then
    warn "Skipping pre-install uninstall because SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1."
    return 0
  fi
  if [[ ! -x "$RUNTIME_CLI_BIN" && ! -d "$SKILL_BILL_STATE_DIR" ]]; then
    info "No prior Skill Bill install detected; skipping pre-install cleanup."
    return 0
  fi
  ...
}
```

As secondary hardening, `build_kotlin_runtime_distribution` in `uninstall.sh`
(`uninstall.sh:159-161`) should not hard-`exit 1` when there is no Gradle
wrapper and no installed runtime CLI: with no runtime present there is nothing
to unlink via the runtime, so warn that runtime-driven cleanup is skipped and
return non-fatally, letting the non-runtime cleanup steps proceed. This keeps an
explicit `./uninstall.sh` on a half-removed install from spuriously failing.

### Fix 4 — Regression coverage

Add scenarios to `scripts/install_smoke_test.sh` that exercise the previously
unmasked paths:

- A piped install run from a foreign directory that contains a stray `skills/`
  subfolder, asserting the install still bootstraps the release bundle (does not
  treat the foreign dir as the source tree) and exits 0.
- A fresh-`HOME` install scenario that does **not** set
  `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1`, asserting the pre-install cleanup is
  skipped with the "No prior Skill Bill install detected" message and the
  install completes.

## Acceptance Criteria

1. Running `curl -fsSL …/install.sh | bash` with no arguments no longer aborts
   with `ORIGINAL_ARGS[@]: unbound variable`; the three expansions at
   `install.sh:270`, `272`, and `276` use the `${arr[@]+"${arr[@]}"}` guard.
2. A piped install (`INSTALLER_FROM_STDIN=1`) always fetches the release bundle
   and re-points `PLUGIN_DIR` to the extracted bundle, even when the current
   working directory contains a `skills/` subfolder; it never resolves
   `uninstall.sh` against the user's CWD.
3. On a machine with no prior install (`$RUNTIME_CLI_BIN` absent and
   `$SKILL_BILL_STATE_DIR` absent), `run_pre_install_uninstall` returns 0 with an
   informational "No prior Skill Bill install detected" message and does not run
   the bundle's `uninstall.sh`.
4. `uninstall.sh` no longer aborts with "Missing Gradle wrapper … No installed
   runtime CLI to fall back on" when there is genuinely nothing installed to
   clean; it warns and proceeds non-fatally.
5. A clean `curl -fsSL …/install.sh | bash` from an arbitrary directory on a
   fresh machine completes end-to-end and installs the `skill-bill` and
   `skill-bill-mcp` launchers, with no manual arguments or environment-variable
   workarounds.
6. `scripts/install_smoke_test.sh` gains the two scenarios described in Fix 4 and
   the full suite passes.
7. `bash -n install.sh`, `bash -n uninstall.sh`, and
   `bash -n scripts/install_smoke_test.sh` all parse cleanly.

## Non-Goals

- Repackaging the release bundle to include `runtime-kotlin/` source or a Gradle
  wrapper. The bundle stays skills-only; cleanup must tolerate its absence.
- Changing runtime-driven cleanup semantics for a *real* prior install that has
  a working runtime CLI.
- Backporting bash ≥ 4.4 behavior beyond the specific empty-array guard.
- Any change to the interactive prompts, agent selection, or desktop-app flow.

## Dependency Notes

The three code fixes are independent and can land together. Fix 4 (smoke-test
scenarios) depends on Fixes 2 and 3 being in place to assert the new behavior.

## Validation Strategy

- `bash -n install.sh && bash -n uninstall.sh && bash -n scripts/install_smoke_test.sh`.
- `bash scripts/install_smoke_test.sh` — existing plus the two new scenarios all
  pass.
- Manual end-to-end on a clean `HOME` from a foreign CWD (e.g. a temp dir seeded
  with an empty `skills/`): `cd "$TMP" && curl -fsSL …/install.sh | bash`
  completes and installs launchers.
- Where available, confirm the no-args one-liner under bash 3.2 (macOS default)
  no longer hits the unbound-variable error.

## Next Path

```bash
Run bill-feature-task on .feature-specs/SKILL-97-fresh-install-curl-fixes/spec.md
```
