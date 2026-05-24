package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallPolicyOwnershipArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }
  private val approvedPolicyCallers = setOf(
    "runtime-infra-fs/src/main/kotlin/skillbill/install/InstallPlanBuilder.kt",
  )
  private val approvedValidationSeams = setOf(
    "runtime-infra-fs/src/main/kotlin/skillbill/install/InstallPlanBuilder.kt",
    "runtime-cli/src/main/kotlin/skillbill/cli/InstallCliPayloads.kt",
  )

  @Test
  fun `install policy package must not import filesystem or install implementation mechanics`() {
    val policyRoot = runtimeRoot.resolve("runtime-domain/src/main/kotlin/skillbill/install/policy")
    val policyFiles = kotlinFilesUnder(policyRoot)
    assertTrue(policyFiles.isNotEmpty(), "Install policy package must exist in runtime-domain.")

    val forbiddenImportPattern = installPolicyForbiddenImportPattern()
    val violations = policyFiles.flatMap { sourceFile ->
      sourceFile.readText().lineSequence()
        .mapIndexedNotNull { index, line ->
          val trimmed = line.trim()
          if (forbiddenImportPattern.matches(trimmed)) {
            "${runtimeRoot.relativize(sourceFile)}:${index + 1} imports ${trimmed.removePrefix("import ")}"
          } else {
            null
          }
        }
    }

    assertEquals(
      emptyList(),
      violations,
      "Install plan policy must stay pure: filesystem/process mechanics and infra-fs install implementation " +
        "imports belong in runtime-infra-fs.",
    )
  }

  @Test
  fun `install policy forbidden import regex catches known bad and passes known good`() {
    val forbiddenImportPattern = installPolicyForbiddenImportPattern()
    val mustBeDetectedAsForbidden = listOf(
      "import java.io.File",
      "import java.nio.file.Files",
      "import java.lang.ProcessBuilder",
      "import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts",
      "import skillbill.install.InstallOperations",
      "import skillbill.install.InstallPlanBuilder",
      "import skillbill.install.computeInstallContentHash",
    )
    val mustNotBeDetectedAsForbidden = listOf(
      "import java.nio.file.Path",
      "import skillbill.install.model.InstallPlan",
      "import skillbill.install.policy.InstallPlanPolicy",
      "import skillbill.contracts.install.InstallPlanSchemaValidator",
    )

    val falseNegatives = mustBeDetectedAsForbidden.filterNot(forbiddenImportPattern::matches)
    val falsePositives = mustNotBeDetectedAsForbidden.filter(forbiddenImportPattern::matches)

    assertEquals(emptyList(), falseNegatives, "Install-policy forbidden-import regex missed known-bad imports.")
    assertEquals(emptyList(), falsePositives, "Install-policy forbidden-import regex flagged known-good imports.")
  }

  @Test
  fun `adapter install seams do not own planner or validator policy`() {
    approvedValidationSeams.forEach { relativePath ->
      val text = runtimeRoot.resolve(relativePath).readText()
      assertTrue(
        text.contains("validateInstallPlanWireSnapshot"),
        "Approved install-plan validation seam $relativePath must call the shared wire-snapshot validator.",
      )
    }

    val adapterFiles = adapterKotlinFiles()
    val violations = adapterFiles.flatMap { sourceFile ->
      adapterPolicyOwnershipViolations(runtimeRoot.relativize(sourceFile).toSlashPath(), sourceFile.readText())
    }

    assertEquals(
      emptyList(),
      violations,
      "Adapter modules may invoke install-plan contract validation only at approved seams and must not own " +
        "install planner, validator, or domain-policy decisions.",
    )
  }

  @Test
  fun `adapter ownership scanner catches direct fqn alias and wildcard policy samples`() {
    val knownBadSamples = mapOf(
      "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyAlias.kt" to
        "import skillbill.install.policy.InstallPlanPolicy as Policy\nval draft = Policy.buildPlanDraft(input)",
      "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyWildcard.kt" to
        "import skillbill.install.policy.*\nval draft = InstallPlanPolicy.buildPlanDraft(input)",
      "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyFqn.kt" to
        "val draft = skillbill.install.policy.InstallPlanPolicy.buildPlanDraft(input)",
      "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorAlias.kt" to
        "import skillbill.contracts.install.InstallPlanSchemaValidator as Validator\nValidator.validate(payload)",
      "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorWildcard.kt" to
        "import skillbill.contracts.install.*\nInstallPlanSchemaValidator.validate(payload)",
      "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorFqn.kt" to
        "skillbill.contracts.install.InstallPlanSchemaValidator.validate(payload)",
      "runtime-mcp/src/main/kotlin/skillbill/mcp/BadValidationCall.kt" to
        "validateInstallPlanWireSnapshot(plan)",
      "runtime-mcp/src/main/kotlin/skillbill/mcp/BadValidationAlias.kt" to
        "import skillbill.install.model.validateInstallPlanWireSnapshot as validatePlan\nvalidatePlan(plan)",
    )

    val falseNegatives = knownBadSamples.mapNotNull { (relativePath, sourceText) ->
      relativePath.takeIf { adapterPolicyOwnershipViolations(relativePath, sourceText).isEmpty() }
    }

    assertEquals(emptyList(), falseNegatives, "Adapter ownership scanner missed known-bad samples.")
    assertEquals(
      emptyList(),
      adapterPolicyOwnershipViolations(
        "runtime-infra-fs/src/main/kotlin/skillbill/install/InstallPlanBuilder.kt",
        """
        |import skillbill.install.policy.InstallPlanPolicy
        |import skillbill.install.model.validateInstallPlanWireSnapshot
        |val draft = InstallPlanPolicy.buildPlanDraft(input)
        |validateInstallPlanWireSnapshot(plan)
        """.trimMargin(),
      ),
      "Approved builder seam must remain allowed to invoke policy and shared validation.",
    )
  }

  private fun installPolicyForbiddenImportPattern(): Regex = Regex(
    """^import\s+(""" +
      """java\.io\.File|java\.nio\.file\.Files|java\.lang\.ProcessBuilder|""" +
      """skillbill\.infrastructure(?:\..*)?|""" +
      """skillbill\.install\.(?!model\.|policy\.)[A-Za-z0-9_.*]+""" +
      """)$""",
  )

  private fun adapterPolicyOwnershipViolations(relativePath: String, sourceText: String): List<String> =
    sourceText.lineSequence().mapIndexedNotNull { index, line ->
      adapterPolicyOwnershipViolation(relativePath, index + 1, line.trim())
    }.toList()

  private fun adapterPolicyOwnershipViolation(relativePath: String, lineNumber: Int, trimmed: String): String? {
    val code = trimmed.takeUnless { it.startsWith("//") || it.startsWith("*") } ?: return null
    val disallowedPolicyReference = installPolicyReferencePattern().containsMatchIn(code) &&
      relativePath !in approvedPolicyCallers
    val disallowedSchemaValidatorReference = installSchemaValidatorReferencePattern().containsMatchIn(code)
    val disallowedValidationUse = installWireSnapshotValidationReferencePattern().containsMatchIn(code) &&
      relativePath !in approvedValidationSeams
    val disallowedPolicyDeclaration = installPolicyDeclarationPattern().containsMatchIn(code)
    return when {
      disallowedPolicyReference -> "$relativePath:$lineNumber references InstallPlanPolicy outside the builder seam"
      disallowedSchemaValidatorReference -> "$relativePath:$lineNumber references InstallPlanSchemaValidator directly"
      disallowedValidationUse -> "$relativePath:$lineNumber invokes install-plan validation outside approved seams"
      disallowedPolicyDeclaration -> "$relativePath:$lineNumber declares install planner/validator policy"
      else -> null
    }
  }

  private fun installPolicyReferencePattern(): Regex = Regex(
    """^import\s+skillbill\.install\.policy\.\*(?:\s+as\s+\w+)?$|""" +
      """\bskillbill\.install\.policy\.InstallPlanPolicy\b""",
  )

  private fun installSchemaValidatorReferencePattern(): Regex = Regex(
    """^import\s+skillbill\.contracts\.install\.\*(?:\s+as\s+\w+)?$|""" +
      """\bskillbill\.contracts\.install\.InstallPlanSchemaValidator\b""",
  )

  private fun installWireSnapshotValidationReferencePattern(): Regex = Regex(
    """^import\s+skillbill\.install\.model\.validateInstallPlanWireSnapshot(?:\s+as\s+\w+)?$|""" +
      """(?:^|[^\w.])(?:skillbill\.install\.model\.)?validateInstallPlanWireSnapshot\s*(?:\(|$)""",
  )

  private fun installPolicyDeclarationPattern(): Regex = Regex(
    """^(class|object|interface)\s+.*""" +
      """(InstallPlanPolicy|InstallPlanner|InstallPlanValidator|InstallPlanSchemaValidator)\b""",
  )

  private fun kotlinFilesUnder(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.extension == "kt" }
        .toList()
    }
  }

  private fun adapterKotlinFiles(): List<Path> = listOf(
    runtimeRoot.resolve("runtime-cli/src/main/kotlin"),
    runtimeRoot.resolve("runtime-mcp/src/main/kotlin"),
    runtimeRoot.resolve("runtime-infra-fs/src/main/kotlin"),
    runtimeRoot.resolve("runtime-infra-http/src/main/kotlin"),
    runtimeRoot.resolve("runtime-infra-sqlite/src/main/kotlin"),
    runtimeRoot.resolve("runtime-desktop/core/data/src/commonMain/kotlin"),
    runtimeRoot.resolve("runtime-desktop/core/data/src/jvmMain/kotlin"),
  ).flatMap(::kotlinFilesUnder)

  private fun Path.toSlashPath(): String = toString().replace('\\', '/')
}
