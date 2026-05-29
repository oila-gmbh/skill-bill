package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.install.InstallPlanSchemaValidator
import skillbill.install.model.InstallPlanWireValidator

/**
 * SKILL-52.3 Subtask 1: infra-side adapter that bridges the domain-owned
 * [InstallPlanWireValidator] port to the concrete
 * [InstallPlanSchemaValidator] (now owned by `runtime-infra-fs`).
 *
 * Mirrors `WorkflowSnapshotValidatorAdapter`. The adapter lives in
 * `runtime-infra-fs` — the module that owns the networknt + Jackson schema
 * validator — and is wired into the graph by `RuntimeComponent` so
 * `runtime-domain` install policy code never imports the concrete
 * validator. Loud-fail behavior is unchanged: the delegate throws
 * [skillbill.error.InvalidInstallPlanSchemaError] on any schema violation.
 */
@Inject
class InstallPlanWireValidatorAdapter : InstallPlanWireValidator {
  override fun validate(plan: Map<String, Any?>) {
    InstallPlanSchemaValidator.validate(plan)
  }
}
