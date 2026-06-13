package skillbill.application.install

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.install.model.BaselineManifest
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanWireValidator
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallReconcileApplyOutcome
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.install.policy.InstallPlanPolicy
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.telemetry.TelemetryLevelMutator
import java.nio.file.Path

@Inject
class InstallService(
  private val planningPorts: InstallPlanningPorts,
  private val reconcilePorts: InstallReconcilePorts,
  private val applyExecutionPort: InstallApplyExecutionPort,
  private val skillLinkPort: InstallSkillLinkPort,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
  private val installPlanWireValidator: InstallPlanWireValidator,
) {
  fun planInstall(request: InstallPlanRequest): InstallPlan {
    val facts = planningPorts.planningFactsPort.collectPlanningFacts(InstallPlanningFactsRequest(request)).facts
    val materializationPlan = InstallPlanPolicy.planPlatformSkillMaterialization(
      InstallPlatformSkillMaterializationRequest(
        installRequest = request,
        platformPacks = facts.platformManifests.map { manifest ->
          InstallPlatformPackDiscoverySnapshot(
            slug = manifest.slug,
            packRoot = manifest.packRoot,
          )
        },
      ),
    )
    val platformPacks = planningPorts.platformSkillMaterializationPort.materializePlatformSkills(
      InstallPlatformSkillMaterializationPortRequest(
        installRequest = request,
        platformManifests = facts.platformManifests,
        selectedPlatformSlugs = materializationPlan.selectedPlatformSlugs,
      ),
    ).platformPacks
    val draft = InstallPlanPolicy.buildPlanDraft(facts.toPolicyInput(request, platformPacks))
    val staging = planningPorts.stagingIntentPort.buildStagingIntent(
      InstallStagingIntentRequest(
        installRequest = request,
        draft = draft,
        platformManifests = facts.platformManifests,
      ),
    ).staging
    return validatedInstallPlan(draft, staging, installPlanWireValidator)
  }

  /**
   * SKILL-76 Subtask 2: compute the per-skill reconciliation plan for a reinstall.
   * Pure compute — no FS mutation. The CLI renders the result as a machine-readable
   * report and install.sh drives the stage -> reconcile -> swap sequence and the
   * interactive conflict prompt from it (AC-5..AC-9).
   */
  fun reconcile(request: InstallReconcileRequest): ReconciliationPlan =
    reconcilePorts.reconcilePort.reconcile(request).plan

  /**
   * SKILL-76 Subtask 2: runtime-owned per-skill APPLY. The infra-fs adapter recomputes
   * the plan from the same inputs, gates on conflicts (refusing loudly when conflicts
   * remain and accept-conflicts is unset), and replaces ONLY the changed skill dirs in
   * the live tree from upstream — keep-local and locally-authored skills are preserved by
   * construction. The baseline is then refreshed from the SAME returned plan via
   * [refreshBaselineFromPlan] (single refresh-eligibility rule, the domain
   * [ReconciliationPlan.baselineRefreshPaths]). Returns the plan, the installed paths,
   * and whether the baseline was rewritten.
   */
  fun applyReconcile(request: InstallReconcileApplyRequest): InstallReconcileApplyOutcome {
    val applied = reconcilePorts.reconcileApplyPort.apply(request)
    val before = reconcilePorts.baselineManifestPersistencePort
      .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
      .manifest
    val updated = refreshBaselineFromPlan(request.home, applied.plan)
    return InstallReconcileApplyOutcome(
      plan = applied.plan,
      installedPaths = applied.installedPaths,
      refreshed = updated != before,
    )
  }

  /**
   * Refresh the baseline manifest after a successful, accepted apply. Adopt,
   * new-upstream, and (accepted) conflict skills baseline to their UPSTREAM hash;
   * keep-local and locally-authored skills are left untouched so a user edit is
   * never silently re-baselined. Idempotent: a no-change reinstall produces only
   * keep-local outcomes, so the overlay is empty and the manifest is unchanged
   * (no baseline churn).
   */
  fun refreshBaselineFromPlan(home: Path, plan: ReconciliationPlan): BaselineManifest {
    val current = reconcilePorts.baselineManifestPersistencePort
      .readBaseline(ReadBaselineManifestRequest(installHome = home))
      .manifest
    val overlay = plan.outcomes.mapNotNull { outcome ->
      when (outcome) {
        is SkillReconciliationOutcome.Adopt -> outcome.skillRelativePath to outcome.upstreamHash
        is SkillReconciliationOutcome.NewUpstream -> outcome.skillRelativePath to outcome.upstreamHash
        is SkillReconciliationOutcome.Conflict -> outcome.skillRelativePath to outcome.upstreamHash
        is SkillReconciliationOutcome.KeepLocal -> null
        is SkillReconciliationOutcome.LocallyAuthored -> null
      }
    }.toMap()
    val updated = current.withEntries(overlay)
    if (updated != current) {
      reconcilePorts.baselineManifestPersistencePort.writeBaseline(
        WriteBaselineManifestRequest(installHome = home, manifest = updated),
      )
    }
    return updated
  }

  fun applyInstall(plan: InstallPlan, telemetryLevelMutator: TelemetryLevelMutator? = null): InstallApplyResult {
    val result = applyExecutionPort.applyInstall(
      InstallApplyExecutionRequest(
        plan = plan,
        telemetryLevelMutator = telemetryLevelMutator,
      ),
    ).result
    persistSuccessfulInstallSelection(plan, result)
    return result
  }

  /**
   * SKILL-52.3 subtask 1: CLI emission-seam validation hook. The CLI
   * re-validates the install-plan wire shape before emitting JSON (the
   * documented dual-seam coverage). The concrete validator lives in
   * `runtime-infra-fs`; routing the CLI seam through this service method
   * keeps the injected `InstallPlanWireValidator` port inside the
   * application layer (and off the CLI's compile graph + the runtime-core
   * public ABI), while preserving the loud-fail contract.
   */
  fun validateInstallPlanWire(plan: InstallPlan) {
    InstallPlanPolicy.validateInstallPlanSnapshot(plan, installPlanWireValidator)
  }

  fun discoverPlatformPackSlugs(request: InstallPlanRequest): Set<String> = planningPorts.planningFactsPort
    .collectPlanningFacts(InstallPlanningFactsRequest(request))
    .facts
    .platformManifests
    .mapTo(mutableSetOf()) { manifest -> manifest.slug }

  fun linkSkill(source: Path, targetDir: Path, agent: String, repoRoot: Path? = null, home: Path? = null): List<Path> =
    skillLinkPort.linkSkill(
      InstallSkillLinkRequest(
        source = source,
        targetDir = targetDir,
        agent = agent,
        repoRoot = repoRoot,
        home = home,
      ),
    ).linkedPaths

  private fun persistSuccessfulInstallSelection(plan: InstallPlan, result: InstallApplyResult) {
    if (result.status == InstallApplyStatus.FAILURE) {
      return
    }
    installSelectionPersistencePort.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(
        installHome = plan.request.home,
        selection = SharedInstallSelection(
          selectedAgents = result.resolvedInstalledAgents.agents.ifEmpty {
            plan.agents.mapTo(mutableSetOf()) { target -> target.agent }
          },
          platformPackSelection = persistedPlatformPackSelection(plan),
          telemetryLevel = plan.telemetryLevel,
          mcpRegistrationChoice = plan.request.mcpRegistrationChoice,
        ),
      ),
    )
  }

  private fun persistedPlatformPackSelection(plan: InstallPlan): PlatformPackSelection = PlatformPackSelection(
    mode = plan.request.platformPackSelection.mode,
    selectedSlugs = if (plan.request.platformPackSelection.mode == PlatformPackSelectionMode.SELECTED) {
      plan.selectedPlatformSlugs.toSet()
    } else {
      emptySet()
    },
  )
}
