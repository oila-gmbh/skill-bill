# SKILL-182 · Subtask 2 — Render Cursor native agents with Cursor's own frontmatter vocabulary

## Scope

A delegated Cursor lane is an installed subagent under `~/.cursor/agents/`, and Cursor
decides delegation from that file's frontmatter. Skill Bill currently renders Cursor
agents through the shared Claude renderer, so the emitted frontmatter uses Claude's
vocabulary.

In `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/NativeAgentRendering.kt`:

- `NativeAgentProvider.Cursor.render` delegates to `renderFrontmatterAgent(source, mode = null)`,
  the same private function `Claude` and `Junie` use.
- `renderFrontmatterAgent` emits `name`, `description`, an optional `mode`, and
  `tools: <comma-joined>` whenever `NativeAgentSource.tools` is non-empty.

Cursor's subagent frontmatter defines `name`, `description`, `model` (`inherit` or an
explicit model id), `readonly`, and `is_background`. `tools` is not among them — it is
Claude's field. Every specialist review agent Skill Bill ships declares tools (for
example `bill-kotlin-code-review-*` declare `Read, Grep, Glob, Bash`), so in practice
every installed Cursor review agent carries a key Cursor does not define.

Give Cursor its own renderer that emits only fields Cursor defines. Keep `name` and
`description` — `description` is what drives Cursor's delegation decision, so it must
survive verbatim through the same YAML-quoting path the shared renderer uses
(`yamlScalar` / `yamlNeedsQuoting`). Drop `tools` from the Cursor output.

Do not emit `model`. Cursor's default is already `inherit`, which is exactly the
behaviour subtask 1's playbook section requires ("use the same model as the parent
thread by default; do not override"). Writing an explicit model would be that override.

Do not emit `readonly` or `is_background` in this subtask. Mapping Skill Bill's tool
declarations onto Cursor's `readonly` flag is a behavioural decision, not a rendering
fix, and a review specialist that needs `Bash` is not read-only anyway.

Leave `Claude` and `Junie` rendering byte-identical. `renderFrontmatterAgent` stays as
it is for those two providers.

`NativeAgentRenderingTest.kt:82` currently asserts
`assertEquals(claude, cursor, "Claude and Cursor share the same markdown shape; drift must be intentional")`.
This subtask is that intentional drift: replace the equality assertion with assertions
that pin each provider's own shape, and record why they now differ.

Do not change `NativeAgentSource`, the composition schema, the parsed `tools` field, or
authored `native-agents/agents.yaml` sources. `tools` remains authoritative for the
providers that consume it; only Cursor's projection of it changes.

## Acceptance Criteria

1. `NativeAgentProvider.Cursor.render` produces frontmatter containing exactly `name`
   and `description`, in that order, for a source whose `tools` list is non-empty.
2. No Cursor-rendered file contains a `tools:` key, or any other key outside Cursor's
   defined frontmatter set.
3. `model`, `readonly`, and `is_background` are absent from Cursor output.
4. A `description` requiring YAML quoting (leading reserved character, embedded `: `,
   embedded newline, edge whitespace) is quoted and escaped in Cursor output exactly as
   the shared renderer would have quoted it, and round-trips to the original string.
5. The agent body is emitted unchanged after the frontmatter block, with the same
   trailing-whitespace handling as before.
6. `NativeAgentProvider.Claude.render` and `NativeAgentProvider.Junie.render` output is
   byte-identical to their pre-change output for the same source.
7. The Claude/Cursor equality assertion in `NativeAgentRenderingTest` is replaced by
   per-provider shape assertions, and the Cursor snapshot expectations in
   `NativeAgentRenderSnapshotTest` are updated to the new output.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Changing Claude, Codex, or Junie rendering.
- Mapping `tools` onto Cursor's `readonly` or adding `is_background` support.
- Changing `NativeAgentSource`, the native-agent composition schema, its contract
  version, or any authored `agents.yaml`.
- Changing install/uninstall discovery, target directories, or symlink behaviour.

## Dependencies

None on subtask 1's content, but it lands after it: subtask 1 establishes that Cursor
lanes are installed named subagents, which is the reason this rendering matters. Order
the commits so the contract change precedes the rendering change.

## Validation Strategy

- Unit-test Cursor rendering for a source with a non-empty `tools` list and assert the
  rendered text contains no `tools:` line.
- Unit-test Cursor rendering for the quoted-description cases already covered in
  `NativeAgentRenderSnapshotTest` (the `quotedSource` case at ~line 161) and assert the
  quoting matches the shared renderer's output for the same string.
- Assert Claude and Junie output against their existing snapshots to prove no
  collateral drift, and keep those snapshot expectations unedited.
- Expect `NativeAgentRenderingTest`'s Claude/Cursor equality assertion to fail; that
  failure is the signal the drift landed. Replace it rather than deleting it, and state
  in its message that the providers' frontmatter vocabularies differ.
- Run the affected module tests, then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 3 reconciles the delegated-review documentation with the new contract and the
post-SKILL-159 runtime.
