@file:Suppress("TooManyFunctions", "MaxLineLength")

package skillbill.scaffold

import skillbill.error.InvalidSkillMdShapeError
import skillbill.error.ShellContentContractException
import skillbill.nativeagent.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.discoverRepoNativeAgentSourceEntries
import skillbill.nativeagent.validateRepoNativeAgents
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

data class RepoValidationReport(
  val issues: List<String>,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val structuredIssues: List<RepoValidationIssue> = issues.map(RepoValidationIssue::fromRawIssue),
) {
  val passed: Boolean = issues.isEmpty()

  fun toPayload(): Map<String, Any?> = mapOf(
    "status" to if (passed) "passed" else "failed",
    "skill_count" to skillCount,
    "governed_addon_count" to addonCount,
    "platform_pack_count" to platformPackCount,
    "native_agent_count" to nativeAgentCount,
    "issues" to issues,
  )
}

data class RepoValidationIssue(
  val severity: RepoValidationIssueSeverity,
  val message: String,
  val sourcePath: String?,
  val code: String? = null,
  val name: String? = null,
  val exceptionName: String? = null,
) {
  companion object {
    fun fromRawIssue(raw: String): RepoValidationIssue {
      val separator = raw.indexOf(": ")
      return if (separator > 0 && raw.substring(0, separator).isNotBlank() &&
        !raw.substring(0, separator).contains(' ')
      ) {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = raw.substring(0, separator),
          message = raw.substring(separator + 2),
        )
      } else {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = null,
          message = raw,
        )
      }
    }
  }
}

enum class RepoValidationIssueSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class ReleaseRefMetadata(
  val tag: String,
  val version: String,
  val prerelease: Boolean,
) {
  fun toPayload(): Map<String, Any?> = mapOf(
    "tag" to tag,
    "version" to version,
    "prerelease" to prerelease,
  )
}

object RepoValidationRuntime {
  private val semverTagPattern =
    Regex(
      "^v(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)" +
        "(?:-(?<prerelease>(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
        "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
        "(?:\\+(?<build>[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
    )
  private val skillReferencePattern = Regex("""(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])""")
  private val orchestrationPathPattern = Regex("""orchestration/[\w/.-]+""")
  private val readmeSkillRowPattern = Regex("""^\| `/(bill-[a-z0-9-]+)` \|""")
  private val overrideSectionPattern = Regex("""^## (bill-[a-z0-9-]+)$""")
  private val addonSlugPattern = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")
  private const val GOVERNED_ADDON_PATH_PART_COUNT = 4
  private val externalPlaybookReferencePatterns = listOf(
    Regex("""\.bill-shared/orchestration/""") to
      "must reference skill-local supporting files instead of install-local playbook paths",
  )
  private val nonPortableReviewPatterns = listOf(
    Regex("""`task`""") to "must not hardcode the `task` tool in shared review orchestration",
    Regex("""\bspawn_agent\b""") to "must not hardcode the `spawn_agent` tool in shared review orchestration",
    Regex("""\bsub-agent(s)?\b""") to "must not describe review delegation as sub-agents",
    Regex("""\bAgent to spawn\b""") to "must use portable specialist-review wording",
    Regex("""\bAgents spawned\b""") to "must use portable specialist-review summary wording",
  )
  private val portableReviewSkills = setOf("bill-kotlin-code-review", "bill-kmp-code-review")
  private val inlineTelemetryContractMarkers = listOf(
    "Standalone-first contract",
    "child_steps aggregation",
    "Graceful degradation",
    "Routers never emit",
  )

  fun validateRepo(repoRoot: Path): RepoValidationReport {
    val root = repoRoot.toAbsolutePath().normalize()
    val issues = mutableListOf<String>()
    val skillFiles = discoverSkillFiles(root, issues)
    val platformSkillFiles = discoverPlatformPackSkillFiles(root, issues)
    val skillNames = (skillFiles.keys + platformSkillFiles.keys).toSortedSet()
    val addonFiles = discoverAllAddonFiles(root)
    val platformPacks = validatePlatformPacks(root, issues)
    val nativeAgentSources = runCatching { discoverRepoNativeAgentSourceEntries(root) }.getOrDefault(emptyList())

    skillFiles.forEach { (skillName, skillFile) ->
      validateInstallableSkill(skillName, skillFile, root, issues, validateSourceSidecars = true)
    }
    platformSkillFiles.forEach { (skillName, skillFile) ->
      validateInstallableSkill(skillName, skillFile, root, issues, validateSourceSidecars = false)
    }
    validateSkillSourceShape(skillFiles.values, root, issues)
    addonFiles.forEach { addonFile ->
      validateAddonFile(addonFile, root, issues)
    }

    validateReadme(root.resolve("README.md"), skillFiles.keys.toSet(), issues)
    validateSkillReferences(root, skillNames, issues)
    validateSkillOverrides(root.resolve(".agents/skill-overrides.example.md"), skillNames, required = true, issues)
    validateSkillOverrides(root.resolve(".agents/skill-overrides.md"), skillNames, required = false, issues)
    validateSupportingTargets(root, skillFiles.keys + platformSkillFiles.keys, issues)
    validateWorkflowContracts(root, issues)
    validateOrchestrationPlaybooks(root, issues)
    validateNoInlineTelemetryContractDrift(root, issues)
    validatePluginManifest(root.resolve(".claude-plugin/plugin.json"), issues)
    issues += validateRepoNativeAgents(root).issues
    issues += validatePointerTargetParityIssues(root)
    issues += validateGovernedSkillDrift(root).issues
    issues += validateGeneratedArtifactGuard(root).issues
    validateNoOrchestrationPathsInSkillBodies(root, skillFiles, platformSkillFiles, issues)

    return RepoValidationReport(
      issues = issues.sorted(),
      skillCount = skillNames.size,
      addonCount = addonFiles.size,
      platformPackCount = platformPacks,
      nativeAgentCount = nativeAgentSources.size,
    )
  }

  fun parseReleaseRef(rawValue: String): ReleaseRefMetadata {
    val candidate = rawValue.trim().removePrefix("refs/tags/")
    val match = semverTagPattern.matchEntire(candidate)
      ?: throw IllegalArgumentException(
        "Release tag must match vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata.",
      )
    return ReleaseRefMetadata(
      tag = candidate,
      version = candidate.removePrefix("v"),
      prerelease = match.groups["prerelease"] != null,
    )
  }

  fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) {
    Files.writeString(
      outputPath,
      buildString {
        appendLine("tag=${metadata.tag}")
        appendLine("version=${metadata.version}")
        appendLine("prerelease=${if (metadata.prerelease) "true" else "false"}")
      },
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.APPEND,
    )
  }

  private fun discoverSkillFiles(root: Path, issues: MutableList<String>): Map<String, Path> {
    val skillsDir = root.resolve("skills")
    if (!skillsDir.isDirectory()) {
      issues += "skills/ directory is missing"
      return emptyMap()
    }
    val found = linkedMapOf<String, Path>()
    val seenContent = mutableSetOf<Path>()
    Files.walk(skillsDir).use { stream ->
      stream
        .filter { it.fileName.toString() == "content.md" }
        .sorted()
        .forEach { contentFile ->
          seenContent.add(contentFile.parent)
          val skillName = contentFile.parent.name
          val previous = found.putIfAbsent(skillName, contentFile)
          if (previous != null) {
            issues += "Duplicate skill directory name '$skillName' found at ${previous.parent.relativeTo(
              root,
            )} and ${contentFile.parent.relativeTo(root)}"
          }
        }
    }
    Files.walk(skillsDir).use { stream ->
      stream
        .filter { it.fileName.toString() == "SKILL.md" }
        .sorted()
        .forEach { skillFile ->
          val parent = skillFile.parent
          if (parent !in seenContent) {
            issues += "${parent.relativeTo(root)}: SKILL.md found without sibling content.md " +
              "(authored content.md required since SKILL-40 subtask 1)"
          }
        }
    }
    if (found.isEmpty()) {
      issues += "No skills were found under skills/"
    }
    return found
  }

  private fun discoverPlatformPackSkillFiles(root: Path, issues: MutableList<String>): Map<String, Path> {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) {
      return emptyMap()
    }
    val found = linkedMapOf<String, Path>()
    val seenContent = mutableSetOf<Path>()
    Files.walk(packsRoot).use { stream ->
      stream
        .filter { it.fileName.toString() == "content.md" && it.parent.name.startsWith("bill-") }
        .sorted()
        .forEach { contentFile ->
          seenContent.add(contentFile.parent)
          found[contentFile.parent.name] = contentFile
        }
    }
    Files.walk(packsRoot).use { stream ->
      stream
        .filter { it.fileName.toString() == "SKILL.md" && it.parent.name.startsWith("bill-") }
        .sorted()
        .forEach { skillFile ->
          val parent = skillFile.parent
          if (parent !in seenContent) {
            issues += "${parent.relativeTo(root)}: SKILL.md found without sibling content.md " +
              "(authored content.md required since SKILL-40 subtask 1)"
          }
        }
    }
    return found
  }

  private fun validatePlatformPacks(root: Path, issues: MutableList<String>): Int {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) {
      return 0
    }
    var validCount = 0
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .sorted()
        .forEach { packRoot ->
          try {
            loadPlatformPack(packRoot)
            validCount += 1
          } catch (error: ShellContentContractException) {
            issues += "platform-packs/${packRoot.name}: ${error.message}"
          }
        }
    }
    return validCount
  }

  private fun validateInstallableSkill(
    skillName: String,
    contentFile: Path,
    root: Path,
    issues: MutableList<String>,
    validateSourceSidecars: Boolean,
  ) {
    val text = Files.readString(contentFile)
    val frontmatter = parseFrontmatter(text)
    if (frontmatter["name"] != skillName) {
      issues += "$contentFile: frontmatter name '${frontmatter["name"].orEmpty()}' does not match " +
        "directory '$skillName'"
    }
    if (frontmatter["description"].isNullOrBlank()) {
      issues += "$contentFile: frontmatter description is missing"
    }
    try {
      validateSkillMdShape(contentFile, validateBodyShape = false)
    } catch (error: InvalidSkillMdShapeError) {
      issues += error.message.orEmpty()
    }
    requiredSupportingFilesForSkill(skillName, root).forEach { fileName ->
      val expectedTarget = supportingFileTargets(root)[fileName]
      if (expectedTarget == null) {
        issues += "$contentFile: supporting file '$fileName' has no registered target"
      } else if (!Files.exists(expectedTarget)) {
        issues += "$contentFile: supporting file '$fileName' target is missing at ${expectedTarget.relativeTo(root)}"
      }
      if (validateSourceSidecars && isAuthoredSourceSidecar(contentFile, fileName, expectedTarget)) {
        validateSupportingSidecar(contentFile, fileName, expectedTarget, root, issues)
      }
    }
    validatePortableReviewWording(skillName, text, contentFile, issues)
    validateGovernedContentFile(contentFile, issues)
  }

  private fun validateSkillSourceShape(contentFiles: Collection<Path>, root: Path, issues: MutableList<String>) {
    contentFiles.forEach { contentFile ->
      val skillDir = contentFile.parent
      Files.walk(skillDir).use { stream ->
        stream
          .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) }
          .filter { path ->
            val rel = skillDir.relativize(path).toString().replace('\\', '/')
            rel != "content.md" && !rel.startsWith("$NATIVE_AGENT_SOURCE_DIR/")
          }
          .sorted()
          .forEach { path ->
            issues += "${path.relativeTo(root)}: skill source directories may contain only content.md " +
              "and native-agents/ source files"
          }
      }
    }
  }

  private fun parseFrontmatter(text: String): Map<String, String> {
    val match = Regex("""(?s)\A---\n(.*?)\n---\n""").find(text) ?: return emptyMap()
    return match.groupValues[1].lineSequence().mapNotNull { line ->
      val separator = line.indexOf(':')
      if (separator < 0) {
        null
      } else {
        line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"', '\'')
      }
    }.toMap()
  }

  private fun validateAddonFile(addonFile: Path, root: Path, issues: MutableList<String>) {
    val relative = addonFile.relativeTo(root)
    val parts = relative.map(Path::toString)
    if (
      parts.size != GOVERNED_ADDON_PATH_PART_COUNT ||
      parts[0] != "platform-packs" ||
      parts[2] != "addons"
    ) {
      issues += "$relative: governed add-ons must live directly in platform-packs/<pack>/addons/"
    } else if (!root.resolve("platform-packs").resolve(parts[1]).isDirectory()) {
      issues += "$relative: governed add-on owner pack '${parts[1]}' is missing"
    }
    val name = addonFile.fileName.toString()
    if (!name.endsWith(".md")) {
      issues += "$relative: governed add-on must be markdown"
    }
    val slug = name.removeSuffix(".md")
      .removeSuffix("-implementation")
      .removeSuffix("-review")
    if (!addonSlugPattern.matches(slug)) {
      issues += "$relative: governed add-on slug '$slug' must be lowercase kebab-case"
    }
  }

  private fun validateSupportingSidecar(
    contentFile: Path,
    fileName: String,
    expectedTarget: Path?,
    root: Path,
    issues: MutableList<String>,
  ) {
    if (expectedTarget == null) {
      return
    }
    val sidecar = contentFile.parent.resolve(fileName)
    val sidecarPath = sidecar.normalize().toAbsolutePath()
    val expectedPath = expectedTarget.normalize().toAbsolutePath()
    when {
      !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) ->
        issues += "$contentFile: required supporting sidecar '$fileName' is missing beside the skill"
      sidecarPath == expectedPath -> Unit
      !Files.isSymbolicLink(sidecar) && !isGitSymlinkPlaceholder(sidecar, expectedTarget) ->
        issues += "$contentFile: required supporting sidecar '$fileName' must be a symlink or git symlink placeholder"
      !Files.isSymbolicLink(sidecar) -> Unit
      else -> supportingSymlinkTargetIssue(contentFile, fileName, sidecar, expectedTarget, root)?.let(issues::add)
    }
  }

  private fun isAuthoredSourceSidecar(contentFile: Path, fileName: String, expectedTarget: Path?): Boolean {
    if (expectedTarget == null) {
      return false
    }
    val sidecar = contentFile.parent.resolve(fileName).normalize().toAbsolutePath()
    val expected = expectedTarget.normalize().toAbsolutePath()
    return sidecar == expected
  }

  private fun isGitSymlinkPlaceholder(sidecar: Path, expectedTarget: Path): Boolean {
    var matches = false
    if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
      val rawTarget = Files.readString(sidecar).trim()
      if (rawTarget.isNotBlank()) {
        val actualTarget = sidecar.parent.resolve(rawTarget).normalize().toAbsolutePath()
        val expected = expectedTarget.normalize().toAbsolutePath()
        matches = actualTarget == expected
      }
    }
    return matches
  }

  private fun supportingSymlinkTargetIssue(
    contentFile: Path,
    fileName: String,
    sidecar: Path,
    expectedTarget: Path,
    root: Path,
  ): String? {
    val actualTarget = sidecar.toRealPath()
    val expected = expectedTarget.toRealPath()
    return if (actualTarget == expected) {
      null
    } else {
      val realRoot = root.toRealPath()
      "$contentFile: supporting sidecar '$fileName' points to ${actualTarget.relativeTo(realRoot)} " +
        "instead of ${expected.relativeTo(realRoot)}"
    }
  }

  private fun validateReadme(readme: Path, skillNames: Set<String>, issues: MutableList<String>) {
    if (!readme.isRegularFile()) {
      issues += "README.md is missing"
      return
    }
    val catalogSkills = Files.readAllLines(readme)
      .mapNotNull { line -> readmeSkillRowPattern.find(line)?.groupValues?.get(1) }
      .toSet()
    val missing = skillNames - catalogSkills
    if (missing.isNotEmpty()) {
      issues += "README.md catalog is missing skills: ${missing.sorted()}"
    }
  }

  private fun validateSkillReferences(root: Path, skillNames: Set<String>, issues: MutableList<String>) {
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

  private fun isSkillReferenceScanTarget(relativePath: Path): Boolean {
    val parts = relativePath.map(Path::toString)
    if (NATIVE_AGENT_SOURCE_DIR in parts) {
      return false
    }
    return skillbill.nativeagent.NativeAgentProvider.entries.none { provider -> provider.directoryName in parts }
  }

  private fun validateSkillReferencesInFile(
    file: Path,
    root: Path,
    skillNames: Set<String>,
    issues: MutableList<String>,
  ) {
    val text = Files.readString(file)
    skillReferencePattern.findAll(text).forEach { match ->
      val referenced = match.value
      if (referenced !in skillNames && !isDocumentedExampleReference(file, root, referenced)) {
        issues += "${file.relativeTo(root)}: references unknown skill '$referenced'"
      }
    }
  }

  private fun validateSkillOverrides(
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
      val section = overrideSectionPattern.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
      if (section !in skillNames) {
        issues += "${overrideFile.fileName}:${index + 1}: override section references unknown skill '$section'"
      }
    }
  }

  private fun validateSupportingTargets(root: Path, skillNames: Set<String>, issues: MutableList<String>) {
    skillNames.flatMap { name -> requiredSupportingFilesForSkill(name, root) }.toSet().forEach { fileName ->
      val target = supportingFileTargets(root)[fileName]
      if (target == null || !Files.exists(target)) {
        issues += "supporting file '$fileName' target is missing"
      }
    }
  }

  private fun validateWorkflowContracts(root: Path, issues: MutableList<String>) {
    val checks = mapOf(
      "skills/bill-feature-task-prose/content.md" to listOf(
        "Step id: `assess`",
        "Step id: `implement`",
        "Step id: `pr_description`",
        "feature_implement_workflow_open",
        "feature_implement_workflow_update",
        "feature_implement_workflow_continue",
        "`assessment`",
        "`preplan_digest`",
        "`implementation_summary`",
        "`pr_result`",
      ),
      "skills/bill-feature-verify/content.md" to listOf(
        "Step id: `collect_inputs`",
        "Step id: `code_review`",
        "Step id: `verdict`",
        "feature_verify_workflow_open",
        "feature_verify_workflow_update",
        "feature_verify_workflow_get",
        "feature_verify_workflow_continue",
        "`input_context`",
        "`criteria_summary`",
        "`diff_summary`",
        "`review_result`",
        "`completeness_audit_result`",
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

  private fun discoverAllAddonFiles(root: Path): List<Path> {
    val containers = listOf(root.resolve("skills"), root.resolve("platform-packs"))
    return containers.filter(Path::isDirectory).flatMap { container ->
      Files.walk(container).use { stream ->
        stream
          .filter {
            it.isRegularFile() &&
              it.fileName.toString().endsWith(".md") &&
              "addons" in it.relativeTo(container).map(Path::toString)
          }
          .toList()
      }
    }.sorted()
  }

  private fun validateGovernedContentFile(contentFile: Path, issues: MutableList<String>) {
    if (!contentFile.isRegularFile()) {
      return
    }
    issues += validateAuthoredContent(contentFile, Files.readString(contentFile))
  }

  private fun validatePortableReviewWording(
    skillName: String,
    text: String,
    skillFile: Path,
    issues: MutableList<String>,
  ) {
    if (skillName !in portableReviewSkills) {
      return
    }
    nonPortableReviewPatterns.forEach { (pattern, message) ->
      if (pattern.containsMatchIn(text)) {
        issues += "$skillFile: $message"
      }
    }
  }

  private fun validateOrchestrationPlaybooks(root: Path, issues: MutableList<String>) {
    ORCHESTRATION_PLAYBOOKS.values.forEach { relativePath ->
      val file = root.resolve(relativePath)
      if (!file.isRegularFile()) {
        issues += "$relativePath is missing"
        return@forEach
      }
      val text = Files.readString(file)
      externalPlaybookReferencePatterns.forEach { (pattern, message) ->
        if (pattern.containsMatchIn(text)) {
          issues += "$relativePath: $message"
        }
      }
    }
  }

  private fun validateNoInlineTelemetryContractDrift(root: Path, issues: MutableList<String>) {
    val telemetryPlaybook = root.resolve("orchestration/telemetry-contract/PLAYBOOK.md")
    if (!telemetryPlaybook.isRegularFile()) {
      issues += "orchestration/telemetry-contract/PLAYBOOK.md is missing"
      return
    }
    val telemetryText = Files.readString(telemetryPlaybook)
    inlineTelemetryContractMarkers.forEach { marker ->
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
      inlineTelemetryContractMarkers.forEach { marker ->
        if (marker in text) {
          issues += "$contentFile: must not inline shared telemetry contract marker '$marker'"
        }
      }
    }
  }

  private fun validatePluginManifest(pluginPath: Path, issues: MutableList<String>) {
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

  private fun validatePointerTargetParityIssues(root: Path): List<String> {
    val packsRoot = root.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      return emptyList()
    }
    val packs = mutableListOf<skillbill.scaffold.model.PlatformManifest>()
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .forEach { packRoot ->
          try {
            packs += loadPlatformManifest(packRoot)
          } catch (_: ShellContentContractException) {
            // Surfaced by validatePlatformPacks above.
          }
        }
    }
    return validatePointerTargetParity(root, packs)
  }

  private fun isDocumentedExampleReference(file: Path, root: Path, referenced: String): Boolean {
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

  private fun validateNoOrchestrationPathsInSkillBodies(
    root: Path,
    skillFiles: Map<String, Path>,
    platformSkillFiles: Map<String, Path>,
    issues: MutableList<String>,
  ) {
    (skillFiles.values + platformSkillFiles.values).forEach { contentFile ->
      if (!contentFile.isRegularFile()) return@forEach
      val text = Files.readString(contentFile)
      orchestrationPathPattern.findAll(text).forEach { match ->
        issues += "${contentFile.relativeTo(root)}: must not reference bare orchestration path '${match.value}'"
      }
    }
  }
}
