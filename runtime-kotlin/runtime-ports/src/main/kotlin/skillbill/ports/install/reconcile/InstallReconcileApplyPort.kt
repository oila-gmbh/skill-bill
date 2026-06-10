package skillbill.ports.install.reconcile

import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyResult

/**
 * SKILL-76 Subtask 2: runtime-owned per-skill reconcile APPLY port. Sibling of
 * [InstallReconcilePort] (compute). The adapter recomputes the
 * [skillbill.install.model.ReconciliationPlan] from the SAME inputs, gates on
 * conflicts, and performs the per-skill FILE replacement against the live tree. The
 * baseline-manifest refresh is NOT done here — the application service
 * (`InstallService.applyReconcile`) refreshes the baseline from the returned plan. FS IO
 * lives entirely in the adapter; this surface is pure typed models.
 */
interface InstallReconcileApplyPort {
  fun apply(request: InstallReconcileApplyRequest): InstallReconcileApplyResult
}
