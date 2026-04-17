---
name: stack-routing
description: Single source of truth for shared stack detection. Discovery-driven — platforms are enumerated from platform-packs/ manifests, not hardcoded here. Skills link to this via sibling symlinks.
---

# Shared Stack Routing Contract

This is the canonical stack-routing contract. Skills consume it through
sibling symlinks (e.g. `stack-routing.md` inside each skill directory), so
changes here propagate to every linked skill immediately.

Do not reference this repo-relative path directly from installable skills —
use the sibling symlink instead.

The stack taxonomy, strong signals, and tie-breakers are **not enumerated
inline**. They are discovered at runtime from the manifests shipped under
`platform-packs/<slug>/platform.yaml`. Adding, removing, or renaming a
platform happens entirely in those manifests; no edit to this file is
required unless the discovery algorithm itself changes.

## Discovery Algorithm

Every router, reviewer, and validator agrees on the following procedure:

1. Enumerate every immediate subdirectory of `platform-packs/`.
2. For each candidate slug, load
   `platform-packs/<slug>/platform.yaml` via the shell+content contract
   loader. Loud-fail rules apply — see
   [shell-content-contract.md](../shell-content-contract/PLAYBOOK.md).
3. Each loaded manifest contributes:
   - `platform` — the stack slug.
   - `routing_signals.strong` — list of strong detection signals (path
     markers, dependency coordinates, language-level markers).
   - `routing_signals.tie_breakers` — list of prose rules that disambiguate
     this pack against overlapping packs.
   - `routing_signals.addon_signals` — optional signals consumed by governed
     add-ons after the dominant stack is chosen.
4. Collect signals from the review scope: changed files, repo markers, and
   dependency manifests — in that order.
5. Rank loaded packs by strong-signal presence in the scope.
6. Apply each candidate pack's declared tie-breaker rules in the order they
   appear in the manifest. When a pack declares a tie-breaker that subsumes
   another pack (for example, "prefer this pack when it layers the Kotlin
   baseline internally"), the subsuming pack wins.
7. If multiple packs have strong signals and no tie-breaker resolves the
   conflict, treat the scope as mixed and route to each matching pack via
   delegated execution.
8. If no pack has strong-signal evidence, stop and report
   `Unknown/Unsupported` — never fall back to a default pack.
9. The routed skill name for the selected pack is
   `bill-<slug>-code-review`. This contract is preserved so existing
   user-facing commands keep working.

## Signal Collection Order

When classifying stack or platform:

1. Inspect the changed files first.
2. Then inspect repo markers and dependency manifests.
3. Prefer strong platform markers (as declared in each manifest's
   `routing_signals.strong`) over generic language markers.
4. When signals are mixed, keep the routing explicit instead of collapsing
   different packs into one bucket.

## Post-Stack Add-Ons

- Resolve governed add-ons only after the dominant stack route is chosen.
- Add-ons never create new top-level stack labels, package names, or
  user-facing commands.
- Add-on detection must be owned by the routed platform pack and reported
  separately from stack classification.
- When no governed add-on applies, report `Selected add-ons: none`.
- When one or more governed add-ons apply, report them as
  `Selected add-ons: <slug[, slug]>`.
- Add-on signals are also declared in `platform.yaml` under
  `routing_signals.addon_signals`. Packs that own governed add-ons set
  `governs_addons: true` in their manifest.

## Relationship To The Shell+Content Contract

This playbook describes **how** the router decides. The detailed schema that
every `platform.yaml` must satisfy is owned by
[shell-content-contract.md](../shell-content-contract/PLAYBOOK.md). Loud-fail
loading rules, contract versioning, and manifest schema live there. If a
manifest is invalid, the shell refuses to run and the router has nothing to
decide.
