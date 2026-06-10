package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort

/**
 * SKILL-76 Subtask 2: bundles the reconcile-compute port, the runtime-owned per-skill
 * apply port, and the baseline manifest persistence port that are consumed together when
 * reconciling a reinstall against the copied source tree. Grouping them keeps
 * [skillbill.application.InstallService] below the constructor parameter threshold and
 * mirrors [InstallPlanningPorts].
 */
@Inject
class InstallReconcilePorts(
  val reconcilePort: InstallReconcilePort,
  val reconcileApplyPort: InstallReconcileApplyPort,
  val baselineManifestPersistencePort: BaselineManifestPersistencePort,
)
