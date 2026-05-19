package skillbill.cli

import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSchemaValidator
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.buildInstallPlanWireMap

/**
 * SKILL-48 Subtask 2b: install-plan CLI emission boundary. Delegates
 * the wire map construction to the shared
 * [buildInstallPlanWireMap] helper in `runtime-domain` so the builder
 * seam (`buildInstallPlan` in runtime-core) and the CLI seam emit the
 * exact same shape. Before returning, the map is validated against
 * the canonical schema so a regression in either seam loud-fails
 * before the JSON ever reaches the wire.
 */
internal fun installPlanPayload(plan: InstallPlan): Map<String, Any?> {
  val wireMap = buildInstallPlanWireMap(plan)
  // Deliberate dual-seam validation per AC4 of SKILL-48 subtask 2b
  // (`.feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2b_install-plan.md`).
  // Diverges from 2a's single-seam workflow-state pattern: AC4 explicitly
  // requires BOTH `InstallPlanBuilder` and the CLI emission boundary to
  // validate and loud-fail via `InvalidInstallPlanSchemaError`, so any
  // post-build re-assembly drift is caught before the JSON hits the wire.
  InstallPlanSchemaValidator.validate(wireMap)
  return wireMap
}

internal fun windowsPreflightPayload(preflight: WindowsSymlinkPreflight): Map<String, Any?> = mapOf(
  "state" to preflight.state.name.lowercase(),
  "decision" to preflight.decision.name.lowercase(),
  "message" to preflight.message,
)
