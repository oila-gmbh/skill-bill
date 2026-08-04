# Subtask 2: bill-over-engineering-review horizontal skill

Part of SKILL-159 (`spec.md`). Port `ponytail-review` and `ponytail-audit` as one
horizontal skill with two scopes.

## Scope

Scaffold `bill-over-engineering-review` with `skill-bill new` (kind: `horizontal`) and fill
`content.md` via `skill-bill fill`. Precedent for a focused horizontal review skill:
`skills/bill-unit-test-value-check/`.

The skill reviews exclusively for unnecessary complexity, in two scopes:

- **diff scope** (default): current changes, a commit, or a PR diff — the port of
  `ponytail-review`.
- **repo scope**: whole-tree audit, findings ranked biggest cut first — the port of
  `ponytail-audit`.

Port faithfully:

1. **Finding format** — one line per finding:
   `<file>:L<line>: <tag> <what to cut>. <replacement>.`
2. **Tags** — `delete:` (dead code, unused flexibility, speculative feature; replacement:
   nothing), `stdlib:` (hand-rolled thing the standard library ships; name the function),
   `native:` (dependency or code doing what the platform already does; name the feature),
   `yagni:` (abstraction with one implementation, config nobody sets, layer with one
   caller), `shrink:` (same logic, fewer lines; show the shorter form).
3. **Hunt list** for repo scope — deps the stdlib or platform ships, single-implementation
   interfaces, factories with one product, wrappers that only delegate, files exporting one
   thing, dead flags and config, hand-rolled stdlib.
4. **Scoring** — end with `net: -<N> lines possible.` (repo scope adds `, -<M> deps
   possible.`). Nothing to cut: `Lean already. Ship.`
5. **Boundaries** — complexity only; correctness, security, and performance are explicitly
   out of scope and routed to `bill-code-review`. A minimal smoke test or self-check is
   never flagged for deletion. The skill lists findings and applies nothing.

skill-bill-specific additions:

- Carve-out mirroring subtask 1: governed contracts (typed errors, loud-fail seams,
  contract-version constants, parity tests, validator-backed rules) and `shortcut:` markers
  are never findings.
- Include the ❌/✅ example contrast from the source (vague hedge vs. tagged one-liner),
  rewritten with a neutral example.

## Acceptance Criteria

1. `skills/bill-over-engineering-review/content.md` exists, scaffolder-created, and
   `skill-bill validate --skill-name bill-over-engineering-review` passes.
2. The skill defines both scopes, all five tags with their replacement semantics, the
   one-line finding format, the net-lines scoring rule, and the `Lean already. Ship.` empty
   verdict.
3. The skill declares complexity-only scope with an explicit route to `bill-code-review`
   for correctness/security/performance, and the governed-contract and `shortcut:`-marker
   carve-outs.
4. Trigger phrases cover at minimum: "over-engineered", "what can we delete", "find bloat",
   "simplify review", "audit for over-engineering".
5. `skill-bill validate` and `./install.sh` pass repo-wide.
6. No benchmark figures or savings claims appear in the skill content.

## Non-Goals

- No new platform-pack review area and no changes to pack manifests or `addon_usage`.
- No auto-applied fixes; the skill reports only.
- No overlap claim with `bill-unit-test-value-check`; test-value review stays separate.

## Dependency Notes

Optional dependency on subtask 1: the `shortcut:` marker carve-out references the
convention subtask 1 defines. If executed before subtask 1, keep the carve-out text and
note the convention lands with SKILL-159 subtask 1.

## Validation Strategy

`skill-bill validate --skill-name bill-over-engineering-review`, full `skill-bill
validate`, `./install.sh`, `skill-bill render --skill-name bill-over-engineering-review`
to confirm the rendered wrapper. Acceptance-style self-check: run the skill against a
deliberately over-built scratch diff and confirm tagged one-line output with a net score.

## Next Path

Independent of subtask 3. When both complete, SKILL-159 closes with the parent-spec
reconciliation pass.
