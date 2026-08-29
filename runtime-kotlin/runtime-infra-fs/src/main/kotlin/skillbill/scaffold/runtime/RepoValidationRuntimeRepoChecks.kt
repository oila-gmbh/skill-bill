package skillbill.scaffold.runtime

import skillbill.agentaddon.discoverAgentAddons
import skillbill.error.ShellContentContractException
import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.discoverSkillClasses
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.resolveSkillClass
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal val repoValidationBoundaryLedgerDir: Path = Path.of("skills", "agent")
internal val repoValidationSkillReferencePattern = Regex("""(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])""")
internal val repoValidationOrchestrationPathPattern = Regex("""orchestration/[\w/.-]+""")
internal val repoValidationReadmeSkillRowPattern = Regex("""^\| `/(bill-[a-z0-9-]+)` \|""")
internal val repoValidationOverrideSectionPattern = Regex("""^## (bill-[a-z0-9-]+)$""")
internal val repoValidationExternalPlaybookReferencePatterns = listOf(
  Regex("""\.bill-shared/orchestration/""") to
    "must reference skill-local supporting files instead of install-local playbook paths",
)
internal val repoValidationInlineTelemetryContractMarkers = listOf(
  "Standalone-first contract",
  "child_steps aggregation",
  "Graceful degradation",
  "Routers never emit",
)

internal fun validateAgentAddons(root: Path, issues: MutableList<String>) {
    try {
      discoverAgentAddons(root)
    } catch (error: ShellContentContractException) {
      issues += "agent-addons: ${error.message}"
    }
  }
internal fun validateReadme(
    readme: Path,
    skillNames: Set<String>,
    internalSkills: Set<String>,
    issues: MutableList<String>,
  ) {
    if (!readme.isRegularFile()) {
      issues += "README.md is missing"
      return
    }
    val catalogSkills = Files.readAllLines(readme)
      .mapNotNull { line -> repoValidationReadmeSkillRowPattern.find(line)?.groupValues?.get(1) }
      .toSet()
    val missing = (skillNames - internalSkills) - catalogSkills
    if (missing.isNotEmpty()) {
      issues += "README.md catalog is missing skills: ${missing.sorted()}"
    }
  }

internal fun internalSkillNames(skillFiles: Map<String, Path>): Set<String> =
    skillFiles.entries.mapNotNull { (skillName, contentFile) ->
      if (parseInternalForFrontmatter(contentFile)?.isNotBlank() == true) skillName else null
    }.toSet()

internal fun validateSkillReferences(root: Path, skillNames: Set<String>, issues: MutableList<String>) {
    val scanRoots = listOf("skills", "platform-packs", "orchestration", ".agents").map(root::resolve)
    scanRoots.filter(Path::isDirectory).forEach { scanRoot ->
      Files.walk(scanRoot).use { stream ->
        stream
          .filter {
            it.isRegularFile() &&
              it.fileName.toString().endsWith(".md") &&
              isSkillReferenceScanTarget(it.relativeTo(root))
          }
          .forEach { file -> validateSkillReferencesInFile(file, root, skillNames, issues) }
      }
    }
  }

internal fun isSkillReferenceScanTarget(relativePath: Path): Boolean {
    val parts = relativePath.map(Path::toString)
    if (relativePath.startsWith(repoValidationBoundaryLedgerDir)) {
      return false
    }
    if (NATIVE_AGENT_SOURCE_DIR in parts) {
      return false
    }
    return NativeAgentProvider.entries.none { provider -> provider.directoryName in parts }
  }

internal fun validateSkillReferencesInFile(
    file: Path,
    root: Path,
    skillNames: Set<String>,
    issues: MutableList<String>,
  ) {
    val text = Files.readString(file)
    repoValidationSkillReferencePattern.findAll(text).forEach { match ->
      val referenced = match.value
      if (referenced !in skillNames && !isDocumentedExampleReference(file, root, referenced)) {
        issues += "${file.relativeTo(root)}: references unknown skill '$referenced'"
      }
    }
  }

internal fun validateSkillOverrides(
    overrideFile: Path,
    skillNames: Set<String>,
    required: Boolean,
    issues: MutableList<String>,
  ) {
    if (!overrideFile.isRegularFile()) {
      if (required) {
        issues += "${overrideFile.fileName}: required skill override file is missing"
      }
      return
    }
    Files.readAllLines(overrideFile).forEachIndexed { index, line ->
      val section = repoValidationOverrideSectionPattern.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
      if (section !in skillNames) {
        issues += "${overrideFile.fileName}:${index + 1}: override section references unknown skill '$section'"
      }
    }
  }

internal fun validateSupportingTargets(root: Path, skillNames: Set<String>, issues: MutableList<String>) {
    skillNames.flatMap { name -> requiredSupportingFilesForSkill(name, root) }.toSet().forEach { fileName ->
      val target = supportingFileTargets(root)[fileName]
      if (target == null || !Files.exists(target)) {
        issues += "supporting file '$fileName' target is missing"
      }
    }
  }

internal fun validateFeatureAddonDeclarations(root: Path, issues: MutableList<String>) {
    val staticTargets = supportingFileTargets(root).keys
    val classes = runCatching { discoverSkillClasses(root) }.getOrDefault(emptyList())
    val featureClassPointers = resolveSkillClass("bill-feature", classes)
      ?.pointers
      ?.map { pointer -> "$pointer.md" }
      .orEmpty()
      .filter { pointer -> pointer !in staticTargets }
    featureClassPointers.forEach { pointer ->
      issues += "orchestration/skill-classes/feature-task.yaml: feature-task support pointer '$pointer' " +
        "must be declared by a selected platform pack's feature_addon_usage instead of the global skill class."
    }

    loadFeatureAddonValidationPacks(root).forEach { pack ->
      val declaredPointers = pack.featureAddonUsage
        .filter { usage -> usage.consumer == "feature-task" }
        .flatMap { usage -> usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers } }
        .toSet()
      pack.pointers
        .filter { pointer ->
          pointer.skillRelativeDir == "feature-task" &&
            pointer.target.startsWith("platform-packs/${pack.slug}/addons/") &&
            pointer.target.endsWith(".md")
        }
        .filter { pointer -> pointer.name !in declaredPointers }
        .forEach { pointer ->
          issues += "platform-packs/${pack.slug}/platform.yaml: feature-task pointer '${pointer.name}' targets " +
            "'${pointer.target}' but is missing from feature_addon_usage.feature-task."
        }
    }
  }

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
  val expected = headings.joinToString("\n\n") { RepoValidationRuntime.extractH2(Files.readString(canonical), it) }.trim()
  val actual = headings.joinToString("\n\n") { RepoValidationRuntime.extractH2(Files.readString(specialist), it) }.trim()
  if (expected != actual) {
    issues += "orchestration/review-orchestrator/specialist-contract.md: shared specialist sections must exactly match PLAYBOOK.md"
  }
}
