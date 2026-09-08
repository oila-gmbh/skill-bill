package skillbill.scaffold.runtime

import skillbill.error.ShellContentContractException
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal fun loadFeatureAddonValidationPacks(root: Path): List<PlatformManifest> {
  val packsRoot = root.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return emptyList()
  }
  val packs = mutableListOf<PlatformManifest>()
  Files.list(packsRoot).use { stream ->
    stream
      .filter { it.isDirectory() && !it.name.startsWith(".") }
      .forEach { packRoot ->
        try {
          packs += loadPlatformManifest(packRoot)
        } catch (_: ShellContentContractException) {
        }
      }
  }
  return packs
}
internal fun validateWorkflowContracts(root: Path, issues: MutableList<String>) {
  val checks = mapOf(
    "skills/bill-feature-verify/content.md" to listOf(
      "Step id: `collect_inputs`",
      "Step id: `code_review`",
      "Step id: `unit_test_value_check`",
      "Step id: `verdict`",
      "feature_verify_workflow_open",
      "feature_verify_workflow_update",
      "feature_verify_workflow_get",
      "feature_verify_workflow_continue",
      "`input_context`",
      "`criteria_summary`",
      "`diff_projection`",
      "`feature_flag_audit_receipt`",
      "`code_review_receipt`",
      "`unit_test_value_receipt`",
      "`completeness_audit_receipt`",
      "`verdict_result`",
    ),
  )
  checks.forEach { (relativePath, markers) ->
    val file = root.resolve(relativePath)
    if (!file.isRegularFile()) {
      issues += "$relativePath is missing"
      return@forEach
    }
    val text = Files.readString(file)
    markers.forEach { marker ->
      if (marker !in text) {
        issues += "$relativePath: missing workflow contract marker '$marker'"
      }
    }
  }
}
internal fun validateOrchestrationPlaybooks(root: Path, issues: MutableList<String>) {
  ORCHESTRATION_PLAYBOOKS.values.forEach { relativePath ->
    val file = root.resolve(relativePath)
    if (!file.isRegularFile()) {
      issues += "$relativePath is missing"
      return@forEach
    }
    val text = Files.readString(file)
    repoValidationExternalPlaybookReferencePatterns.forEach { (pattern, message) ->
      if (pattern.containsMatchIn(text)) {
        issues += "$relativePath: $message"
      }
    }
  }
}

internal fun validateNoInlineTelemetryContractDrift(root: Path, issues: MutableList<String>) {
  val telemetryPlaybook = root.resolve("orchestration/telemetry-contract/PLAYBOOK.md")
  if (!telemetryPlaybook.isRegularFile()) {
    issues += "orchestration/telemetry-contract/PLAYBOOK.md is missing"
    return
  }
  val telemetryText = Files.readString(telemetryPlaybook)
  repoValidationInlineTelemetryContractMarkers.forEach { marker ->
    if (marker !in telemetryText) {
      issues += "orchestration/telemetry-contract/PLAYBOOK.md: missing telemetry marker '$marker'"
    }
  }
  discoverSkillFiles(root, mutableListOf()).forEach { (_, skillFile) ->
    val contentFile = skillFile.parent.resolve("content.md")
    if (!contentFile.isRegularFile()) {
      return@forEach
    }
    val text = Files.readString(contentFile)
    repoValidationInlineTelemetryContractMarkers.forEach { marker ->
      if (marker in text) {
        issues += "$contentFile: must not inline shared telemetry contract marker '$marker'"
      }
    }
  }
}

internal fun validatePluginManifest(pluginPath: Path, issues: MutableList<String>) {
  if (!pluginPath.isRegularFile()) {
    return
  }
  val text = Files.readString(pluginPath)
  listOf("\"name\"", "\"version\"").forEach { marker ->
    if (marker !in text) {
      issues += "$pluginPath: plugin manifest is missing $marker"
    }
  }
}

internal fun validatePointerTargetParityIssues(root: Path): List<String> {
  val packsRoot = root.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return emptyList()
  }
  val packs = mutableListOf<PlatformManifest>()
  Files.list(packsRoot).use { stream ->
    stream
      .filter { it.isDirectory() && !it.name.startsWith(".") }
      .forEach { packRoot ->
        try {
          packs += loadPlatformManifest(packRoot)
        } catch (_: ShellContentContractException) {
        }
      }
  }
  return validatePointerTargetParity(root, packs)
}

internal fun isDocumentedExampleReference(file: Path, root: Path, referenced: String): Boolean {
  val relative = file.relativeTo(root).toString()
  if (relative == "orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md") {
    return referenced in setOf(
      "bill-java-code-review",
      "bill-java-code-check",
      "bill-kotlin-code-review-new",
      "bill-new-horizontal",
    )
  }
  return false
}

internal fun validateNoOrchestrationPathsInSkillBodies(
  root: Path,
  skillFiles: Map<String, Path>,
  platformSkillFiles: Map<String, Path>,
  issues: MutableList<String>,
) {
  (skillFiles.values + platformSkillFiles.values).forEach { contentFile ->
    if (!contentFile.isRegularFile()) return@forEach
    val text = Files.readString(contentFile)
    repoValidationOrchestrationPathPattern.findAll(text).forEach { match ->
      issues += "${contentFile.relativeTo(root)}: must not reference bare orchestration path '${match.value}'"
    }
  }
}

internal fun validateSpecialistContractParity(root: Path, issues: MutableList<String>) {
  val canonical = root.resolve("orchestration/review-orchestrator/PLAYBOOK.md")
  val specialist = root.resolve("orchestration/review-orchestrator/specialist-contract.md")
  if (!canonical.isRegularFile() || !specialist.isRegularFile()) return
  val headings = listOf("Shared Contract For Every Specialist", "Shared Report Structure")
  val expected =
    headings.joinToString("\n\n") { RepoValidationRuntime.extractH2(Files.readString(canonical), it) }.trim()
  val actual =
    headings.joinToString("\n\n") { RepoValidationRuntime.extractH2(Files.readString(specialist), it) }.trim()
  if (expected != actual) {
    issues +=
      "orchestration/review-orchestrator/specialist-contract.md: " +
      "shared specialist sections must exactly match PLAYBOOK.md"
  }
}
