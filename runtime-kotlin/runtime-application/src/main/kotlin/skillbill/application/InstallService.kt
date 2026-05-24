package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.ports.install.InstallPlanGateway
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path

@Inject
class InstallService(
  private val gateway: InstallPlanGateway,
) {
  fun planInstall(request: InstallPlanRequest): InstallPlan = gateway.planInstall(request)

  fun applyInstall(plan: InstallPlan, telemetryLevelMutator: TelemetryLevelMutator? = null): InstallApplyResult =
    gateway.applyInstall(plan, telemetryLevelMutator)

  fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path? = null, home: Path? = null): List<Path> =
    gateway.linkSkill(source, targetDir, agent, repoRoot, home)
}
