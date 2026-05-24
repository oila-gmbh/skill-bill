package skillbill.install.model

import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.contracts.install.InstallPlanSchemaPaths

/**
 * SKILL-48 Subtask 2b: single source of truth for the install-plan
 * wire map. Lives in `runtime-domain` so both `runtime-core` (the
 * primary `buildInstallPlan` seam) and `runtime-cli` (the emission
 * boundary) can construct the same map without inverting the module
 * dependency direction.
 *
 * The shape is pinned by
 * `orchestration/contracts/install-plan-schema.yaml`; the
 * `contract_version` key is sourced from
 * [INSTALL_PLAN_CONTRACT_VERSION] so a schema bump only requires
 * editing the schema YAML and [InstallPlanSchemaPaths].
 */
fun buildInstallPlanWireMap(plan: InstallPlan): Map<String, Any?> = mapOf(
  "status" to "planned",
  "contract_version" to INSTALL_PLAN_CONTRACT_VERSION,
  "agents" to plan.agents.map(::agentTargetWireMap),
  "platform_packs" to plan.discoveredPlatformPacks.map { pack ->
    mapOf(
      "slug" to pack.slug,
      "pack_root" to pack.packRoot.toString(),
      "selected" to pack.selected,
    )
  },
  "selected_platforms" to plan.selectedPlatformSlugs,
  "skills" to plan.skills.map { skill ->
    mapOf(
      "name" to skill.name,
      "kind" to skill.kind.wireName(),
      "platform" to skill.platformSlug,
      "source_dir" to skill.sourceDir.toString(),
    )
  },
  "staging_root" to plan.staging.root.toString(),
  "staging" to plan.staging.skillPaths.map { intent ->
    mapOf(
      "skill_name" to intent.skillName,
      "source_dir" to intent.sourceDir.toString(),
      "staging_dir" to intent.stagingDir.toString(),
      "content_hash" to intent.contentHash,
    )
  },
  "telemetry_level" to plan.telemetryLevel.id,
  "mcp_registration" to mapOf(
    "register" to plan.mcpRegistrationIntent.register,
    "runtime_mcp_bin" to plan.mcpRegistrationIntent.runtimeMcpBin?.toString(),
    "agents" to plan.mcpRegistrationIntent.agents.map(InstallAgent::id),
  ),
  "runtime_distribution" to mapOf(
    "runtime_install_root" to plan.runtimeDistributionInputs.runtimeInstallRoot.toString(),
    "runtime_cli_build_dir" to plan.runtimeDistributionInputs.runtimeCliBuildDir?.toString(),
    "runtime_mcp_build_dir" to plan.runtimeDistributionInputs.runtimeMcpBuildDir?.toString(),
    "runtime_cli_install_dir" to plan.runtimeDistributionInputs.runtimeCliInstallDir?.toString(),
    "runtime_mcp_install_dir" to plan.runtimeDistributionInputs.runtimeMcpInstallDir?.toString(),
    "runtime_launcher_bin_dir" to plan.runtimeDistributionInputs.runtimeLauncherBinDir?.toString(),
  ),
  "windows_symlink_preflight" to windowsPreflightWireMap(plan.windowsSymlinkPreflight),
  "replace_existing_skill_bill_links" to plan.request.replaceExistingSkillBillLinks,
)

private fun agentTargetWireMap(target: InstallAgentTarget): Map<String, Any?> = mapOf(
  "agent" to target.agent.id,
  "path" to target.path.toString(),
  "source" to target.source.name.lowercase(),
)

private fun windowsPreflightWireMap(preflight: WindowsSymlinkPreflight): Map<String, Any?> = mapOf(
  "state" to preflight.state.name.lowercase(),
  "decision" to preflight.decision.name.lowercase(),
  "message" to preflight.message,
)

private fun InstallPlanSkillKind.wireName(): String = name.lowercase()
