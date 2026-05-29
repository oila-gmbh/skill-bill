package skillbill.install.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidInstallPlanSchemaError

/**
 * SKILL-52.3 Subtask 1: domain-owned validator port for the install-plan
 * wire snapshot map.
 *
 * Mirrors [skillbill.workflow.WorkflowSnapshotValidator]: `runtime-domain`
 * install policy code MUST NOT import the concrete schema validator (which
 * now lives in `runtime-infra-fs`). The application/infra boundary
 * constructs the validator implementation and threads it through the
 * documented install seams (`InstallPlanBuilder` and the CLI emission
 * boundary) so dual-seam coverage is preserved without `runtime-domain`
 * owning networknt + Jackson + filesystem schema loading.
 *
 * Implementations MUST throw [InvalidInstallPlanSchemaError] on any schema
 * violation, keeping the existing loud-fail contract intact at every seam.
 */
interface InstallPlanWireValidator {
  /**
   * Validates the install-plan wire snapshot map against the canonical
   * schema. On any violation, throws [InvalidInstallPlanSchemaError] whose
   * `fieldPath` names the offending field so the failure surface stays
   * loud and useful.
   *
   * The typed `Map<String, Any?>` signature (vs a raw `Any?`) keeps "is it
   * a mapping?" a compile-time concern of the caller — the install seams
   * build the wire map via [buildInstallPlanWireMap] by construction.
   */
  @OpenBoundaryMap("Install-plan wire snapshot map at the schema-validation seam")
  fun validate(plan: Map<String, Any?>)
}
