# Skill Bill VS Code extension

Isolated VS Code extension that surfaces Skill Bill feature-work status in the
status bar. This directory is a **sibling** of `runtime-kotlin/` and
`intellij-plugin/` with its own npm build — it is not included in Gradle and
adds no VS Code dependency to the Kotlin runtime.

See [ARCHITECTURE.md](ARCHITECTURE.md) for package ownership, persistence policy,
source-of-truth rules, and deferred tool-window / mutation paths.

## Requirements

- Node.js 20+
- A `skill-bill` CLI on `PATH`, in `${SKILL_BILL_BIN_DIR:-~/.local/bin}`, or
  named by the `skillBill.cliPath` workspace setting

## Installing the extension locally

```bash
cd vscode-extension
npm install
npm run package
```

Install the generated `.vsix` from **Extensions → … → Install from VSIX…**.

Marketplace publish and hosted release packaging are **out of scope** for subtask 1
(subtask 2).

## CLI path resolution

1. `skillBill.cliPath` workspace setting, when set
2. Else `skill-bill` on `PATH`
3. Else `$SKILL_BILL_BIN_DIR`, then `~/.local/bin`

An override that is set but not executable is **misconfigured** and never falls
back to PATH.

## Common tasks

```bash
cd vscode-extension
npm run compile
npm test
npm run package
```

Do **not** fold these tasks into `./gradlew check`. The extension builds
independently of `runtime-kotlin` and `intellij-plugin`.

## Out of scope (subtask 1)

- Stop / Pause controls and mutating CLI verbs
- Marketplace publish and hosted VSIX release job
- Tool window UI
