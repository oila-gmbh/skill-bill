package skillbill.cli

import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult

/**
 * SKILL-52.1 subtask 3 — Adapter-side mappers that convert typed
 * [ScaffoldService][skillbill.application.ScaffoldService] results into the wire-shape
 * `LinkedHashMap` payloads consumed by [CliOutput] and the CliScaffoldRuntime goldens.
 *
 * Each mapper preserves the EXACT key order produced by the prior raw-map producer in
 * `skillbill.scaffold.AuthoringOperations`. The byte-equivalence contract is locked by
 * `runtime-cli/src/test/kotlin/skillbill/cli/CliScaffoldRuntimeTest.kt`.
 *
 * Mirrors the structure of `WorkflowCliResultMappers`: the typed model carries the
 * legacy wire payload verbatim in its `@OpenBoundaryMap`-annotated `payload` field, so
 * each mapper returns that payload directly. Strongly-typed top-level fields on the
 * result models exist for direct callers (e.g. exit-code selection) and are NOT
 * re-emitted here — the wire shape is already complete in `payload`.
 *
 * Both success and error envelope paths flow through `authoringResult { ... }` /
 * `errorResult(...)` in `ScaffoldCliCommands.kt`. Errors are surfaced by the catch
 * blocks in `authoringResult` and do not pass through these mappers.
 */
internal fun ScaffoldListResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldShowResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldExplainResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldValidateResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldUpgradeResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldFillResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldSaveExactContentResult.toCliMap(): Map<String, Any?> = payload

internal fun ScaffoldEditWithBodyFileResult.toCliMap(): Map<String, Any?> = payload
