package skillbill.infrastructure.fs.validation

import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemValidationGateRunnerTest {
  @Test
  fun `COLLECT_ALL unions compiler findings from module A with JUnit from compiling module B`() {
    val repo = Files.createTempDirectory("gate-collect-all")
    try {
      val script = writeGateScript(repo)
      val runner = FileSystemValidationGateRunner()
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
      val result = FileSystemValidationGateRunner().run(
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
  fun `COLLECT_ALL failed empty sources emit exactly one unparseable_gate_failure`() {
    val repo = Files.createTempDirectory("gate-unparseable")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(script, "#!/bin/sh\nexit 1\n")
      val result = FileSystemValidationGateRunner().run(
        request(
          repo,
          argv = listOf("sh", script.toString()),
          parseMode = ValidationGateFindingParseMode.COLLECT_ALL,
        ),
      )
      assertEquals(listOf("unparseable_gate_failure"), result.findings.map { it.ruleOrTestId })
      assertEquals("<validation-gate>", result.findings.single().module)
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
      val result = FileSystemValidationGateRunner().run(
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
      val result = FileSystemValidationGateRunner().run(
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
      val result = FileSystemValidationGateRunner().run(
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
  ): ValidationGateRunRequest = ValidationGateRunRequest(
    repoRoot = repo,
    argv = argv,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    declaration = ValidationGateDeclaration(
      fullGateCommand = listOf("sh", "gate.sh"),
      cacheBypassingFullGateCommand = listOf("sh", "gate.sh", "--rerun-tasks"),
      collectAllFullGateCommand = listOf("sh", "gate.sh", "--continue"),
      cacheBypassingCollectAllFullGateCommand = listOf("sh", "gate.sh", "--continue", "--rerun-tasks"),
      buildOnlyCommand = listOf("sh", "-c", "exit 0"),
      findings = ValidationGateFindingsLocator(
        format = ValidationGateFindingsFormat.JUNIT_XML,
        artifactGlobs = listOf("**/build/test-results/**/*.xml"),
        compilerDiagnostics = ValidationGateCompilerDiagnosticsLocator(
          ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
        ),
      ),
    ),
    terminalVerifying = false,
    findingParseMode = parseMode,
  )
}
