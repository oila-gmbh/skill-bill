package skillbill.ports.install.reconcile

import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.reconcile.model.InstallReconcileResult

/**
 * SKILL-76 Subtask 2: reconcile-compute port. Mirrors
 * [skillbill.ports.install.plan.InstallStagingIntentPort] — one typed *Request in,
 * one typed *Result (carrying the [skillbill.install.model.ReconciliationPlan]) out.
 * The adapter computes UPSTREAM/LOCAL/BASELINE content hashes per skill (reusing
 * `computeInstallContentHash`) and classifies each skill into the sealed
 * reconciliation outcome. FS IO lives entirely in the adapter; this surface is pure
 * typed models.
 */
interface InstallReconcilePort {
  fun reconcile(request: InstallReconcileRequest): InstallReconcileResult
}
