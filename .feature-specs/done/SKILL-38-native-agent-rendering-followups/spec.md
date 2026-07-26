# SKILL-38: native-agent rendering follow-ups

Status: Complete

Sources: feature briefing inline (no external doc); originating Linear/issue key SKILL-38.

## Context
Commit 6031f34 introduced provider-neutral `native-agents/<name>.md` sources rendered into Claude/Codex/Opencode/Junie shapes at install time. Source code lives in `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/`. A strategy review surfaced 6 follow-ups that should all ship in this feature. This is a refactor + harden + document feature, not a behavior change.

## Acceptance criteria

1. **Round-trip parse/render symmetry test.** Add a test next to `NativeAgentRenderingTest` that, for every `native-agents/*.md` source under `skills/` and `platform-packs/` in the repo, asserts:
   `parseNativeAgentSourceText(renderNativeAgentSource(parseNativeAgentSource(path))).copy(path = null) == parseNativeAgentSource(path).copy(path = null)`
   If it fails, harden `parseSimpleFrontmatter` in `NativeAgentSource.kt:113` to handle whatever the renderer produces — do not blanket-strip quotes; respect YAML double-quote escaping (decode the same `\\`, `\"`, `\n`, `\r`, `\t` sequences the renderer's `YAML_DOUBLE_QUOTE_ESCAPES` map produces).

2. **Move render dispatch onto the enum.** `NativeAgentProvider` (in `NativeAgentRendering.kt`) gains a `render(source: NativeAgentSource): String` method. The free `renderNativeAgent(agent, provider)` either delegates to `provider.render(agent)` or is removed in favor of the method. All call sites updated. The `when (provider)` switch goes away.

3. **Slug-prefix the cache key.** `installCacheRoot` in `NativeAgentOperations.kt:161` produces `<home>/.skill-bill/native-agents/<slug>-<8-byte-hash>/` where `<slug>` is the platform-packs root's parent directory basename, sanitized to `[a-z0-9-]+` (lowercase; non-matching chars collapse to `-`; trim leading/trailing `-`), truncated to ≤32 chars. Hash math (`stableRepoKey`) unchanged. Update existing tests covering `installCacheRoot`/`stableRepoKey`.

4. **Per-provider render snapshot test.** Add (in `NativeAgentRenderingTest` or a sibling) a test that pins one inline `NativeAgentSource` (constructed in code, not loaded from disk — keep hermetic) and asserts byte-exact rendered output for all 4 providers. Snapshots live inline as raw multiline string literals (`"""..."""`) so diffs are obvious in PRs.

5. **Body provider-agnostic rule.** Add a check in `NativeAgentValidation.kt` (`validateNativeAgentSources`) that rejects sources whose body contains any of: `{{#claude}}`, `{{#codex}}`, `{{#opencode}}`, `{{#junie}}`, or the case-insensitive substring `if provider ==` / `if (provider`. Issue message: "native agent bodies must be provider-agnostic; conditionals belong in the renderer". Cover with a new validation test asserting both pass and fail cases.

6. **Documentation.** Find existing native-agent docs (search README.md, AGENTS.md, runtime-kotlin/, orchestration/, docs/). If a section exists in a doc that already covers `native-agents/`, extend it. If none exists, add a new file `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/README.md`. Cover: (a) source format (frontmatter + body), (b) the "body is provider-agnostic" rule from AC5, (c) install/symlink fallback behavior including the Windows-developer-mode hint and the `Linked|Skipped` semantics surfaced by `installNativeAgentFile`. Keep it under ~80 lines.

## Non-goals
- Do not change install/symlink behavior — only document it.
- Do not change the cache directory structure beyond adding the slug prefix in (3).
- Do not introduce a templating engine for bodies.
- Do not add a 5th provider.
- Do not edit existing native-agent source files under `skills/`/`platform-packs/`. Their bytes are sacred — the round-trip test must pass against them as-is, which means the parser is what bends if anything bends.

## Validation
`cd runtime-kotlin && ./gradlew check`
