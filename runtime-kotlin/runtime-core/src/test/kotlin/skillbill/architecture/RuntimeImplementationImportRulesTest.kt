package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeImplementationImportRulesTest {
  @Test
  fun `adapter low-level implementation import scanner catches known bad packages`() {
    val mustBeDetected = listOf(
      "skillbill.infrastructure.sqlite.ReviewDatabase",
      "skillbill.infrastructure.fs.FileSystemScaffoldGateway",
      "skillbill.infrastructure.http.HttpTelemetryClient",
      "skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory",
      "skillbill.infrastructure.fs.install.InstallOperations",
      "skillbill.infrastructure.fs.launcher.McpRegistrationOperations",
      "skillbill.infrastructure.fs.nativeagent.NativeAgentOperations",
      "skillbill.review.ReviewRuntime",
      "skillbill.infrastructure.fs.scaffold.ScaffoldService",
      "skillbill.infrastructure.fs.skillremove.SkillRemoveJvmFileSystem",
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

  @Test
  fun `schema or coherence validator import scanner catches known validators`() {
    // SKILL-52.3 subtask 5 (AC3) positive control: the `*SchemaValidator` /
    // `*CoherenceValidator` ban predicate used by the runtime-domain +
    // runtime-application validator-import guard must fire on every concrete
    // validator FQN regardless of owning module, and must NOT flag the
    // domain-owned validator PORTS (which are how pure layers reach validation).
    val mustBeDetected = listOf(
      "skillbill.infrastructure.fs.contracts.install.InstallPlanSchemaValidator",
      "skillbill.infrastructure.fs.contracts.workflow.WorkflowStateSchemaValidator",
      "skillbill.infrastructure.fs.contracts.workflow.CanonicalWorkflowStateSchemaValidator",
      "skillbill.infrastructure.fs.contracts.workflow.DecompositionManifestSchemaValidator",
      "skillbill.infrastructure.fs.contracts.workflow.DecompositionManifestCoherenceValidator",
      "skillbill.infrastructure.fs.scaffold.PlatformPackSchemaValidator",
      "skillbill.infrastructure.fs.nativeagent.NativeAgentCompositionSchemaValidator",
    )
    val mustNotBeDetected = listOf(
      // Domain-owned validator PORTS — the sanctioned reach into validation.
      "skillbill.install.model.InstallPlanWireValidator",
      "skillbill.workflow.decomposition.DecompositionManifestValidator",
      "skillbill.workflow.engine.WorkflowSnapshotValidator",
      // Unrelated types.
      "skillbill.application.InstallService",
      "skillbill.infrastructure.fs.contracts.install.InstallPlanSchemaPaths",
    )

    assertEquals(
      emptyList(),
      mustBeDetected.filterNot(::isSchemaOrCoherenceValidatorImport),
      "Schema/coherence validator import scanner must catch every concrete *SchemaValidator/*CoherenceValidator.",
    )
    assertEquals(
      emptyList(),
      mustNotBeDetected.filter(::isSchemaOrCoherenceValidatorImport),
      "Schema/coherence validator import scanner must allow validator ports and unrelated types.",
    )
  }
}
