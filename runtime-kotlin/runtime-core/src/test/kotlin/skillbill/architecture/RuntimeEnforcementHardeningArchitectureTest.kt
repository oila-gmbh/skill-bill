package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.3 subtask 5 (enforcement-hardening-and-final-lock): source-text
 * inline-FQN bans and the schema/coherence validator-import ban. Split out of
 * [ImplementationOwnershipArchitectureTest] to keep both suites under the
 * detekt `LargeClass` budget without a suppression.
 */
class RuntimeEnforcementHardeningArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `application domain and ports do not embed inline fully-qualified adapter or infrastructure references`() {
    // SKILL-52.3 subtask 5 (AC1): the sibling import-only guard
    // `application domain and ports do not import adapters infrastructure or
    // composition roots` matches parsed `import` statements only. A
    // fully-qualified inline reference with NO import — e.g.
    // `skillbill.infrastructure.fs.Foo()` written out at the call site — is
    // invisible to that scan. This source-text scan catches inline FQN
    // references to the same forbidden prefixes so the leak cannot recur
    // through an unimported call site. The fixture-driven positive control
    // below proves the scanner fires.
    val violations = SOURCE_TEXT_LAYER_RULES.flatMap { (sourceRoot, forbiddenPrefixes) ->
      val sourceFiles = kotlinFilesUnder(runtimeRoot.resolve(sourceRoot))
      assertTrue(
        sourceFiles.isNotEmpty(),
        "Guarded source root '$sourceRoot' must resolve to at least one .kt file; a renamed or absent " +
          "root would silently make this AC1 inline-FQN guard vacuous (kotlinFilesUnder returns empty).",
      )
      sourceFiles.flatMap { sourceFile ->
        bannedInlineReferences(sourceFile.readText(), forbiddenPrefixes).map { reference ->
          "${runtimeRoot.relativize(sourceFile)} contains inline reference $reference"
        }
      }
    }.sorted()

    assertEquals(
      emptyList(),
      violations,
      "Application, domain, and port source must not embed inline fully-qualified references to adapters, " +
        "infrastructure, or DI composition roots — even without a matching import statement.",
    )
  }

  @Test
  fun `inline fully-qualified reference scanner fires on synthetic fixture`() {
    // SKILL-52.3 subtask 5 (AC1) positive control: a synthetic source string
    // carrying a bare `skillbill.infrastructure.fs.Foo()` inline reference with
    // NO import statement MUST be reported by the scanner. A regression that
    // weakened the scan (e.g. only matching `import ...` lines) would silently
    // disable the inline-FQN guard above.
    val fixtureWithInlineReference =
      """
      package skillbill.application

      class Leaky {
        fun build(): Any = skillbill.infrastructure.fs.Foo()
      }
      """.trimIndent()
    assertEquals(
      listOf("skillbill.infrastructure"),
      bannedInlineReferences(
        fixtureWithInlineReference,
        listOf("skillbill.cli", "skillbill.infrastructure", "skillbill.db"),
      ),
      "Inline-FQN scanner must report a bare `skillbill.infrastructure.fs.Foo()` reference with no import.",
    )

    val cleanFixture =
      """
      package skillbill.application

      import skillbill.application.model.Foo

      /**
       * Doc: see skillbill.infrastructure.fs.Foo for the adapter wiring.
       * skillbill.db.Bar is the legacy path.
       */
      class Clean {
        // trailing comment skillbill.cli.Baz reference must be ignored
        fun build(): Foo = Foo() // inline tail skillbill.infrastructure.fs.Qux
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      bannedInlineReferences(cleanFixture, listOf("skillbill.cli", "skillbill.infrastructure", "skillbill.db")),
      "Inline-FQN scanner must not flag forbidden FQNs that appear only inside KDoc, block comments, " +
        "or `//` line-comment tails.",
    )
  }

  @Test
  fun `pure layers must not import concrete schema or coherence validators`() {
    // SKILL-52.3 subtask 5 (AC3): the three relocated schema validators + the
    // coherence validator live in `runtime-infra-fs` and are reached only
    // through the domain-owned ports `InstallPlanWireValidator`,
    // `DecompositionManifestValidator`, and `WorkflowSnapshotValidator`. Pure
    // layers must never import a concrete `*SchemaValidator` /
    // `*CoherenceValidator`. The existing RuntimeArchitectureTest guard covers
    // `runtime-domain/.../skillbill/install/` + `.../skillbill/workflow/`; this
    // test additionally locks `runtime-application` main source and routes the
    // predicate through the shared `isSchemaOrCoherenceValidatorImport` helper
    // (self-tested in `RuntimeImplementationImportRulesTest`) so the install
    // leak that motivated SKILL-52.3 cannot recur from any of the three roots.
    val guardedSourceRoots = listOf(
      "runtime-domain/src/main/kotlin/skillbill/install",
      "runtime-domain/src/main/kotlin/skillbill/workflow",
      "runtime-application/src/main/kotlin",
    )
    val scannedFiles = guardedSourceRoots
      .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
      .flatMap(::kotlinFilesUnder)
    assertTrue(
      scannedFiles.size >= guardedSourceRoots.size,
      "Each guarded source root must resolve to at least one .kt file; a renamed or absent root would " +
        "silently make this AC3 guard vacuous. Guarded roots: $guardedSourceRoots",
    )
    val violations = scannedFiles
      .flatMap { sourceFile ->
        importedNames(sourceFile.readText())
          .filter(::isSchemaOrCoherenceValidatorImport)
          .map { importedName -> "${runtimeRoot.relativize(sourceFile)} imports $importedName" }
          .toList()
      }
      .sorted()

    assertEquals(
      emptyList(),
      violations,
      "runtime-domain install/workflow source and runtime-application main source must reach schema validation " +
        "only through the domain-owned validator ports, never by importing a concrete " +
        "*SchemaValidator/*CoherenceValidator.",
    )
  }

  @Test
  fun `validator-import extraction strips aliases before applying the ban predicate`() {
    // SKILL-52.3 subtask 5 (AC3) regression control: aliased imports are
    // idiomatic in this repo (e.g. ReviewContractMappers.kt), so the AC3 guard's
    // import extraction MUST strip an ` as <alias>` suffix before feeding
    // `isSchemaOrCoherenceValidatorImport`. A naive `removePrefix("import ")`
    // would yield `InstallPlanSchemaValidator as IPV` whose `substringAfterLast('.')`
    // no longer `endsWith("SchemaValidator")`, silently evading the ban. Drive an
    // aliased line through the SAME extraction+predicate path the guard uses.
    val aliasedImportSource =
      """
      package skillbill.application

      import skillbill.contracts.install.InstallPlanSchemaValidator as IPV
      import skillbill.contracts.workflow.DecompositionManifestCoherenceValidator as DMCV

      class Leaky
      """.trimIndent()
    val flagged = importedNames(aliasedImportSource).filter(::isSchemaOrCoherenceValidatorImport)
    assertEquals(
      listOf(
        "skillbill.contracts.install.InstallPlanSchemaValidator",
        "skillbill.contracts.workflow.DecompositionManifestCoherenceValidator",
      ),
      flagged,
      "AC3 import extraction must strip ` as <alias>` so an aliased concrete validator import is still caught.",
    )
  }

  /**
   * SKILL-52.3 subtask 5 (AC1): source-text scan for inline fully-qualified
   * references to forbidden prefixes. Mirrors
   * `RuntimeArchitectureTest.assertNoBannedSourceReferences` /
   * `containsBannedReference` (word-boundary-anchored substring match) but
   * deliberately SKIPS `import` lines so it complements — rather than
   * duplicates — the import-parsing guard. Each forbidden prefix is matched
   * as a dotted package token (`prefix.`) so `skillbill.infrastructure.fs.Foo`
   * is caught while an unrelated identifier merely containing the text is not.
   * Conservatively ignores `import`/`package` statements and comment text:
   * line-comment tails are stripped and whole-line block-comment / KDoc
   * continuation lines (trimmed line begins with an asterisk, a slash-star
   * block-comment opener, or a double-slash) are skipped so a documented FQN
   * in a comment cannot false-positive the build. Full string literal parsing
   * is intentionally NOT attempted — stripping comment tails is sufficient
   * for this guard.
   */
  private fun bannedInlineReferences(source: String, forbiddenPrefixes: List<String>): List<String> =
    source.lineSequence()
      .filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("import ") ||
          trimmed.startsWith("package ") ||
          trimmed.startsWith("//") ||
          trimmed.startsWith("*") ||
          trimmed.startsWith("/*")
      }
      .map { line -> line.substringBefore("//") }
      .flatMap { line ->
        forbiddenPrefixes.filter { prefix -> Regex("""\b${Regex.escape(prefix)}\.""").containsMatchIn(line) }
      }
      .distinct()
      .toList()

  /**
   * SKILL-52.3 subtask 5 (AC3): parse imported FQNs from source text, mirroring
   * `RuntimeArchitectureTest.importPattern` + `substringBefore(" as ")` so an
   * aliased import (`import a.b.C as D`) yields the bare FQN `a.b.C` and trailing
   * whitespace is dropped. Aligning on the shared parse keeps the AC3 guard from
   * being evaded by idiomatic aliased imports.
   */
  private fun importedNames(source: String): List<String> = IMPORT_PATTERN.findAll(source)
    .map { match -> match.groupValues[1].substringBefore(" as ").trim() }
    .toList()

  private fun kotlinFilesUnder(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.extension == "kt" }
        .toList()
    }
  }

  private companion object {
    /**
     * SKILL-52.3 subtask 5 (AC3): import-line pattern matching
     * `RuntimeArchitectureTest.importPattern`. The capture stops at the first
     * non-FQN char, so the optional ` as <alias>` suffix is excluded by the
     * regex; the call site still applies `substringBefore(" as ")` defensively.
     */
    val IMPORT_PATTERN: Regex = Regex("""^import\s+([A-Za-z0-9_.*]+)""", RegexOption.MULTILINE)

    /**
     * SKILL-52.3 subtask 5 (AC1): forbidden inline-FQN prefixes per source
     * root for the source-text scan. Scoped to the adapter / concrete
     * infrastructure / DI composition-root prefixes that may never be reached
     * from application/domain/port code by ANY path — import OR inline FQN.
     * (Cross-layer prefixes such as `skillbill.application` or `skillbill.ports`
     * are intentionally excluded here: they are guarded by the import-only
     * rule and appear legitimately as inline type names within their own
     * layer, so a blanket source-text ban would false-positive.)
     */
    val SOURCE_TEXT_LAYER_RULES: Map<String, List<String>> = mapOf(
      "runtime-application/src/main/kotlin" to listOf(
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
      ),
      "runtime-domain/src/main/kotlin" to listOf(
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
      ),
      "runtime-ports/src/main/kotlin" to listOf(
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
      ),
    )
  }
}
