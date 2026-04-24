package skillbill

import skillbill.cli.CliRuntime
import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus
import skillbill.db.DatabaseRuntime
import skillbill.install.InstallRuntime
import skillbill.launcher.LauncherRuntime
import skillbill.learnings.LearningsRuntime
import skillbill.mcp.McpRuntime
import skillbill.review.ReviewRuntime
import skillbill.scaffold.ScaffoldRuntime
import skillbill.telemetry.TelemetryRuntime
import skillbill.workflow.implement.FeatureImplementWorkflowRuntime
import skillbill.workflow.verify.FeatureVerifyWorkflowRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeModuleSmokeTest {
  @Test
  fun `runtime module declares package boundaries and reserved surface contracts`() {
    assertEquals("runtime-kotlin", RuntimeModule.NAME)
    assertEquals(17, RuntimeModule.TOOLCHAIN_JDK)
    assertDeclaredPackages()
    assertRuntimeSurfacePackages()
    assertReservedRuntimeContracts()
  }

  private fun assertDeclaredPackages() {
    assertEquals(expectedSubsystemPackages, RuntimeModule.declaredSubsystemPackages.toSet())
  }

  private fun assertRuntimeSurfacePackages() {
    assertEquals(
      expectedSubsystemPackages -
        setOf(
          "skillbill.application",
          "skillbill.contracts",
          "skillbill.di",
          "skillbill.error",
          "skillbill.infrastructure",
          "skillbill.ports",
        ),
      runtimeSurfacePackages,
    )
    assertTrue(runtimeSurfaces.all { it.qualifiedName?.startsWith("skillbill.") == true })
  }

  private fun assertReservedRuntimeContracts() {
    assertEquals(
      setOf(
        "install",
        "launcher",
        "scaffold",
        "feature-implement-workflow",
        "feature-verify-workflow",
      ),
      reservedContracts.map(RuntimeSurfaceContract::name).toSet(),
    )
    reservedContracts.forEach { contract ->
      assertEquals("0.1", contract.contractVersion)
      assertEquals(RuntimeSurfaceStatus.RESERVED, contract.status)
      assertTrue(contract.placeholderReason.length > 60, "Placeholder reason is too weak for ${contract.name}")
      assertTrue(contract.supportedOperations.isEmpty(), "Reserved surfaces must not claim operations")
    }
  }

  private companion object {
    val expectedSubsystemPackages: Set<String> =
      setOf(
        "skillbill.application",
        "skillbill.cli",
        "skillbill.di",
        "skillbill.launcher",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.telemetry",
        "skillbill.review",
        "skillbill.learnings",
        "skillbill.ports",
        "skillbill.infrastructure",
        "skillbill.workflow.implement",
        "skillbill.workflow.verify",
        "skillbill.scaffold",
        "skillbill.contracts",
        "skillbill.install",
        "skillbill.error",
      )
    val runtimeSurfaces =
      listOf(
        CliRuntime::class,
        LauncherRuntime::class,
        McpRuntime::class,
        DatabaseRuntime::class,
        TelemetryRuntime::class,
        ReviewRuntime::class,
        LearningsRuntime::class,
      )
    val reservedContracts =
      listOf(
        InstallRuntime.contract,
        LauncherRuntime.contract,
        ScaffoldRuntime.contract,
        FeatureImplementWorkflowRuntime.contract,
        FeatureVerifyWorkflowRuntime.contract,
      )
    val runtimeSurfacePackages: Set<String> =
      runtimeSurfaces
        .mapNotNull { it.qualifiedName?.substringBeforeLast(".") }
        .toSet() + reservedContracts.map(RuntimeSurfaceContract::ownerPackage)
  }
}
