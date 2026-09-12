# SKILL-239 subtask 4: Make architecture enforcement match its claims

## Scope

Fix F-006 and F-007 in wire-vocabulary enforcement and current architecture documentation. The scanner's current checked keys come only from existing owning objects, so an undeclared key produces no violation. Some documentation tests pin exact prose while missing disagreements about the active ceiling and baselines.

Own WireVocabularyArchitectureSupport and its fixtures, RuntimeArchitectureDocumentationTest, the relevant enforcement inventory, docs/code-principles.md, runtime-kotlin/ARCHITECTURE.md, and the decomposition/workflow key declarations and call sites needed to close the named contract gap. Preserve AGENTS.md's reference to [Design Principles](../../runtime-kotlin/ARCHITECTURE.md#design-principles). Keep lasting rules there rather than duplicating them in AGENTS.md or this spec.

## Acceptance criteria

1. At governed payload seams, a literal contract key with no owning declaration fails independently of whether another file already declared that key. A canonical schema field without its Kotlin owner also fails. Use the canonical schema inventory or an equally independent authority; never derive both expected and actual coverage from the same owner scan.
2. Move decomposition-manifest and workflow-envelope payload keys to their existing runtime-contracts owner or a family-owned Keys object. Update their reads, writes, and codec consumers in the same commit. Keep wire values, aliases, schema versions, and extension behavior unchanged unless a real contract change requires a version bump.
3. Fixtures cover a new schema key, an undeclared literal, a valid constant reference, and an allowed open-extension map. The test calls the production scanner path, not a reimplementation of its algorithm. Avoid introducing a general Kotlin parser or dependency unless the existing tooling cannot distinguish the required cases and the need is documented.
4. Document exactly which payload seams the scanner understands and any remaining coverage gaps. An empty violation list is not presented as proof that all identifiers or all String fields are typed. Preserve existing contract-version, raw-map extension, and rejection safeguards.
5. The current line ceiling, module graph, domain library dependencies, engine cycle state, and baseline policies agree across AGENTS.md, docs/code-principles.md, runtime-kotlin/ARCHITECTURE.md, and the owning checks. The Design Principles enforcement status names the checks that landed and any remaining owned gaps. Keep historical decision records as history. Remove current-tense claims that contradict live code.
6. Exercise the logical-type scanner through its real source-fixture path if retaining its synthetic split-file coverage. Delete incidental English-phrase assertions such as the ban on 'while the'. Retain governed headings, public package ownership, contract sections, and module-edge parity. A documentation check that claims a numeric rule verifies the number against its owner or avoids duplicating it.
7. No new suppression or baseline entry hides a violation. Every new check names the realistic regression it catches. Do not create a parallel architecture inventory, a plugin framework, or a line-count dashboard.

## Dependency notes

Depends on subtask 3 because it records the final engine boundary and empty cycle baseline. It does not wait for SKILL-238 or broaden into its deletion sweep. This task updates documentation of actual dependencies without adding or upgrading libraries.

## Validation strategy

Run the existing wire scanner fixtures, module-edge, implementation-import, engine inbound API, and documentation tests. From runtime-kotlin, use `./gradlew :runtime-core:test --tests 'skillbill.architecture.WireVocabularyArchitectureTest' --tests 'skillbill.architecture.RuntimeArchitectureDocumentationTest' --tests 'skillbill.architecture.RuntimeGradleModuleLayeringTest' --tests 'skillbill.architecture.RuntimeEngineInboundApiTest' --tests 'skillbill.architecture.RuntimeImplementationImportRulesTest' --console=plain`. Run existing decomposition/workflow schema and codec tests for changed key consumers. Validate the spec bundle against the canonical manifest schema and acceptance-heading contract.

## Non-goals

Universal Kotlin semantic analysis, raw-map elimination, blanket identifier wrappers, changes to contract policy, prose keyword blacklists, or weakening existing runtime validation. Do not delete tests that protect real schema, compatibility, rollback, or lease behavior.

## Next path

Finish the goal through the runtime's normal finalization. Report behavior proved, remaining owned work in SKILL-238, and any explicitly documented audit limitations.
