package skillbill.contract

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus
import skillbill.install.InstallRuntime
import skillbill.launcher.LauncherRuntime
import skillbill.scaffold.ScaffoldRuntime
import skillbill.workflow.implement.FeatureImplementWorkflowRuntime
import skillbill.workflow.verify.FeatureVerifyWorkflowRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSurfaceContractTest {
  @Test
  fun `launcher runtime contract locks current surface`() {
    assertSurfaceContract(
      contract = LauncherRuntime.contract,
      name = "launcher",
      ownerPackage = "skillbill.launcher",
      supportedOperations =
      listOf(
        "select-cli-runtime",
        // TODO(3c): remove python-fallback after the Python runtime retirement lands.
        "python-fallback",
        "select-mcp-runtime",
        // TODO(3c): remove mcp-python-fallback after the Python runtime retirement lands.
        "mcp-python-fallback",
      ),
    )
  }

  @Test
  fun `install runtime contract locks current surface`() {
    assertSurfaceContract(
      contract = InstallRuntime.contract,
      name = "install",
      ownerPackage = "skillbill.install",
      supportedOperations = listOf("agent-path", "detect-agents", "link-skill", "rollback-links"),
    )
  }

  @Test
  fun `scaffold runtime contract locks current surface`() {
    assertSurfaceContract(
      contract = ScaffoldRuntime.contract,
      name = "scaffold",
      ownerPackage = "skillbill.scaffold",
      supportedOperations = listOf("load-pack", "discover-packs", "discover-addons", "scaffold", "dry-run"),
    )
  }

  @Test
  fun `feature implement workflow runtime contract locks current surface`() {
    assertSurfaceContract(
      contract = FeatureImplementWorkflowRuntime.contract,
      name = "feature-implement-workflow",
      ownerPackage = "skillbill.workflow.implement",
      supportedOperations = workflowOperations,
    )
  }

  @Test
  fun `feature verify workflow runtime contract locks current surface`() {
    assertSurfaceContract(
      contract = FeatureVerifyWorkflowRuntime.contract,
      name = "feature-verify-workflow",
      ownerPackage = "skillbill.workflow.verify",
      supportedOperations = workflowOperations,
    )
  }

  private fun assertSurfaceContract(
    contract: RuntimeSurfaceContract,
    name: String,
    ownerPackage: String,
    supportedOperations: List<String>,
  ) {
    assertEquals(name, contract.name)
    assertEquals(ownerPackage, contract.ownerPackage)
    assertEquals("0.1", contract.contractVersion)
    assertEquals(RuntimeSurfaceStatus.ACTIVE, contract.status)
    assertEquals("", contract.placeholderReason)
    assertEquals(supportedOperations, contract.supportedOperations)
  }

  private companion object {
    val workflowOperations: List<String> = listOf("open", "update", "get", "list", "latest", "resume", "continue")
  }
}
