package skillbill.desktop.feature.skillbill.state

import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.first_run_install_did_not_run
import dev.skillbill.designsystem.generated.resources.first_run_install_planning_failed
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator

internal class SkillBillFirstRunController(
  private val state: SkillBillViewState,
  private val repoController: SkillBillRepoController,
  private val firstRunGateway: DesktopFirstRunGateway,
  private val desktopPreferenceStore: DesktopPreferenceStore,
  private val installedWorkspaceLocator: InstalledWorkspaceLocator,
) {
  fun beginFirstRunDiscovery(): FirstRunDiscoveryRequest? = with(state) {
    val setup = firstRunSetup ?: return null
    if (setup.busy || setup.discoveryLoaded || setup.errorMessage != null) {
      return null
    }
    activeFirstRunToken += 1
    firstRunSetup = setup.copy(busy = true, errorMessage = null)
    currentState = createState()
    FirstRunDiscoveryRequest(token = activeFirstRunToken)
  }

  fun openFirstRunSetup(): SkillBillState = with(state) {
    if (busyOperation != null || scaffoldWizard != null || firstRunSetup != null) {
      return currentState
    }
    firstRunSetup = latestInstallSetupRequest()?.toFirstRunSetupState() ?: FirstRunSetupState()
    currentState = createState()
    currentState
  }

  suspend fun runFirstRunDiscovery(request: FirstRunDiscoveryRequest): FirstRunDiscoveryResponse =
    FirstRunDiscoveryResponse(
      request = request,
      result = firstRunGateway.discoverSetup(),
    )

  fun finishFirstRunDiscovery(response: FirstRunDiscoveryResponse): SkillBillState = with(state) {
    if (response.request.token != activeFirstRunToken) {
      return currentState
    }
    val setup = firstRunSetup ?: return currentState
    firstRunSetup = when (val result = response.result) {
      is FirstRunDiscoveryResult.Success -> setup.applyDiscovery(
        discovery = result.discovery,
        preferredRequest = latestInstallSetupRequest(),
      )
      is FirstRunDiscoveryResult.Failed -> setup.copy(
        busy = false,
        errorMessage = result.message,
      )
    }
    currentState = createState()
    currentState
  }

  fun selectFirstRunAgent(agentId: String, selected: Boolean): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    val selectedAgents = if (selected) {
      setup.selectedAgentIds + agentId
    } else {
      setup.selectedAgentIds - agentId
    }
    firstRunSetup = setup.copy(
      selectedAgentIds = selectedAgents,
      agentOptions = setup.agentOptions.map { option ->
        if (option.agentId == agentId) option.copy(selected = selected) else option
      },
      plan = null,
      outcome = null,
      errorMessage = null,
    )
    currentState = createState()
    currentState
  }

  fun selectFirstRunPlatform(slug: String, selected: Boolean): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    val selectedPlatformSlugs = if (selected) {
      setup.selectedPlatformSlugs + slug
    } else {
      setup.selectedPlatformSlugs - slug
    }
    firstRunSetup = setup.copy(
      selectedPlatformSlugs = selectedPlatformSlugs,
      platformSelectionMode = selectedPlatformSlugs.toFirstRunPlatformSelectionMode(),
      platformPacks = setup.platformPacks.map { pack ->
        if (pack.slug == slug) pack.copy(selected = selected) else pack
      },
      plan = null,
      outcome = null,
      errorMessage = null,
    )
    currentState = createState()
    currentState
  }

  fun selectFirstRunTelemetry(level: FirstRunTelemetryLevel): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (!setup.busy) {
      firstRunSetup = setup.copy(telemetryLevel = level, plan = null, outcome = null, errorMessage = null)
      currentState = createState()
    }
    currentState
  }

  fun advanceFirstRunStep(): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (!setup.canContinue || setup.step == FirstRunSetupStep.RESULT) {
      return currentState
    }
    firstRunSetup = setup.copy(step = setup.step.next())
    currentState = createState()
    currentState
  }

  fun retreatFirstRunStep(): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    firstRunSetup = setup.copy(step = setup.step.previous(), errorMessage = null)
    currentState = createState()
    currentState
  }

  fun beginFirstRunApply(): FirstRunApplyRequest? = with(state) {
    val setup = firstRunSetup ?: return null
    if (!setup.canContinue || setup.busy) {
      return null
    }
    activeFirstRunToken += 1
    firstRunSetup = setup.copy(
      step = FirstRunSetupStep.APPLY,
      busy = true,
      errorMessage = null,
      outcome = null,
    )
    currentState = createState()
    FirstRunApplyRequest(
      token = activeFirstRunToken,
      setupRequest = setup.request(),
    )
  }

  suspend fun runFirstRunApply(request: FirstRunApplyRequest): FirstRunApplyResponse {
    val planResult = firstRunGateway.planSetup(request.setupRequest)
    val applyResult = when (planResult) {
      is FirstRunPlanResult.Planned -> firstRunGateway.applySetup(planResult.plan)
      is FirstRunPlanResult.Failed -> null
    }
    return FirstRunApplyResponse(
      request = request,
      planResult = planResult,
      applyResult = applyResult,
    )
  }

  fun finishFirstRunApply(response: FirstRunApplyResponse): SkillBillState = with(state) {
    if (response.request.token != activeFirstRunToken) {
      return currentState
    }
    val setup = firstRunSetup ?: return currentState
    val nextSetup = when (val planResult = response.planResult) {
      is FirstRunPlanResult.Failed -> setup.copy(
        step = FirstRunSetupStep.RESULT,
        busy = false,
        errorMessage = planResult.message,
        outcome = FirstRunInstallOutcome(
          status = FirstRunInstallStatus.FAILURE,
          titleRes = Res.string.first_run_install_planning_failed,
        ),
      )
      is FirstRunPlanResult.Planned -> {
        val outcome = when (val applyResult = response.applyResult) {
          is FirstRunApplyResult.Applied -> applyResult.outcome
          is FirstRunApplyResult.Failed -> applyResult.outcome
          null -> FirstRunInstallOutcome(
            status = FirstRunInstallStatus.FAILURE,
            titleRes = Res.string.first_run_install_did_not_run,
          )
        }
        setup.copy(
          step = FirstRunSetupStep.RESULT,
          busy = false,
          plan = planResult.plan,
          outcome = outcome,
          errorMessage = null,
        )
      }
    }
    firstRunSetup = nextSetup
    if (nextSetup.outcome?.status == FirstRunInstallStatus.FAILURE) {
      desktopPreferenceStore.saveFirstRunPreferences(DesktopFirstRunPreferences(completed = false))
    } else {
      desktopPreferenceStore.markFirstRunCompleted(DesktopFirstRunPreferences())
    }
    currentState = createState()
    currentState
  }

  fun finishFirstRunSetup(): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) return currentState
    if (setup.outcome?.status != FirstRunInstallStatus.FAILURE) {
      firstRunSetup = null
      busyOperation = null
      if (installedWorkspaceRoot == null) {
        val freshRoot = installedWorkspaceLocator.locate().takeIf { it.availability }?.path?.takeIf { it.isNotBlank() }
        installedWorkspaceRoot = freshRoot
        normalizedInstalledWorkspaceRoot = freshRoot?.let(::normalizeRepoPath)
      }
      currentState = if (installedWorkspaceRoot != null) {
        repoController.openRepo(installedWorkspaceRoot!!, preserveSelection = false)
      } else {
        createState()
      }
    }
    currentState
  }

  fun dismissFirstRunSetup(): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    firstRunSetup = null
    currentState = createState()
    currentState
  }

  fun retryFirstRunSetup(): SkillBillState = with(state) {
    val setup = firstRunSetup ?: return currentState
    firstRunSetup = setup.copy(
      step = FirstRunSetupStep.AGENTS,
      busy = false,
      errorMessage = null,
      plan = null,
      outcome = null,
    )
    currentState = createState()
    currentState
  }

  internal fun latestInstallSetupRequest(): FirstRunSetupRequest? {
    val legacyFallback = desktopPreferenceStore.firstRunPreferences.value.toLegacySetupRequestOrNull()
    return firstRunGateway.latestReusableSetupRequest(legacyFallback)
  }
}
