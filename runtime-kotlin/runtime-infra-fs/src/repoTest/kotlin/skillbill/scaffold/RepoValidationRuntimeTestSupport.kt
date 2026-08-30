package skillbill.scaffold

import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.renderNativeAgentSource
import skillbill.scaffold.runtime.requiredSupportingFilesForSkill
import skillbill.scaffold.runtime.supportingFileTargets
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun completeTransitionalLicense(): String = Files.readString(repositoryRoot().resolve("LICENSE"))

internal fun writeSuccessorApproval(repoRoot: Path, license: String) {
  val sha256 = MessageDigest.getInstance("SHA-256")
    .digest(license.trimEnd().encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  Files.createDirectories(repoRoot.resolve("docs"))
  Files.writeString(
    repoRoot.resolve("docs/release-successor-license-approval.md"),
    """
      Status: Approved
      Approved License Identifier: LicenseRef-Skill-Bill-Use-1.0
      Approved LICENSE SHA-256: $sha256
      Approved by: Braian Gapur
      Approval location: https://example.test/approvals/successor-license
    """.trimIndent() + "\n",
  )
}

internal fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
  .first { Files.isRegularFile(it.resolve("LICENSE")) }

internal fun seedInternalSkill(repoRoot: Path, name: String, internalFor: String?) {
  val skillDir = repoRoot.resolve("skills/$name")
  Files.createDirectories(skillDir)
  val frontmatter = buildString {
    appendLine("---")
    appendLine("name: $name")
    appendLine("description: $name skill.")
    if (internalFor != null) {
      appendLine("internal-for: $internalFor")
    }
    appendLine("---")
  }
  Files.writeString(skillDir.resolve("content.md"), "$frontmatter\n# $name\n\nBody.\n")
}

internal fun seedPlatformReviewPack(repoRoot: Path, slug: String, body: String) {
  val skillName = "bill-$slug-code-review"
  val contentFile = repoRoot.resolve("platform-packs/$slug/code-review/$skillName/content.md")
  Files.createDirectories(contentFile.parent)
  Files.writeString(
    repoRoot.resolve("platform-packs/$slug/platform.yaml"),
    """
      |platform: $slug
      |contract_version: "1.7"
      |display_name: $slug
      |routing_signals:
      |  strong:
      |    - $slug
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: code-review/$skillName/content.md
      |  areas: {}
    """.trimMargin(),
  )
  Files.writeString(contentFile, body)
}

internal fun createRepoValidationSkillFixture(
  repoRoot: Path,
  skipSidecar: String? = null,
  overrideTargets: Map<String, Path> = emptyMap(),
  sidecarMode: SidecarMode = SidecarMode.SymbolicLink,
  writeSidecars: Boolean = false,
) {
  SkillClassFixtures.seedShippedSkillClasses(repoRoot)
  supportingFileTargets(repoRoot).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "contract\n")
  }
  val skillDir = repoRoot.resolve("skills/bill-code-review")
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance for the code-review baseline skill fixture.
    """.trimIndent(),
  )
  if (!writeSidecars) {
    return
  }
  val targets = supportingFileTargets(repoRoot)
  requiredSupportingFilesForSkill("bill-code-review", repoRoot).filterNot { it == skipSidecar }.forEach { fileName ->
    val sidecar = skillDir.resolve(fileName)
    val target = overrideTargets[fileName] ?: targets.getValue(fileName)
    val relativeTarget = sidecar.parent.relativize(target).toString()
    when (sidecarMode) {
      SidecarMode.SymbolicLink -> Files.createSymbolicLink(sidecar, Path.of(relativeTarget))
      SidecarMode.GitPlaceholder -> Files.writeString(sidecar, relativeTarget)
    }
  }
}

internal fun writeNativeAgentFixture(skillDir: Path, name: String) {
  val source = NativeAgentSource(name = name, description = "Review changed code.", body = "# Worker\n\nReview it.")
  val sourcePath = skillDir.resolve("native-agents/$name.md")
  Files.createDirectories(sourcePath.parent)
  Files.writeString(sourcePath, renderNativeAgentSource(source))
}

internal enum class SidecarMode {
  SymbolicLink,
  GitPlaceholder,
}
