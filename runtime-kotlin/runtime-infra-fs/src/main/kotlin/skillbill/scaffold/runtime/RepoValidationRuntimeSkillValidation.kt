package skillbill.scaffold.runtime

import skillbill.error.InvalidSkillMdShapeError
import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.scaffold.authoring.InternalSkillDeclaration
import skillbill.scaffold.authoring.internalSkillClassificationViolations
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.validation.validateAuthoredContent
import skillbill.scaffold.validation.validateSkillMdShape
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

internal val repoValidationNonPortableReviewPatterns = listOf(
  Regex("""`task`""") to "must not hardcode the `task` tool in shared review orchestration",
  Regex("""\bspawn_agent\b""") to "must not hardcode the `spawn_agent` tool in shared review orchestration",
  Regex("""\bsub-agent(s)?\b""") to "must not describe review delegation as sub-agents",
  Regex("""\bAgent to spawn\b""") to "must use portable specialist-review wording",
  Regex("""\bAgents spawned\b""") to "must use portable specialist-review summary wording",
)
internal const val REPO_VALIDATION_GOVERNED_ADDON_PATH_PART_COUNT = 4
internal val repoValidationAddonSlugPattern = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")
internal val repoValidationSidecarReferencePattern = Regex("`([a-z0-9][a-z0-9-]*)\\.md`")

  @Suppress("LongParameterList")
internal fun validateInstallableSkill(
    skillName: String,
    contentFile: Path,
    root: Path,
    issues: MutableList<String>,
    validateSourceSidecars: Boolean,
    portableReviewSkills: Set<String>,
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
    validatePortableReviewWording(skillName, text, contentFile, issues, portableReviewSkills)
    validateGovernedContentFile(contentFile, issues)
  }

internal fun validateSkillSourceShape(contentFiles: Collection<Path>, root: Path, issues: MutableList<String>) {
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

internal fun parseFrontmatter(text: String): Map<String, String> {
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

internal fun validateAddonFile(addonFile: Path, root: Path, issues: MutableList<String>) {
    val relative = addonFile.relativeTo(root)
    val parts = relative.map(Path::toString)
    if (
      parts.size != REPO_VALIDATION_GOVERNED_ADDON_PATH_PART_COUNT ||
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
    if (!repoValidationAddonSlugPattern.matches(slug)) {
      issues += "$relative: governed add-on slug '$slug' must be lowercase kebab-case"
    }
  }

internal fun validateSupportingSidecar(
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

internal fun isAuthoredSourceSidecar(contentFile: Path, fileName: String, expectedTarget: Path?): Boolean {
    if (expectedTarget == null) {
      return false
    }
    val sidecar = contentFile.parent.resolve(fileName).normalize().toAbsolutePath()
    val expected = expectedTarget.normalize().toAbsolutePath()
    return sidecar == expected
  }

internal fun isGitSymlinkPlaceholder(sidecar: Path, expectedTarget: Path): Boolean {
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

internal fun supportingSymlinkTargetIssue(
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
internal fun validateGovernedContentFile(contentFile: Path, issues: MutableList<String>) {
    if (!contentFile.isRegularFile()) {
      return
    }
    issues += validateAuthoredContent(contentFile, Files.readString(contentFile))
  }
internal fun validateInternalSidecarCollisions(skills: Map<String, Path>, issues: MutableList<String>) {
    val internalByParent = skills.entries
      .mapNotNull { (skillName, contentFile) ->
        val declaredParent = parseInternalForFrontmatter(contentFile)?.takeIf(String::isNotBlank)
        if (declaredParent != null && declaredParent != skillName && declaredParent in skills) {
          declaredParent to skillName
        } else {
          null
        }
      }
      .groupBy({ it.first }, { it.second })
    internalByParent.forEach { (parentName, children) ->
      val parentFile = skills[parentName] ?: return@forEach
      val parentDir = parentFile.parent
      children.sorted().forEach { childName ->
        val sidecar = parentDir.resolve("$childName.md")
        if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
          issues += "$parentFile: internal sidecar '$childName.md' for parent '$parentName' " +
            "collides with an authored file at '$sidecar'; remove the authored file or rename the " +
            "internal skill."
        }
      }
    }
  }

internal fun validatePortableReviewWording(
    skillName: String,
    text: String,
    skillFile: Path,
    issues: MutableList<String>,
    portableReviewSkills: Set<String>,
  ) {
    if (skillName !in portableReviewSkills) {
      return
    }
    repoValidationNonPortableReviewPatterns.forEach { (pattern, message) ->
      if (pattern.containsMatchIn(text)) {
        issues += "$skillFile: $message"
      }
    }
  }
internal fun validateInternalSkillClassification(
    baseSkillFiles: Map<String, Path>,
    platformSkillFiles: Map<String, Path>,
    issues: MutableList<String>,
  ) {
    val declarations = baseSkillFiles.entries.map { (skillName, contentFile) ->
      InternalSkillDeclaration(
        skillName = skillName,
        contentFile = contentFile,
        declaredParent = parseInternalForFrontmatter(contentFile),
        isBaseSkill = true,
      )
    } + platformSkillFiles.entries.map { (skillName, contentFile) ->
      InternalSkillDeclaration(
        skillName = skillName,
        contentFile = contentFile,
        declaredParent = parseInternalForFrontmatter(contentFile),
        isBaseSkill = false,
      )
    }
    issues += internalSkillClassificationViolations(declarations)
  }

internal fun validateInternalSidecarReferences(skillFiles: Map<String, Path>, issues: MutableList<String>) {
    val declaredParents = skillFiles.mapValues { (_, contentFile) ->
      parseInternalForFrontmatter(contentFile)?.takeIf(String::isNotBlank)
    }
    skillFiles.forEach { (skillName, contentFile) ->
      val effectiveParent = declaredParents[skillName] ?: skillName
      repoValidationSidecarReferencePattern.findAll(Files.readString(contentFile)).forEach { match ->
        val referenced = match.groupValues[1]
        if (referenced !in skillFiles || referenced == skillName) {
          return@forEach
        }
        val referencedParent = declaredParents[referenced]
        if (referencedParent == null) {
          issues += "$contentFile: references sidecar '$referenced.md' but '$referenced' is a listed " +
            "skill and renders no sidecar; invoke it via the Skill tool or classify it internal."
        } else if (referencedParent != effectiveParent) {
          issues += "$contentFile: references sidecar '$referenced.md' but '$referenced' is internal to " +
            "'$referencedParent', not co-located with '$skillName' (effective parent '$effectiveParent'); " +
            "the sidecar will not exist in this skill's installed directory."
        }
      }
    }
  }
