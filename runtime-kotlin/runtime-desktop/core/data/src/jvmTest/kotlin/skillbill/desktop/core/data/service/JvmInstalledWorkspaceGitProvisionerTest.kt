package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.ProvisionResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmInstalledWorkspaceGitProvisionerTest {
  private val cleanupRoots = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    cleanupRoots.forEach { root ->
      runCatching { deleteRecursively(root) }
    }
    cleanupRoots.clear()
  }

  // AC1: first open of a non-git directory provisions a new repo with the scoped .gitignore and
  // an initial commit containing skills/ and platform-packs/.
  @Test
  fun `provision on first open initializes repo writes gitignore and creates initial commit`() {
    val workspace = newWorkspace()
    Files.createDirectories(workspace.resolve("skills/my-skill"))
    Files.writeString(workspace.resolve("skills/my-skill/content.md"), "# content\n")
    Files.createDirectories(workspace.resolve("platform-packs/kotlin"))
    Files.writeString(workspace.resolve("platform-packs/kotlin/platform.yaml"), "slug: kotlin\n")

    val provisioner = JvmInstalledWorkspaceGitProvisioner()
    val result = provisioner.provision(workspace.toString())

    assertIs<ProvisionResult.Provisioned>(result)

    // Verify the repo is rooted at the workspace.
    val topLevel = gitTopLevel(workspace)
    assertEquals(workspace.toAbsolutePath().normalize().toString(), topLevel)

    // Verify .gitignore exists and contains the expected exclusions.
    val gitignore = workspace.resolve(".gitignore")
    assertTrue(Files.exists(gitignore), ".gitignore must be created")
    val gitignoreContent = Files.readString(gitignore)
    assertTrue(gitignoreContent.contains("runtime/"), ".gitignore must exclude runtime/")
    assertTrue(gitignoreContent.contains("installed-skills/"), ".gitignore must exclude installed-skills/")
    assertTrue(gitignoreContent.contains("native-agents/"), ".gitignore must exclude native-agents/")
    assertTrue(gitignoreContent.contains("orchestration/"), ".gitignore must exclude orchestration/")
    assertTrue(gitignoreContent.contains("review-metrics.db"), ".gitignore must exclude review-metrics.db")
    assertTrue(gitignoreContent.contains("config.json"), ".gitignore must exclude config.json")
    assertTrue(gitignoreContent.contains("install-selection.json"), ".gitignore must exclude install-selection.json")
    assertTrue(gitignoreContent.contains("baseline-manifest.json"), ".gitignore must exclude baseline-manifest.json")
    assertTrue(gitignoreContent.contains("desktop.properties"), ".gitignore must exclude desktop.properties")

    // Verify there is exactly one commit and it contains skills/ and platform-packs/ content.
    val commitCount = gitCommitCount(workspace)
    assertEquals(1, commitCount, "Must have exactly one initial commit; found $commitCount")
    val committedPaths = gitCommittedPaths(workspace)
    assertTrue(
      committedPaths.any { it.startsWith("skills/") },
      "Initial commit must include skills/ content; paths: $committedPaths",
    )
    assertTrue(
      committedPaths.any { it.startsWith("platform-packs/") },
      "Initial commit must include platform-packs/ content; paths: $committedPaths",
    )
  }

  // AC2: re-provision on an already-provisioned workspace returns AlreadyProvisioned without
  // creating additional commits.
  @Test
  fun `provision on already-provisioned workspace returns AlreadyProvisioned without new commits`() {
    val workspace = newWorkspace()
    Files.createDirectories(workspace.resolve("skills"))
    val provisioner = JvmInstalledWorkspaceGitProvisioner()

    val firstResult = provisioner.provision(workspace.toString())
    assertIs<ProvisionResult.Provisioned>(firstResult)
    val commitCountAfterFirst = gitCommitCount(workspace)

    val secondResult = provisioner.provision(workspace.toString())
    assertIs<ProvisionResult.AlreadyProvisioned>(secondResult)

    val commitCountAfterSecond = gitCommitCount(workspace)
    assertEquals(
      commitCountAfterFirst,
      commitCountAfterSecond,
      "Re-provision must not create additional commits",
    )
  }

  // AC3: a $HOME-rooted parent git repo does not suppress provisioning — the workspace still gets
  // its own repo with a different top-level.
  @Test
  fun `provision creates own repo even when workspace is inside a parent git worktree`() {
    val parentRepo = Files.createTempDirectory("skillbill-parent-").also { cleanupRoots.add(it) }
    runGit(parentRepo, "init", "-q")
    runGit(parentRepo, "config", "user.email", "test@example.com")
    runGit(parentRepo, "config", "user.name", "Test")
    Files.writeString(parentRepo.resolve("top.txt"), "top\n")
    runGit(parentRepo, "add", ".")
    runGit(parentRepo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "parent init")

    // The workspace is a subdirectory of the parent git repo.
    val workspace = Files.createDirectories(parentRepo.resolve(".skill-bill"))

    val provisioner = JvmInstalledWorkspaceGitProvisioner()
    val result = provisioner.provision(workspace.toString())

    assertIs<ProvisionResult.Provisioned>(result)

    // The workspace must be its own git top-level, not the parent.
    val topLevel = gitTopLevel(workspace)
    val normalizedWorkspace = workspace.toAbsolutePath().normalize().toString()
    val normalizedParent = parentRepo.toAbsolutePath().normalize().toString()
    assertEquals(normalizedWorkspace, topLevel, "Workspace must have its own git root, not the parent repo root")
    assertTrue(topLevel != normalizedParent, "Workspace git root must not equal the parent repo root")
  }

  // AC4: when the git binary is missing, provision returns GitUnavailable without crashing.
  @Test
  fun `provision returns GitUnavailable when git binary cannot be found`() {
    val workspace = newWorkspace()
    val provisioner = JvmInstalledWorkspaceGitProvisioner()
    // Replace the runnerFactory with one that throws IOException on every call.
    provisioner.runnerFactory = { _ -> throw IOException("git: No such file or directory") }

    val result = provisioner.provision(workspace.toString())

    assertIs<ProvisionResult.GitUnavailable>(result)
    assertTrue(
      result.errorMessage.isNotBlank(),
      "GitUnavailable must carry a non-blank explanatory message",
    )
  }

  // F-MAJOR-3: AC4 step-2 IOException path — git binary is available for rev-parse (which exits
  // non-zero since the dir is not a repo), but throws IOException on the second call (git init).
  // This verifies that IOException at any provisioning step after rev-parse returns GitUnavailable.
  @Test
  fun `provision returns GitUnavailable when git binary unavailable at init step`() {
    val workspace = newWorkspace()
    var callCount = 0
    val provisioner = JvmInstalledWorkspaceGitProvisioner()
    provisioner.runnerFactory = { pb ->
      callCount++
      if (callCount == 1) {
        // First call: rev-parse — let it run for real (exits non-zero since workspace is not a repo)
        pb.start()
      } else {
        // Second call: git init — simulate missing binary
        throw IOException("git: No such file or directory")
      }
    }

    val result = provisioner.provision(workspace.toString())

    assertIs<ProvisionResult.GitUnavailable>(result)
    assertTrue(
      result.errorMessage.isNotBlank(),
      "GitUnavailable must carry a non-blank explanatory message",
    )
    assertTrue(
      callCount >= 2,
      "runnerFactory must be called at least twice (rev-parse + init attempt); actual: $callCount",
    )
  }

  // F-MAJOR-4: provision on a non-existent workspace path must return a failure result (not crash).
  @Test
  fun `provision returns Failed for non-existent workspace path`() {
    val nonExistentPath = "/tmp/nonexistent-skillbill-path-does-not-exist-${System.nanoTime()}"
    val provisioner = JvmInstalledWorkspaceGitProvisioner()

    val result = provisioner.provision(nonExistentPath)

    // A non-existent directory is either Failed (git init cannot create a repo there) or
    // GitUnavailable (if git binary is missing in the test environment). Either is acceptable per
    // spec — the important invariant is that the result is NOT Provisioned.
    assertTrue(
      result !is ProvisionResult.Provisioned && result !is ProvisionResult.AlreadyProvisioned,
      "provision must not return Provisioned or AlreadyProvisioned for a non-existent path; got $result",
    )
  }

  // ---- helpers ----

  private fun newWorkspace(): Path {
    val dir = Files.createTempDirectory("skillbill-workspace-")
    cleanupRoots.add(dir)
    return dir
  }

  private fun gitTopLevel(root: Path): String {
    val process = ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--show-toplevel")
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git rev-parse --show-toplevel timed out")
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.exitValue() == 0) { "git rev-parse --show-toplevel failed: $output" }
    return Path.of(output).toAbsolutePath().normalize().toString()
  }

  private fun gitCommitCount(root: Path): Int {
    val process = ProcessBuilder("git", "-C", root.toString(), "rev-list", "--count", "HEAD")
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git rev-list --count HEAD timed out")
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.exitValue() != 0) return 0
    return output.toIntOrNull() ?: 0
  }

  private fun gitCommittedPaths(root: Path): List<String> {
    val process = ProcessBuilder("git", "-C", root.toString(), "show", "--name-only", "--format=", "HEAD")
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git show --name-only timed out")
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.exitValue() != 0) return emptyList()
    return output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  }

  private fun runGit(root: Path, vararg args: String) {
    val command = mutableListOf("git", "-C", root.toString())
    command.addAll(args)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git ${args.joinToString(" ")} timed out")
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path)
      .sorted(Comparator.reverseOrder())
      .forEach { Files.deleteIfExists(it) }
  }
}
