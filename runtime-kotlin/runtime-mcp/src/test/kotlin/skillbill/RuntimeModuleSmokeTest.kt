package skillbill

import skillbill.cli.core.CliRuntime
import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus
import skillbill.db.core.DatabaseRuntime
import skillbill.install.runtime.InstallRuntime
import skillbill.launcher.agentrun.LauncherRuntime
import skillbill.learnings.LearningsRuntime
import skillbill.mcp.core.McpRuntime
import skillbill.nativeagent.discovery.NativeAgentRuntime
import skillbill.review.ReviewParser
import skillbill.scaffold.runtime.ScaffoldRuntime
import skillbill.telemetry.config.TelemetryRuntime
import skillbill.workflow.implement.FeatureImplementWorkflowRuntime
import skillbill.workflow.verify.FeatureVerifyWorkflowRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeModuleSmokeTest {
  @Test
  fun `runtime module declares package boundaries and workflow surface contracts`() {
    assertEquals("runtime-kotlin", RuntimeModule.NAME)
    assertEquals(17, RuntimeModule.TOOLCHAIN_JDK)
    assertEquals(
      setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-infra-fs",
        "runtime-infra-http",
        "runtime-infra-sqlite",
        "runtime-cli",
        "runtime-desktop",
        "runtime-desktop:core:common",
        "runtime-desktop:core:data",
        "runtime-desktop:core:database",
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:navigation",
        "runtime-desktop:core:testing",
        "runtime-desktop:core:ui",
        "runtime-desktop:feature:skillbill",
        "runtime-mcp",
        "runtime-ports",
      ),
      RuntimeModule.declaredGradleModules.toSet(),
    )
    assertDeclaredPackages()
    assertRuntimeSurfacePackages()
    assertRuntimeContracts()
  }

  private fun assertDeclaredPackages() {
    assertEquals(expectedSubsystemPackages, RuntimeModule.declaredSubsystemPackages.toSet())
  }

  private fun assertRuntimeSurfacePackages() {
    assertEquals(
      expectedRuntimeSurfacePackages,
      runtimeSurfacePackages,
    )
    assertTrue(runtimeSurfaces.all { it.qualifiedName?.startsWith("skillbill.") == true })
  }

  private fun assertRuntimeContracts() {
    assertEquals(
      emptySet(),
      reservedContracts.map(RuntimeSurfaceContract::name).toSet(),
    )
    reservedContracts.forEach { contract ->
      assertEquals("0.1", contract.contractVersion)
      assertEquals(RuntimeSurfaceStatus.RESERVED, contract.status)
      assertTrue(contract.placeholderReason.length > 60, "Placeholder reason is too weak for ${contract.name}")
      assertTrue(contract.supportedOperations.isEmpty(), "Reserved surfaces must not claim operations")
    }
    workflowContracts.forEach { contract ->
      assertEquals("0.1", contract.contractVersion)
      assertEquals(RuntimeSurfaceStatus.ACTIVE, contract.status)
      assertEquals(
        listOf("open", "update", "get", "list", "latest", "resume", "continue"),
        contract.supportedOperations,
      )
    }
    assertEquals(
      setOf("install", "launcher", "native-agent", "scaffold"),
      activeRuntimeContracts.map(RuntimeSurfaceContract::name).toSet(),
    )
    activeRuntimeContracts.forEach { contract ->
      assertEquals("0.1", contract.contractVersion)
      assertEquals(RuntimeSurfaceStatus.ACTIVE, contract.status)
      assertTrue(contract.placeholderReason.isEmpty(), "Active surfaces must not carry placeholder reasons")
      assertTrue(contract.supportedOperations.isNotEmpty(), "Active surfaces must name supported operations")
    }
  }

  private companion object {
    val expectedSubsystemPackages: Set<String> =
      setOf(
        "skillbill.application",
        "skillbill.boundary",
        "skillbill.cli",
        "skillbill.config",
        "skillbill.desktop",
        "skillbill.di",
        "skillbill.launcher",
        "skillbill.mcp",
        "skillbill.model",
        "skillbill.db",
        "skillbill.telemetry",
        "skillbill.team",
        "skillbill.review",
        "skillbill.learnings",
        "skillbill.ports",
        "skillbill.infrastructure",
        "skillbill.domain.skillremove",
        "skillbill.workflow.implement",
        "skillbill.workflow.verify",
        "skillbill.scaffold",
        "skillbill.contracts",
        "skillbill.install",
        "skillbill.nativeagent",
        "skillbill.error",
        "skillbill.featurespec",
        "skillbill.goalrunner",
        "skillbill.skillremove",
        "skillbill.workflow",
      )
    val runtimeSurfaces =
      listOf(
        CliRuntime::class,
        LauncherRuntime::class,
        McpRuntime::class,
        DatabaseRuntime::class,
        TelemetryRuntime::class,
        ReviewParser::class,
        LearningsRuntime::class,
        InstallRuntime::class,
        NativeAgentRuntime::class,
        ScaffoldRuntime::class,
      )
    val expectedRuntimeSurfacePackages: Set<String> =
      setOf(
        "skillbill.cli.core",
        "skillbill.launcher.agentrun",
        "skillbill.mcp.core",
        "skillbill.db.core",
        "skillbill.telemetry.config",
        "skillbill.review",
        "skillbill.learnings",
        "skillbill.install.runtime",
        "skillbill.nativeagent.discovery",
        "skillbill.scaffold.runtime",
        "skillbill.workflow.implement",
        "skillbill.workflow.verify",
      )
    val reservedContracts =
      emptyList<RuntimeSurfaceContract>()
    val activeRuntimeContracts = listOf(
      InstallRuntime.contract,
      LauncherRuntime.contract,
      NativeAgentRuntime.contract,
      ScaffoldRuntime.contract,
    )
    val workflowContracts = listOf(FeatureImplementWorkflowRuntime.contract, FeatureVerifyWorkflowRuntime.contract)
    val runtimeSurfacePackages: Set<String> =
      runtimeSurfaces
        .mapNotNull { it.qualifiedName?.substringBeforeLast(".") }
        .toSet() + reservedContracts.map(RuntimeSurfaceContract::ownerPackage) +
        workflowContracts.map(RuntimeSurfaceContract::ownerPackage)
  }
}
