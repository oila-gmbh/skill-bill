package skillbill

import skillbill.cli.CliRuntime
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
  fun `package scaffold remains available for later subsystem ports`() {
    val expectedSubsystemPackages =
      setOf(
        "skillbill.cli",
        "skillbill.launcher",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.telemetry",
        "skillbill.review",
        "skillbill.learnings",
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
        FeatureImplementWorkflowRuntime::class,
        FeatureVerifyWorkflowRuntime::class,
        ScaffoldRuntime::class,
        InstallRuntime::class,
      )
    val runtimeSurfacePackages =
      runtimeSurfaces
        .mapNotNull { it.qualifiedName?.substringBeforeLast(".") }
        .toSet()

    assertEquals("runtime-kotlin", RuntimeModule.NAME)
    assertEquals(17, RuntimeModule.TOOLCHAIN_JDK)
    assertEquals(expectedSubsystemPackages, RuntimeModule.declaredSubsystemPackages.toSet())
    assertEquals(
      expectedSubsystemPackages - setOf("skillbill.contracts", "skillbill.error"),
      runtimeSurfacePackages,
    )
    assertTrue(runtimeSurfaces.all { it.qualifiedName?.startsWith("skillbill.") == true })
  }
}
