package skillbill.infrastructure.fs.validation

import skillbill.ports.time.JvmSystemClock
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemValidationGateRunnerTest {
  @Test
  fun `COLLECT_ALL unions compiler findings from module A with JUnit from compiling module B`() {
    val repo = Files.createTempDirectory("gate-collect-all")
    try {
      val script = writeGateScript(repo)
      val runner = FileSystemValidationGateRunner(JvmSystemClock)
      val failFast = runner.run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertTrue(failFast.findings.none { it.ruleOrTestId == "fails" })
      assertEquals("kotlin_compiler", failFast.findings.single().ruleOrTestId)

      val collectAll = runner.run(
        request(
          repo,
          argv = listOf("sh", script.toString(), "--continue"),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertEquals(2, collectAll.findings.size)
      val compiler = collectAll.findings.single { it.ruleOrTestId == "kotlin_compiler" }
      assertEquals("module-a", compiler.module)
      assertEquals("module-a/Foo.kt:3:1", compiler.location)
      assertEquals("Unresolved reference: missing", compiler.message)
      val junit = collectAll.findings.single { it.ruleOrTestId == "fails" }
      assertEquals("module.b", junit.module)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL compiler diagnostics do not emit unparseable_gate_failure`() {
    val repo = Files.createTempDirectory("gate-compiler-only")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        ROOT=$(pwd)
        printf '%s\n' "e: file://${'$'}ROOT/module-a/Foo.kt:3:1 Unresolved reference: missing"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertEquals(listOf("kotlin_compiler"), result.findings.map { it.ruleOrTestId })
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `terminal verifying with zero executed work and exit 0 is passed`() {
    val repo = Files.createTempDirectory("gate-zero-work-pass")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' '9 actionable tasks: 9 up-to-date'
        exit 0
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          GateParseOptions(
            parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
            terminalVerifying = true,
            withExecutedWorkSignal = true,
          ),
        ),
      )
      assertEquals(ValidationGateRunOutcome.PASSED, result.outcome)
      assertEquals(0, result.executedWorkUnits)
      assertEquals(emptyList(), result.findings)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL parses detekt XML reports into discrete findings`() {
    val repo = Files.createTempDirectory("gate-detekt-xml")
    try {
      val detektReport = repo.resolve("runtime-kotlin/runtime-application/build/reports/detekt/detekt.xml")
      Files.createDirectories(detektReport.parent)
      Files.writeString(
        detektReport,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <checkstyle version="4.3">
        <file name="runtime-application/src/main/kotlin/skillbill/example/Foo.kt">
        	<error line="12" column="1" severity="warning" message="Too many functions" source="detekt.TooManyFunctions" />
        </file>
        </checkstyle>
        """.trimIndent(),
      )
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' 'FAILURE: Build failed with an exception.'
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          GateParseOptions(
            parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
            artifactGlobs = listOf(
              "**/build/test-results/**/*.xml",
              "runtime-kotlin/**/build/reports/detekt/*.xml",
            ),
          ),
        ),
      )
      val finding = result.findings.single { it.ruleOrTestId == "TooManyFunctions" }
      assertEquals("runtime-application", finding.module)
      assertEquals("runtime-application/src/main/kotlin/skillbill/example/Foo.kt:12", finding.location)
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL parses detekt and spotless stdout lines into discrete findings`() {
    val repo = Files.createTempDirectory("gate-quality-stdout")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        ROOT=$(pwd)
        printf '%s\n' "${'$'}ROOT/runtime-kotlin/runtime-application/src/main/kotlin/Foo.kt:12:1: Too many functions [TooManyFunctions]"
        printf '%s\n' "${'$'}ROOT/runtime-kotlin/runtime-infra-sqlite/src/test/kotlin/Bar.kt:386:1: Line too long [MaxLineLength]"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertEquals(2, result.findings.size)
      assertEquals("TooManyFunctions", result.findings.single { it.ruleOrTestId == "TooManyFunctions" }.ruleOrTestId)
      assertEquals("MaxLineLength", result.findings.single { it.ruleOrTestId == "MaxLineLength" }.ruleOrTestId)
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL failed empty sources emit exactly one unparseable_gate_failure`() {
    val repo = Files.createTempDirectory("gate-unparseable")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        cat "${fixturePath("truly-unparseable.stdout")}"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single()
      assertEquals("unparseable_gate_failure", finding.ruleOrTestId)
      assertEquals("<validation-gate>", finding.module)
      assertTrue(finding.message.contains("Could not find method foo()"))
      assertTrue(finding.message.contains("Gate stdout (head+tail):"))
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL multi-failure Gradle stdout yields discrete projectHealth and architectureCheck findings`() {
    val repo = Files.createTempDirectory("gate-multi-failure")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        cat "${fixturePath("multi-failure-project-health-and-architecture-check.stdout")}"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertTrue(result.findings.size >= 2)
      val ruleIds = result.findings.map { it.ruleOrTestId }.toSet()
      assertTrue("incorrectConfiguration" in ruleIds)
      assertTrue("forbidden_project_dependency" in ruleIds)
      assertTrue(ruleIds.size >= 2)
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
      val projectHealth = result.findings.single { it.ruleOrTestId == "incorrectConfiguration" }
      assertEquals("harness-cursor", projectHealth.module)
      assertTrue(projectHealth.message.contains("api(project(\":composition\"))"))
      assertTrue(projectHealth.message.contains("was implementation"))
      val architecture = result.findings.single { it.ruleOrTestId == "forbidden_project_dependency" }
      assertEquals("architecture-tests", architecture.module)
      assertTrue(architecture.message.contains("Forbidden project dependency"))
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL projectHealth-only stdout yields discrete configuration mismatch finding`() {
    val repo = Files.createTempDirectory("gate-project-health-only")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        cat "${fixturePath("project-health-only.stdout")}"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertTrue(result.findings.isNotEmpty())
      val finding = result.findings.single { it.ruleOrTestId == "incorrectConfiguration" }
      assertEquals("harness-cursor", finding.module)
      assertTrue(finding.message.contains("api(project(\":composition\"))"))
      assertTrue(finding.message.contains("was implementation"))
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL parses spotless step failures into file-level findings`() {
    val repo = Files.createTempDirectory("gate-spotless-step")
    try {
      val kotlinDir = repo.resolve("runtime-kotlin/runtime-application/src/main/kotlin/Foo.kt")
      Files.createDirectories(kotlinDir.parent)
      Files.writeString(kotlinDir, "class Foo\n")
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' "> Task :runtime-application:spotlessKotlinCheck FAILED"
        printf '%s\n' "Step 'ktlint' found problem in 'runtime-kotlin/runtime-application/src/main/kotlin/Foo.kt':"
        printf '%s\n' "Error on line: 1, column: 1"
        printf '%s\n' "rule: standard:max-line-length"
        printf '%s\n' "Exceeded max line length (140)"
        printf '%s\n' "Execution failed for task ':runtime-application:spotlessKotlinCheck'."
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single { it.ruleOrTestId == "spotless" }
      assertEquals("runtime-application", finding.module)
      assertEquals(
        "runtime-kotlin/runtime-application/src/main/kotlin/Foo.kt:1:1",
        finding.location,
      )
      assertTrue(finding.message.contains("max-line-length"))
      assertTrue(result.findings.none { it.ruleOrTestId == "spotlessKotlinCheck" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL spotless task header enriches message when step detail is absent`() {
    val repo = Files.createTempDirectory("gate-spotless-header-enrich")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' 'FAILURE: Build failed with an exception.'
        printf '%s\n' '* What went wrong:'
        printf '%s\n' "Execution failed for task ':runtime-infra-fs:spotlessCheck'."
        printf '%s\n' "Violations detected in the following files:"
        printf '%s\n' "runtime-kotlin/runtime-infra-fs/src/main/kotlin/Bar.kt"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single()
      assertEquals("runtime-infra-fs", finding.module)
      assertEquals("spotlessCheck", finding.ruleOrTestId)
      assertTrue(finding.message.contains("Violations detected"))
      assertTrue(finding.message.contains("Bar.kt"))
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL uncovered task failure header yields structured finding`() {
    val repo = Files.createTempDirectory("gate-uncovered-task-header")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' 'FAILURE: Build failed with an exception.'
        printf '%s\n' '* What went wrong:'
        printf '%s\n' "Execution failed for task ':runtime-infra-fs:spotlessCheck'."
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single()
      assertEquals("runtime-infra-fs", finding.module)
      assertEquals("spotlessCheck", finding.ruleOrTestId)
      assertEquals("Execution failed for task ':runtime-infra-fs:spotlessCheck'.", finding.message)
      assertTrue(result.findings.none { it.ruleOrTestId == "unparseable_gate_failure" })
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `COLLECT_ALL compiler diagnostic suppresses duplicate compileKotlin task header finding`() {
    val repo = Files.createTempDirectory("gate-compiler-task-dedupe")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        ROOT=$(pwd)
        printf '%s\n' "e: file://${'$'}ROOT/module-a/Foo.kt:3:1 Unresolved reference: missing"
        printf '%s\n' "Execution failed for task ':module-a:compileKotlin'."
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertEquals(1, result.findings.size)
      assertEquals("kotlin_compiler", result.findings.single().ruleOrTestId)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `ARTIFACTS_ONLY ignores compiler stdout and does not emit unparseable`() {
    val repo = Files.createTempDirectory("gate-artifacts-only")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        ROOT=$(pwd)
        printf '%s\n' "e: file://${'$'}ROOT/module-a/Foo.kt:3:1 Unresolved reference: missing"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.ARTIFACTS_ONLY,
        ),
      )
      assertEquals(emptyList(), result.findings)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `compiler diagnostic identity is repo-relative and path-free`() {
    val repo = Files.createTempDirectory("gate-compiler-identity")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        ROOT=$(pwd)
        printf '%s\n' "e: file://${'$'}ROOT/module-a/Foo.kt:3:1 Unresolved reference: missing"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single()
      assertEquals("module-a/Foo.kt:3:1", finding.location)
      assertEquals("Unresolved reference: missing", finding.message)
      assertTrue("file://" !in finding.message)
      assertTrue(repo.toAbsolutePath().toString() !in finding.message)
      assertTrue(finding.location != null && repo.toAbsolutePath().toString() !in finding.location!!)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `compiler diagnostic identity is repo-relative when stdout uses a realpath of a symlink repo`() {
    val real = Files.createTempDirectory("gate-compiler-real")
    val parent = Files.createTempDirectory("gate-compiler-link-parent")
    val link = parent.resolve("repo")
    try {
      Files.createSymbolicLink(link, real)
      val realRoot = real.toRealPath()
      val script = real.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' "e: file://$realRoot/module-a/Foo.kt:3:1 Unresolved reference: missing"
        exit 1
        """.trimIndent(),
      )
      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          link,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      val finding = result.findings.single()
      assertEquals("module-a", finding.module)
      assertEquals("module-a/Foo.kt:3:1", finding.location)
      assertEquals("Unresolved reference: missing", finding.message)
      assertTrue(realRoot.toString() !in finding.location!!)
    } finally {
      real.toFile().deleteRecursively()
      parent.toFile().deleteRecursively()
    }
  }

  @Test
  fun `expandGlob skips git metadata and keeps matching build artifacts`() {
    val repo = Files.createTempDirectory("gate-expand-glob-git")
    try {
      val realArtifact = repo.resolve("module-b/build/test-results/test/TEST-B.xml")
      Files.createDirectories(realArtifact.parent)
      Files.writeString(realArtifact, "<testsuite/>")
      val gitArtifact = repo.resolve(".git/build/test-results/test/TEST-git.xml")
      Files.createDirectories(gitArtifact.parent)
      Files.writeString(gitArtifact, "<testsuite/>")
      Files.writeString(repo.resolve(".git/index.lock"), "lock")

      val matches = FileSystemValidationGateRunner.expandGlob(repo, "**/build/test-results/**/*.xml")

      assertEquals(listOf(realArtifact), matches)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a green gate run ignores failing JUnit reports left on disk by an earlier run`() {
    val repo = Files.createTempDirectory("gate-stale-artifacts")
    try {
      val stale = repo.resolve("module-b/build/test-results/test/TEST-Stale.xml")
      Files.createDirectories(stale.parent)
      Files.writeString(
        stale,
        """<?xml version="1.0"?><testsuite><testcase classname="module.b.StaleTest" name="fails">""" +
          """<failure message="assertion failed"/></testcase></testsuite>""",
      )
      Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)))
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        printf '%s\n' '172 actionable tasks: 172 up-to-date'
        exit 0
        """.trimIndent(),
      )

      val result = FileSystemValidationGateRunner(JvmSystemClock).run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )

      assertEquals(ValidationGateRunOutcome.PASSED, result.outcome)
      assertEquals(emptyList(), result.findings)
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  private fun fixturePath(name: String): String {
    val resource = javaClass.classLoader.getResource("validation-gate/$name")
      ?: error("Missing validation-gate fixture: $name")
    return Path.of(resource.toURI()).toString()
  }

  private fun writeGateScript(repo: Path): Path {
    val script = repo.resolve("gate.sh")
    Files.writeString(
      script,
      """
      #!/bin/sh
      ROOT=$(pwd)
      printf '%s\n' "e: file://${'$'}ROOT/module-a/Foo.kt:3:1 Unresolved reference: missing"
      for arg in "${'$'}@"; do
        if [ "${'$'}arg" = "--continue" ]; then
          mkdir -p "${'$'}ROOT/module-b/build/test-results/test"
          printf '%s\n' '<?xml version="1.0"?><testsuite><testcase classname="module.b.CompilingTest" name="fails"><failure message="assertion failed"/></testcase></testsuite>' > "${'$'}ROOT/module-b/build/test-results/test/TEST-B.xml"
        fi
      done
      exit 1
      """.trimIndent(),
    )
    return script
  }

  private fun request(
    repo: Path,
    argv: List<String>,
    parseMode: ValidationGateFindingParseMode,
  ): ValidationGateRunRequest = request(repo, argv, GateParseOptions(parseMode = parseMode))

  private fun request(repo: Path, argv: List<String>, options: GateParseOptions): ValidationGateRunRequest =
    ValidationGateRunRequest(
      repoRoot = repo,
      argv = argv,
      cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
      declaration = ValidationGateDeclaration(
        fullGateCommand = listOf("sh", "gate.sh"),
        cacheBypassingFullGateCommand = listOf("sh", "gate.sh", "--rerun-tasks"),
        collectAllFullGateCommand = listOf("sh", "gate.sh", "--continue"),
        cacheBypassingCollectAllFullGateCommand = listOf("sh", "gate.sh", "--continue", "--rerun-tasks"),
        findings = ValidationGateFindingsLocator(
          format = ValidationGateFindingsFormat.JUNIT_XML,
          artifactGlobs = options.artifactGlobs,
          compilerDiagnostics = ValidationGateCompilerDiagnosticsLocator(
            ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
          ),
          executedWork = if (options.withExecutedWorkSignal) {
            ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY)
          } else {
            null
          },
        ),
      ),
      terminalVerifying = options.terminalVerifying,
      findingParseMode = options.parseMode,
    )
}

private data class GateParseOptions(
  val parseMode: ValidationGateFindingParseMode,
  val terminalVerifying: Boolean = false,
  val withExecutedWorkSignal: Boolean = false,
  val artifactGlobs: List<String> = listOf("**/build/test-results/**/*.xml"),
)
