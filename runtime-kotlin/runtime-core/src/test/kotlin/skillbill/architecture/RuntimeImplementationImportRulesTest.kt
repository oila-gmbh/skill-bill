package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeImplementationImportRulesTest {
  @Test
  fun `adapter low-level implementation import scanner catches known bad packages`() {
    val mustBeDetected = listOf(
      "skillbill.db.ReviewDatabase",
      "skillbill.infrastructure.fs.FileSystemScaffoldGateway",
      "skillbill.infrastructure.http.HttpTelemetryClient",
      "skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory",
      "skillbill.install.InstallOperations",
      "skillbill.launcher.McpRegistrationOperations",
      "skillbill.nativeagent.NativeAgentOperations",
      "skillbill.review.ReviewRuntime",
      "skillbill.scaffold.ScaffoldService",
      "skillbill.skillremove.SkillRemoveJvmFileSystem",
      "skillbill.telemetry.TelemetryConfigRuntime",
      "skillbill.learnings.LearningsRuntime",
    )
    val mustNotBeDetected = listOf(
      "skillbill.application.ReviewService",
      "skillbill.install.model.InstallPlan",
      "skillbill.learnings.model.LearningScope",
      "skillbill.ports.review.ReviewInputSource",
      "skillbill.scaffold.model.ScaffoldResult",
      "skillbill.telemetry.model.RemoteStatsRequest",
    )

    assertEquals(
      emptyList(),
      mustBeDetected.filterNot(::isRuntimeImplementationImport),
      "Adapter implementation-import scanner must catch known low-level implementation packages.",
    )
    assertEquals(
      emptyList(),
      mustNotBeDetected.filter(::isRuntimeImplementationImport),
      "Adapter implementation-import scanner must allow application services, ports, and public models.",
    )
  }
}
