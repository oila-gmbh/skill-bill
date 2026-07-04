package skillbill.scaffold.model.command

import skillbill.scaffold.model.CodeReviewBaselineLayer

/**
 * SKILL-52.2 subtask 2: typed scaffold command request model. Adapters (CLI, MCP, Desktop)
 * parse their raw wire payloads at the adapter boundary into one of the sealed variants below
 * and call `ScaffoldGateway.scaffold(request, dryRun)` instead of passing a raw
 * `Map<String, Any?>` across the application/port boundary.
 *
 * Invariants preserved from the legacy map seam (so `ScaffoldPayloadVersionMismatchError`,
 * `InvalidScaffoldPayloadError`, `UnknownSkillKindError`, and `UnknownPreShellFamilyError` fire
 * at the same semantic points):
 *  - `scaffoldPayloadVersion` (wire field `scaffold_payload_version`) is carried verbatim so the
 *    domain policy can re-assert the version match against the central constant.
 *  - `repoRoot` (wire field `repo_root`) is an optional override; null means "use the runtime
 *    default repo root" (the historical user-dir property fallback resolved by the adapter).
 *  - dry-run/execute intent is NOT part of the request — it remains a separate parameter on the
 *    gateway/service `scaffold(request, dryRun)` signature.
 */
sealed class ScaffoldCommandRequest {
  /** Wire payload contract version (verbatim from `scaffold_payload_version`). */
  abstract val scaffoldPayloadVersion: String

  /** Optional repo-root override (wire field `repo_root`); null lets the runtime resolve it. */
  abstract val repoRoot: String?

  /**
   * Horizontal-skill scaffold input. Mirrors the wire payload fields for `kind: horizontal`
   * (`name`, optional `description`, optional `content_body`, optional `subagent_specialists`,
   * optional `no_subagents`).
   */
  data class HorizontalSkill(
    val name: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String> = emptyList(),
    val suppressSubagents: Boolean = false,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  /**
   * Platform-pack scaffold input. New platform-pack requests always create the full approved
   * specialist set; callers may still opt out of native-agent stubs or override their names.
   */
  data class PlatformPack(
    val platform: String,
    val displayName: String = "",
    val description: String = "",
    /**
     * When non-null, the wire payload explicitly declared a `routing_signals` block. Carries
     * the optional `strong` / `tie_breakers` arrays so domain policy preset-fallback logic
     * remains correct (preset survives only when no routing block is declared at all).
     */
    val routingSignals: RoutingSignalsInput? = null,
    val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
    val subagentSpecialists: List<String>? = null,
    val suppressSubagents: Boolean = false,
    val contentBody: String? = null,
    /** Optional explicit `name` override; null means "use the canonical default for the kind". */
    val nameOverride: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  /**
   * Platform-override (piloted family) scaffold input. Mirrors the wire payload fields for
   * `kind: platform-override-piloted`. `family` is left as the raw wire string and validated by
   * the domain (`UnknownPreShellFamilyError` for unrecognised pre-shell families).
   */
  data class PlatformOverride(
    val platform: String,
    val family: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String>? = null,
    val suppressSubagents: Boolean = false,
    val nameOverride: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  /** Code-review-area scaffold input. Mirrors `kind: code-review-area` wire fields. */
  data class CodeReviewArea(
    val platform: String,
    val area: String,
    val description: String = "",
    val contentBody: String? = null,
    val nameOverride: String? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()

  /** Add-on scaffold input. Mirrors `kind: add-on` wire fields including the optional `body`. */
  data class AddOn(
    val name: String,
    val platform: String,
    val description: String = "",
    val body: String? = null,
    val addonLocationPath: String? = null,
    /**
     * Optional explicit consumer-skill directory list. When null, the domain falls back to
     * the platform pack's declared addon-consumers (resolved against the on-disk manifest).
     */
    val consumerSkillDirs: List<String>? = null,
    override val scaffoldPayloadVersion: String,
    override val repoRoot: String? = null,
  ) : ScaffoldCommandRequest()
}

/**
 * Optional `routing_signals` payload block. Both inner lists are nullable so the domain can
 * distinguish "field absent" (use preset) from "field present but empty" (loud fail).
 */
data class RoutingSignalsInput(
  val strong: List<String>? = null,
  val tieBreakers: List<String>? = null,
)
