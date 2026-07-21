package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.CanonicalWorkflowStateSchemaValidator
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.WorkflowStateSchemaPaths
import skillbill.testing.repoRootFromTest
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 2a AC2: pins `contract_version` parity between the
 * canonical schema file (`orchestration/contracts/workflow-state-schema.yaml`)
 * and the runtime constant `WORKFLOW_STATE_CONTRACT_VERSION`. Bumping
 * one without the other is a build break, by design.
 */
class WorkflowStateSchemaContractVersionTest {
  @Test
  fun `workflow state schema bundled on runtime contracts classpath matches canonical schema`() {
    val schemaFile = repoRootFromTest().resolve(WorkflowStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    val canonicalSchema = YAMLMapper().readTree(Files.readString(schemaFile))
    val classpathSchema = loadClasspathSchemaNode()

    assertEquals(
      canonicalSchema,
      classpathSchema,
      "Classpath workflow-state schema at '${WorkflowStateSchemaPaths.CLASSPATH_RESOURCE}' must match " +
        "the canonical schema at ${WorkflowStateSchemaPaths.REPO_RELATIVE_PATH}.",
    )
  }

  @Test
  fun `schema contract_version const matches WORKFLOW_STATE_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(WorkflowStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal WORKFLOW_STATE_CONTRACT_VERSION " +
        "($WORKFLOW_STATE_CONTRACT_VERSION).",
    )
  }

  /**
   * F-205: the contract_version parity is a THREE-way invariant — the
   * runtime constant, the canonical schema's const, and every shipped
   * `WorkflowDefinition.contractVersion` must agree. Pin all three so
   * bumping one without the others is a build break, by design.
   */
  @Test
  fun `every shipped WorkflowDefinition contractVersion matches WORKFLOW_STATE_CONTRACT_VERSION`() {
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      FeatureImplementWorkflowDefinition.definition.contractVersion,
      "FeatureImplementWorkflowDefinition.contractVersion must equal WORKFLOW_STATE_CONTRACT_VERSION " +
        "($WORKFLOW_STATE_CONTRACT_VERSION).",
    )
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      FeatureVerifyWorkflowDefinition.definition.contractVersion,
      "FeatureVerifyWorkflowDefinition.contractVersion must equal WORKFLOW_STATE_CONTRACT_VERSION " +
        "($WORKFLOW_STATE_CONTRACT_VERSION).",
    )
  }

  /**
   * F-302: pins the per-skill enum sets in the schema's `oneOf` branches
   * to every shipped `WorkflowDefinition`'s `stepIds` /
   * `workflowStatuses` — both directions, so neither side can silently
   * drift. The schema branch enums add an empty-string allowance to
   * `current_step_id` (a freshly opened record before the first step
   * transition); every OTHER enum must be identical to the Kotlin
   * definition's set.
   */
  @Test
  fun `featureImplement branch enums match FeatureImplementWorkflowDefinition`() {
    val schema = loadSchemaNode()
    val branch = schema.path("\$defs").path("featureImplementBranch")
    val definition = FeatureImplementWorkflowDefinition.definition

    assertBranchStatusesMatch(branch, definition.workflowStatuses, "featureImplementBranch")
    assertBranchCurrentStepIdsMatch(branch, definition.stepIds.toSet(), "featureImplementBranch")
    assertBranchStepsStepIdMatch(branch, definition.stepIds.toSet(), "featureImplementBranch")
  }

  @Test
  fun `featureVerify branch enums match FeatureVerifyWorkflowDefinition`() {
    val schema = loadSchemaNode()
    val branch = schema.path("\$defs").path("featureVerifyBranch")
    val definition = FeatureVerifyWorkflowDefinition.definition

    assertBranchStatusesMatch(branch, definition.workflowStatuses, "featureVerifyBranch")
    assertBranchCurrentStepIdsMatch(branch, definition.stepIds.toSet(), "featureVerifyBranch")
    assertBranchStepsStepIdMatch(branch, definition.stepIds.toSet(), "featureVerifyBranch")
  }

  @Test
  fun `featureTaskRuntime branch enums match FeatureTaskRuntimePhaseWorkflowDefinition`() {
    val schema = loadSchemaNode()
    val branch = schema.path("\$defs").path("featureTaskRuntimeBranch")
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

    assertBranchStatusesMatch(branch, definition.workflowStatuses, "featureTaskRuntimeBranch")
    assertBranchCurrentStepIdsMatch(branch, definition.stepIds.toSet(), "featureTaskRuntimeBranch")
    assertBranchStepsStepIdMatch(branch, definition.stepIds.toSet(), "featureTaskRuntimeBranch")
  }

  /**
   * SKILL-135 Subtask 1 AC6: the feature-task-runtime branch constrains the step-id MEMBERSHIP set
   * and never its order, which is why reordering the phase pipeline to audit-first needed no
   * `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` bump. Pinned explicitly so a future change that makes the
   * schema order-sensitive, or that adds a step id, cannot silently skip the bump decision.
   */
  @Test
  fun `featureTaskRuntime branch pins the step-id set only, so reordering needs no contract bump`() {
    val branch = loadSchemaNode().path("\$defs").path("featureTaskRuntimeBranch")
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    assertBranchStepsStepIdMatch(branch, definition.stepIds.toSet(), "featureTaskRuntimeBranch")
    // Order-insensitivity is a property of the schema KEYWORDS, not of comparing an equal set twice:
    // `steps` must constrain each item uniformly, never positionally.
    val steps = branch.path("properties").path("steps")
    assertTrue(
      steps.path("prefixItems").isMissingNode && steps.path("items").path("prefixItems").isMissingNode,
      "Schema featureTaskRuntimeBranch.steps must not declare positional item schemas: a positional " +
        "constraint would make the durable step order part of the contract and a reorder a bump.",
    )
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      definition.contractVersion,
      "Reordering the feature-task-runtime phase pipeline must not move the contract version.",
    )
  }

  private fun loadSchemaNode(): JsonNode {
    val schemaFile = repoRootFromTest().resolve(WorkflowStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    return YAMLMapper().readTree(Files.readString(schemaFile))
  }

  private fun loadClasspathSchemaNode(): JsonNode {
    val resourceStream = CanonicalWorkflowStateSchemaValidator::class.java.classLoader
      .getResourceAsStream(WorkflowStateSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical workflow-state schema is missing from the classpath at " +
        "'${WorkflowStateSchemaPaths.CLASSPATH_RESOURCE}'. Ensure `copyWorkflowStateSchema` ran before this test.",
    )
    val schemaText = resourceStream.bufferedReader().use { it.readText() }
    return YAMLMapper().readTree(schemaText)
  }

  private fun JsonNode.enumStrings(): Set<String> = path("enum")
    .takeIf { !it.isMissingNode && it.isArray }
    ?.let { node -> node.elements().asSequence().map { it.asText() }.toSet() }
    .orEmpty()

  private fun assertBranchStatusesMatch(branch: JsonNode, expected: Set<String>, branchName: String) {
    val actual = branch.path("properties").path("workflow_status").enumStrings()
    assertEquals(
      expected,
      actual,
      "Schema $branchName.workflow_status enum must equal the Kotlin definition's workflowStatuses set.",
    )
  }

  private fun assertBranchCurrentStepIdsMatch(branch: JsonNode, expected: Set<String>, branchName: String) {
    val actual = branch.path("properties").path("current_step_id").enumStrings()
    // Schema allows the empty string as a freshly-opened-record sentinel;
    // strip it before comparing to the definition's step set.
    val actualWithoutEmpty = actual - ""
    assertEquals(
      expected,
      actualWithoutEmpty,
      "Schema $branchName.current_step_id enum (minus the empty-string sentinel) " +
        "must equal the Kotlin definition's stepIds set.",
    )
    assertTrue(
      "" in actual,
      "Schema $branchName.current_step_id enum must allow the empty-string sentinel for freshly-opened records.",
    )
  }

  private fun assertBranchStepsStepIdMatch(branch: JsonNode, expected: Set<String>, branchName: String) {
    val items = branch.path("properties").path("steps").path("items")
    // The schema declares `items.allOf[1].properties.step_id.enum`; walk
    // the allOf array to find the entry that carries the step_id enum.
    val allOf = items.path("allOf")
    assertTrue(allOf.isArray, "Schema $branchName.steps.items.allOf must be an array.")
    val stepIdEnum = allOf.elements().asSequence()
      .map { it.path("properties").path("step_id") }
      .firstOrNull { !it.path("enum").isMissingNode }
      ?: error("Schema $branchName.steps.items must declare a step_id enum under allOf[].properties.step_id.")
    val actual = stepIdEnum.enumStrings()
    assertEquals(
      expected,
      actual,
      "Schema $branchName.steps.items.step_id enum must equal the Kotlin definition's stepIds set.",
    )
  }
}
