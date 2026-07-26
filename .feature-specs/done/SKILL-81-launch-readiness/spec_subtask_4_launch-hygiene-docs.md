# SKILL-81 · Subtask 4 — Launch-hygiene docs

## Scope

Two low-effort gaps make an actively-developed MIT project read as closed/solo and let a
careful reader misjudge the architecture:

1. **No `CONTRIBUTING.md`.** External readers (and pack authors) have no stated entry
   point, so contribution feels uninvited even though the project is MIT and very active.
2. **Ambiguous prose-vs-runtime story.** The dual `bill-feature-task` mode is documented
   ambiguously enough that the readiness audit itself misread the **default** prose mode
   as "deprecated legacy." Per the actual design, **prose is the default and runtime is
   opt-in** — the docs must say so unambiguously.

In scope:
- Add a minimal `CONTRIBUTING.md` at the repo root covering: how to run the quality gate
  (`(cd runtime-kotlin && ./gradlew check)` and any agent-config validation), the
  supported extension point (authoring platform packs under `platform-packs/<lang>/`),
  the pre-1.0 expectation that entry points/taxonomy may still move, and how to file
  issues / open PRs. Keep it short and honest; do not promise a process that does not
  exist.
- Add a brief "extending Skill Bill" pointer (a short section in `CONTRIBUTING.md` or a
  small `docs/` pointer doc) linking the existing platform-pack contract/spec and the
  MCP tool surface, so an early adopter knows where extension lives. A short pointer, not
  a full API reference.
- Clarify the prose-vs-runtime default in the user-facing docs (README "what you get" /
  `docs/getting-started*.md` / the relevant skill description as appropriate): state
  plainly that **prose is the default in-session mode and runtime (`mode:runtime`) is
  opt-in**, and remove or correct any wording that implies prose is deprecated/legacy.
  This is a documentation-only clarification; no skill or runtime code changes.

## Acceptance Criteria

1. A `CONTRIBUTING.md` exists at the repo root and covers, at minimum: running the
   quality gate, the platform-pack extension point, the pre-1.0 caveat, and how to file
   issues / open PRs. Content is accurate (no invented process or guarantees).
2. `CONTRIBUTING.md` (or a small linked pointer doc) points an extender at the existing
   platform-pack contract/spec and the MCP tool surface.
3. The user-facing docs state unambiguously that prose is the default mode and runtime is
   opt-in; no remaining wording describes the prose orchestrator as deprecated or legacy.
4. No skill bodies, runtime code, or platform-pack manifests are modified — this subtask
   is Markdown-only.
5. The pre-1.0 / honest framing is preserved; no overclaiming of contributor base or
   process maturity.

## Non-goals

- A full MCP tool API reference or a complete platform-pack authoring tutorial (pointer
  only).
- Removing or refactoring the prose orchestrator or any prose/runtime code (docs only).
- A CODE_OF_CONDUCT, issue/PR templates, or governance docs beyond `CONTRIBUTING.md`
  (may be a future feature; not in scope here).

## Dependency notes

Independent. No dependency on subtasks 1–3. Markdown-only; if it touches the README it
edits only the "what you get" / mode-description region, not the hero block owned by
subtasks 2 and 3 — coordinate region to avoid overlap if run concurrently.

## Validation strategy

- Confirm `CONTRIBUTING.md` exists, renders, and its commands/links resolve (the quality
  gate command runs; the pack-contract and MCP pointers link to real files/surfaces).
- `git grep -i "deprecated\|legacy"` near the prose/runtime descriptions returns no
  wording that frames the default prose mode as deprecated.
- Confirm no non-Markdown files changed in this subtask's diff.

## Next path

```bash
skill-bill goal SKILL-81
```
