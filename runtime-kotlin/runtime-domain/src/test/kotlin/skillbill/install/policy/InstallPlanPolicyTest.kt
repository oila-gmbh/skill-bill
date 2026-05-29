package skillbill.install.policy

import skillbill.error.InvalidInstallPlanSchemaError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlanWireValidator
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallPolicyValidationStatus
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanPolicyTest {
  @Test
  fun `manual plan draft resolves selected platform skills agent defaults and MCP intent`() {
    val input = policyInput(
      request = request(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CODEX, InstallAgent.CLAUDE),
        ),
        targetPaths = targetPaths(
          agentTargets = listOf(
            InstallAgentTarget(
              agent = InstallAgent.CLAUDE,
              path = path("/manual/claude"),
              source = InstallAgentTargetSource.DETECTED,
            ),
          ),
        ),
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("kotlin"),
        ),
      ),
    )

    val draft = InstallPlanPolicy.buildPlanDraft(input)

    assertEquals(listOf(InstallAgent.CLAUDE, InstallAgent.CODEX), draft.agents.map(InstallAgentTarget::agent))
    assertEquals(
      listOf(InstallAgentTargetSource.MANUAL, InstallAgentTargetSource.MANUAL),
      draft.agents.map { it.source },
    )
    assertEquals(path("/manual/claude"), draft.agents.first { it.agent == InstallAgent.CLAUDE }.path)
    assertEquals(path("/home/.codex/skills"), draft.agents.first { it.agent == InstallAgent.CODEX }.path)
    assertEquals(listOf("kotlin"), draft.selectedPlatformSlugs)
    assertEquals(listOf("bill-code-review", "bill-kotlin-code-review"), draft.skills.map(InstallPlanSkill::name))
    assertEquals(listOf(InstallAgent.CLAUDE, InstallAgent.CODEX), draft.mcpRegistrationIntent.agents)
    assertEquals(input.request.targetPaths.copy(agentTargets = draft.agents), draft.installationTargetPaths)
  }

  @Test
  fun `detected plan draft prefers caller supplied detected targets and normalizes their source`() {
    val input = policyInput(
      request = request(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.DETECTED,
          detectedTargets = listOf(
            InstallAgentTarget(
              agent = InstallAgent.OPENCODE,
              path = path("/detected/opencode"),
              source = InstallAgentTargetSource.MANUAL,
            ),
          ),
        ),
      ),
      detectedAgentTargets = listOf(
        InstallAgentTarget(
          agent = InstallAgent.CODEX,
          path = path("/detected/codex"),
          source = InstallAgentTargetSource.DETECTED,
        ),
      ),
    )

    val draft = InstallPlanPolicy.buildPlanDraft(input)

    assertEquals(listOf(InstallAgent.OPENCODE), draft.agents.map(InstallAgentTarget::agent))
    assertEquals(listOf(InstallAgentTargetSource.DETECTED), draft.agents.map { target -> target.source })
    assertEquals(path("/detected/opencode"), draft.agents.single().path)
  }

  @Test
  fun `request validation rejects inconsistent platform and target selections`() {
    val selectedWithoutSlugs = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(
          request = request(
            platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.SELECTED),
          ),
        ),
      )
    }
    assertContains(selectedWithoutSlugs.message.orEmpty(), "SELECTED requires at least one selected slug")

    val detectedWithManualAgents = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(
          request = request(
            agentSelection = InstallAgentSelection(
              mode = InstallAgentSelectionMode.DETECTED,
              manualAgents = setOf(InstallAgent.CODEX),
            ),
          ),
        ),
      )
    }
    assertContains(
      detectedWithManualAgents.message.orEmpty(),
      "Detected agent selection must not include manual agents",
    )

    val missingManualTarget = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(
          request = request(
            agentSelection = InstallAgentSelection(
              mode = InstallAgentSelectionMode.MANUAL,
              manualAgents = setOf(InstallAgent.CODEX),
            ),
          ),
          defaultAgentTargets = emptyList(),
        ),
      )
    }
    assertContains(
      missingManualTarget.message.orEmpty(),
      "no explicit or default target path",
    )
  }

  @Test
  fun `request validation rejects malformed skill and platform snapshots`() {
    val blankBaseName = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(baseSkills = listOf(baseSkill(""))),
      )
    }
    assertContains(blankBaseName.message.orEmpty(), "non-blank name")

    val blankBaseSource = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(baseSkills = listOf(baseSkill("bill-code-review", sourceDir = path("")))),
      )
    }
    assertContains(blankBaseSource.message.orEmpty(), "sourceDir must not be blank")

    val nonPlatformSkill = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(
          platformPacks = listOf(
            platformPack(
              skills = listOf(
                baseSkill("bill-kotlin-code-review"),
              ),
            ),
          ),
        ),
      )
    }
    assertContains(nonPlatformSkill.message.orEmpty(), "contains non-platform skill")

    val mismatchedPlatformSlug = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.validateRequest(
        policyInput(
          platformPacks = listOf(
            platformPack(
              slug = "kotlin",
              skills = listOf(platformSkill("bill-kotlin-code-review", platformSlug = "kmp")),
            ),
          ),
        ),
      )
    }
    assertContains(mismatchedPlatformSlug.message.orEmpty(), "owned by 'kmp'")
  }

  @Test
  fun `platform skill materialization plan uses policy selection without skill snapshots`() {
    val plan = InstallPlanPolicy.planPlatformSkillMaterialization(
      InstallPlatformSkillMaterializationRequest(
        installRequest = request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        ),
        platformPacks = listOf(
          InstallPlatformPackDiscoverySnapshot(slug = "kmp", packRoot = path("/repo/platform-packs/kmp")),
          InstallPlatformPackDiscoverySnapshot(slug = "kotlin", packRoot = path("/repo/platform-packs/kotlin")),
        ),
      ),
    )

    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)

    val duplicateDiscovery = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.planPlatformSkillMaterialization(
        InstallPlatformSkillMaterializationRequest(
          installRequest = request(platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL)),
          platformPacks = listOf(
            InstallPlatformPackDiscoverySnapshot(slug = "kotlin", packRoot = path("/repo/platform-packs/kotlin")),
            InstallPlatformPackDiscoverySnapshot(slug = "kotlin", packRoot = path("/repo/platform-packs/kotlin-copy")),
          ),
        ),
      )
    }
    assertContains(duplicateDiscovery.message.orEmpty(), "duplicate slug")
  }

  @Test
  fun `planning rejects unknown platforms and duplicate skill names`() {
    val unknownPlatform = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.buildPlanDraft(
        policyInput(
          request = request(
            platformPackSelection = PlatformPackSelection(
              mode = PlatformPackSelectionMode.SELECTED,
              selectedSlugs = setOf("swift"),
            ),
          ),
        ),
      )
    }
    assertContains(unknownPlatform.message.orEmpty(), "Unknown platform pack selection: swift")

    val duplicateSkill = assertFailsWith<IllegalArgumentException> {
      InstallPlanPolicy.buildPlanDraft(
        policyInput(
          platformPacks = listOf(
            platformPack(
              slug = "duplicate",
              skills = listOf(platformSkill("bill-code-review", platformSlug = "duplicate")),
            ),
          ),
          request = request(
            platformPackSelection = PlatformPackSelection(
              mode = PlatformPackSelectionMode.SELECTED,
              selectedSlugs = setOf("duplicate"),
            ),
          ),
        ),
      )
    }
    assertContains(duplicateSkill.message.orEmpty(), "duplicate skill name")
    assertContains(duplicateSkill.message.orEmpty(), "bill-code-review")
  }

  @Test
  fun `validate install plan snapshot delegates to the injected wire validator port`() {
    // SKILL-52.3 Subtask 1: the concrete schema validator now lives in
    // `runtime-infra-fs`; the domain policy reaches it only through the
    // injected `InstallPlanWireValidator` port. This test pins the seam
    // contract (the policy builds the wire map and hands it to the port,
    // surfacing the port's typed error). Real-schema coverage lives in
    // the infra-fs `InstallPlanSchemaViolationsTest` and the dedicated
    // dual-seam install validation test.
    val draft = InstallPlanPolicy.buildPlanDraft(policyInput())
    val plan = draft.toInstallPlan(
      staging = InstallStagingIntent(
        root = path("/home/.skill-bill/installed-skills"),
        skillPaths = draft.skills.map { skill ->
          InstallStagingPathIntent(
            skillName = skill.name,
            sourceDir = skill.sourceDir,
            stagingRoot = path("/home/.skill-bill/installed-skills"),
            stagingDir = path("/home/.skill-bill/installed-skills/${skill.name}-abc"),
            contentHash = "abc",
          )
        },
      ),
    )

    var capturedWireMap: Map<String, Any?>? = null
    val recordingValidator = object : InstallPlanWireValidator {
      override fun validate(plan: Map<String, Any?>) {
        capturedWireMap = plan
      }
    }
    val result = InstallPlanPolicy.validateInstallPlanSnapshot(plan, recordingValidator)
    assertEquals(InstallPolicyValidationStatus.VALID, result.status)
    assertEquals("planned", capturedWireMap?.get("status"))

    val loudFailValidator = object : InstallPlanWireValidator {
      override fun validate(plan: Map<String, Any?>) {
        throw InvalidInstallPlanSchemaError(
          fieldPath = "mcp_registration.runtime_mcp_bin",
          reason = "must be a non-empty string when register is true.",
        )
      }
    }
    val error = assertFailsWith<InvalidInstallPlanSchemaError> {
      InstallPlanPolicy.validateInstallPlanSnapshot(plan, loudFailValidator)
    }
    assertContains(error.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }

  private fun policyInput(
    request: skillbill.install.model.InstallPlanRequest = request(),
    baseSkills: List<InstallPlanSkill> = listOf(baseSkill("bill-code-review")),
    platformPacks: List<InstallPlatformPackSnapshot> = listOf(platformPack()),
    detectedAgentTargets: List<InstallAgentTarget> = emptyList(),
    defaultAgentTargets: List<InstallAgentDefaultTarget> = defaultAgentTargets(),
  ): InstallPolicyInput = InstallPolicyInput(
    request = request,
    baseSkills = baseSkills,
    platformPacks = platformPacks,
    detectedAgentTargets = detectedAgentTargets,
    defaultAgentTargets = defaultAgentTargets,
  )

  private fun defaultAgentTargets(): List<InstallAgentDefaultTarget> = listOf(
    InstallAgentDefaultTarget(InstallAgent.CLAUDE, path("/home/.claude/commands")),
    InstallAgentDefaultTarget(InstallAgent.CODEX, path("/home/.codex/skills")),
    InstallAgentDefaultTarget(InstallAgent.COPILOT, path("/home/.copilot/skills")),
    InstallAgentDefaultTarget(InstallAgent.JUNIE, path("/home/.junie/skills")),
    InstallAgentDefaultTarget(InstallAgent.OPENCODE, path("/home/.config/opencode/skills")),
  )

  private fun request(
    agentSelection: InstallAgentSelection = InstallAgentSelection(
      mode = InstallAgentSelectionMode.MANUAL,
      manualAgents = setOf(InstallAgent.CODEX),
    ),
    platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
    targetPaths: InstallationTargetPaths = targetPaths(),
    mcpRegistrationChoice: McpRegistrationChoice = McpRegistrationChoice(
      register = true,
      runtimeMcpBin = path("/runtime-mcp"),
    ),
  ): skillbill.install.model.InstallPlanRequest = skillbill.install.model.InstallPlanRequest(
    repoRoot = path("/repo"),
    home = path("/home"),
    agentSelection = agentSelection,
    platformPackSelection = platformPackSelection,
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = mcpRegistrationChoice,
    runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = path("/home/.skill-bill/runtime")),
    targetPaths = targetPaths,
    windowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
  )

  private fun targetPaths(agentTargets: List<InstallAgentTarget> = emptyList()): InstallationTargetPaths =
    InstallationTargetPaths(
      skillsRoot = path("/repo/skills"),
      platformPacksRoot = path("/repo/platform-packs"),
      agentTargets = agentTargets,
    )

  private fun platformPack(
    slug: String = "kotlin",
    skills: List<InstallPlanSkill> = listOf(platformSkill("bill-kotlin-code-review", platformSlug = slug)),
  ): InstallPlatformPackSnapshot = InstallPlatformPackSnapshot(
    slug = slug,
    packRoot = path("/repo/platform-packs/$slug"),
    skills = skills,
  )

  private fun baseSkill(name: String, sourceDir: Path = path("/repo/skills/$name")): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = sourceDir,
      kind = InstallPlanSkillKind.BASE,
    )

  private fun platformSkill(name: String, platformSlug: String): InstallPlanSkill = InstallPlanSkill(
    name = name,
    sourceDir = path("/repo/platform-packs/$platformSlug/code-review/$name"),
    kind = InstallPlanSkillKind.PLATFORM_PACK,
    platformSlug = platformSlug,
  )

  private fun path(value: String): Path = Path.of(value)
}
