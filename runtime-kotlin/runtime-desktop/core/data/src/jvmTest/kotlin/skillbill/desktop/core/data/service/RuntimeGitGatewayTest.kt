package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.relativeTo
import kotlin.streams.toList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeGitGatewayTest {
  private val cleanupRoots = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    cleanupRoots.forEach { root ->
      runCatching { deleteRecursively(root) }
    }
    cleanupRoots.clear()
  }

  @Test
  fun `snapshot groups staged unstaged untracked and generated files`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)

    // Modify an existing file (unstaged), stage it (staged), add a new untracked file (untracked).
    Files.writeString(repo.resolve("skills/bill-alpha/content.md"), "# updated content\n")
    Files.writeString(repo.resolve("untracked.txt"), "new\n")
    Files.writeString(repo.resolve("staged.md"), "staged\n")
    runGit(repo, "add", "staged.md")

    val snapshot = service.snapshotFor(session)

    val grouped = snapshot.files.groupBy { it.group }
    assertTrue(grouped[ChangedFileGroup.STAGED].orEmpty().any { it.path == "staged.md" })
    assertTrue(grouped[ChangedFileGroup.UNSTAGED].orEmpty().any { it.path == "skills/bill-alpha/content.md" })
    assertTrue(grouped[ChangedFileGroup.UNTRACKED].orEmpty().any { it.path == "untracked.txt" })
    // SKILL.md is a generated artifact per discoverGeneratedArtifactFiles when content.md exists.
    // Touching it as an untracked file proves the gateway re-classifies via the shared discovery.
    Files.writeString(repo.resolve("skills/bill-alpha/SKILL.md"), "regenerated\n")
    val second = service.snapshotFor(session)
    val generatedFile = second.files.singleOrNull { it.path == "skills/bill-alpha/SKILL.md" }
    assertNotNull(generatedFile)
    assertEquals(ChangedFileGroup.GENERATED, generatedFile.group)
    assertTrue(generatedFile.isGenerated)
  }

  @Test
  fun `diffFor returns staged vs unstaged content`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    Files.writeString(repo.resolve("skills/bill-alpha/content.md"), "# unstaged change\n")
    Files.writeString(repo.resolve("staged.md"), "staged-add\n")
    runGit(repo, "add", "staged.md")

    val unstaged = service.diffFor(session, "skills/bill-alpha/content.md", staged = false)
    val staged = service.diffFor(session, "staged.md", staged = true)

    assertTrue(unstaged.contains("# unstaged change"), "unstaged diff: $unstaged")
    assertTrue(staged.contains("staged-add"), "staged diff: $staged")
    // diffFor staged=false on a non-existent path returns blank, not an exception (AC11).
    val empty = service.diffFor(session, "no-such-file.md", staged = false)
    assertEquals("", empty)
  }

  @Test
  fun `recentCommits orders newest first and narrows by pathFilter`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    // Add two more commits, one touching a separate file.
    Files.writeString(repo.resolve("skills/bill-alpha/content.md"), "v2\n")
    runGit(repo, "add", ".")
    runGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "alpha v2")
    Files.writeString(repo.resolve("other.txt"), "other\n")
    runGit(repo, "add", "other.txt")
    runGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "add other")

    val all = service.recentCommits(session, limit = 5)
    val filtered = service.recentCommits(session, limit = 5, pathFilter = "other.txt")

    assertEquals(3, all.size)
    assertEquals("add other", all[0].subject)
    assertEquals("alpha v2", all[1].subject)
    assertTrue(all[0].fullHash.isNotBlank())
    assertEquals(7, all[0].shortHash.length.coerceAtLeast(0).let { if (it in 7..40) 7 else it })
    // path filter narrows to commits that touched other.txt only.
    assertEquals(1, filtered.size)
    assertEquals("add other", filtered.single().subject)
  }

  @Test
  fun `stage and unstage transitions move files between groups`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    Files.writeString(repo.resolve("toggle.md"), "v1\n")

    val afterStage = service.stage(session, listOf("toggle.md"))
    val afterUnstage = service.unstage(session, listOf("toggle.md"))

    assertEquals(
      ChangedFileGroup.STAGED,
      afterStage.files.single { it.path == "toggle.md" }.group,
    )
    // After unstage the file should appear as untracked (it was never tracked before).
    assertEquals(
      ChangedFileGroup.UNTRACKED,
      afterUnstage.files.single { it.path == "toggle.md" }.group,
    )
  }

  @Test
  fun `errors on non-git directories surface in snapshot without throwing`() {
    val nonRepo = Files.createTempDirectory("skillbill-non-repo")
    cleanupRoots.add(nonRepo)
    Files.createDirectories(nonRepo.resolve("skills/bill-alpha"))
    Files.writeString(nonRepo.resolve("skills/bill-alpha/content.md"), "x\n")
    val service = RuntimeGitGateway()
    val session = loadedSession(nonRepo)

    val snapshot = service.snapshotFor(session)

    // AC11: snapshot must surface error without throwing or mutating other slices. git status
    // outside of a repo returns a non-zero exit and stderr; we surface that as errorMessage.
    assertNotNull(snapshot.errorMessage, "expected error message but got null")
    assertTrue(snapshot.files.isEmpty())
  }

  @Test
  fun `read only operations do not mutate working tree`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    val before = repoFileSnapshot(repo)

    // AC8 invariant: read-only ops (snapshot, diff, recentCommits, status) must not change any file.
    service.snapshotFor(session)
    service.diffFor(session, "skills/bill-alpha/content.md", staged = false)
    service.recentCommits(session, limit = 10)
    service.statusFor(session)

    val after = repoFileSnapshot(repo)
    assertEquals(before, after)
  }

  @Test
  fun `rename porcelain produces one staged entry for destination path (F-T04)`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)

    // Move an existing committed file with `git mv` and stage the rename.
    runGit(repo, "mv", "skills/bill-alpha/content.md", "skills/bill-alpha/renamed.md")

    val snapshot = service.snapshotFor(session)

    val matches = snapshot.files.filter {
      it.path == "skills/bill-alpha/renamed.md" || it.path == "skills/bill-alpha/content.md"
    }
    // F-T04: exactly one entry for the destination path appears in the STAGED group, with no
    // phantom entry consuming a slot for the source path.
    assertEquals(1, matches.size, "expected single staged rename entry, got: ${snapshot.files.map { it.path }}")
    val renameEntry = matches.single()
    assertEquals("skills/bill-alpha/renamed.md", renameEntry.path)
    assertEquals(ChangedFileGroup.STAGED, renameEntry.group)
  }

  @Test
  fun `commit subject round-trips control chars and path with space (F-T05)`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)

    // Create a file at a path containing a space and commit it with a subject that contains tab,
    // newline, and pipe characters.
    val spacePath = "with space.md"
    Files.writeString(repo.resolve(spacePath), "edge\n")
    runGit(repo, "add", spacePath)
    val gnarlySubject = "edge\twith|chars\nand newline"
    runGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", gnarlySubject)

    val commits = service.recentCommits(session, limit = 5)
    val edgeCommit = commits.firstOrNull { it.changedPaths.any { p -> p == spacePath } }

    assertNotNull(
      edgeCommit,
      "expected commit touching '$spacePath'; got: ${commits.map { it.subject to it.changedPaths }}",
    )
    // F-T05: the space-containing path round-trips verbatim through porcelain parsing.
    assertTrue(edgeCommit.changedPaths.contains(spacePath))
    // F-T05: the subject preserves tabs and pipes. Git collapses a literal newline in -m into a
    // space at commit time (single-line subjects), so we assert the embedded tab + pipe survive,
    // and the original word "newline" is still present after collapse.
    assertTrue(edgeCommit.subject.contains("\t"), "subject lost tab: '${edgeCommit.subject}'")
    assertTrue(edgeCommit.subject.contains("|"), "subject lost pipe: '${edgeCommit.subject}'")
    assertTrue(edgeCommit.subject.contains("newline"), "subject lost trailing word: '${edgeCommit.subject}'")
  }

  @Test
  fun `commit creates a commit from staged changes and surfaces success`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    Files.writeString(repo.resolve("published.md"), "ready\n")
    runGit(repo, "add", "published.md")

    val result = service.commit(session, "publish changes")
    val snapshot = service.snapshotFor(session)
    val commits = service.recentCommits(session, limit = 2)

    assertTrue(result.success, "commit failed: ${result.errorMessage}")
    assertTrue(snapshot.files.none { it.path == "published.md" })
    assertEquals("publish changes", commits.first().subject)
  }

  @Test
  fun `publishingStatus shows push target canonical risk and compare URL for fork topology`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "git@github.com:contributor/skill-bill.git")
    runGit(repo, "remote", "add", "upstream", "https://github.com/canonical/skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(false, status.pushTarget?.isLikelyCanonical)
    assertEquals("https://github.com/canonical/skill-bill/compare/main...contributor:$branch", status.compareUrl)
  }

  @Test
  fun `publishingStatus targets current branch even when upstream tracks a different branch`() {
    val repo = newRepoWithCommit()
    val origin = newBareRemote()
    val upstream = newBareRemote()
    val baseBranch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", origin.toUri().toString())
    runGit(repo, "remote", "add", "upstream", upstream.toUri().toString())
    runGit(repo, "push", "origin", baseBranch)
    runGit(repo, "push", "-u", "upstream", baseBranch)
    runGit(repo, "checkout", "-b", "feature", "upstream/$baseBranch")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/feature", status.pushTarget?.displayName)
    assertEquals("feature", status.pushTarget?.branchName)

    Files.writeString(repo.resolve("feature.md"), "feature\n")
    runGit(repo, "add", "feature.md")
    assertTrue(service.commit(loadedSession(repo), "feature work").success)

    val pushResult = service.push(loadedSession(repo), status.pushTarget!!)

    assertTrue(pushResult.success, "push failed: ${pushResult.errorMessage}")
    assertEquals(0, gitExitCode(origin, "show-ref", "--verify", "--quiet", "refs/heads/feature"))
    assertEquals(1, gitExitCode(upstream, "show-ref", "--verify", "--quiet", "refs/heads/feature"))
  }

  @Test
  fun `publishingStatus uses pushurl when classifying canonical target risk`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "git@github.com:contributor/skill-bill.git")
    runGit(repo, "remote", "set-url", "--push", "origin", "https://github.com/canonical/skill-bill.git")
    runGit(repo, "remote", "add", "upstream", "https://github.com/canonical/skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(true, status.pushTarget?.isLikelyCanonical)
    assertNotNull(status.pushTarget?.canonicalWarning)
    assertEquals("https://github.com/canonical/skill-bill/compare/$branch", status.compareUrl)
  }

  @Test
  fun `publishingStatus treats any canonical pushurl as canonical risk`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "git@github.com:contributor/skill-bill.git")
    runGit(repo, "remote", "set-url", "--push", "--add", "origin", "git@github.com:contributor/skill-bill.git")
    runGit(repo, "remote", "set-url", "--push", "--add", "origin", "https://github.com/canonical/skill-bill.git")
    runGit(repo, "remote", "add", "upstream", "https://github.com/canonical/skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(true, status.pushTarget?.isLikelyCanonical)
    assertNotNull(status.pushTarget?.canonicalWarning)
    assertEquals(null, status.compareUrl)
  }

  @Test
  fun `publishingStatus uses fork pushurl instead of canonical fetch url`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "https://github.com/canonical/skill-bill.git")
    runGit(repo, "remote", "set-url", "--push", "origin", "git@github.com:contributor/skill-bill.git")
    runGit(repo, "remote", "add", "upstream", "https://github.com/canonical/skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(false, status.pushTarget?.isLikelyCanonical)
    assertEquals("https://github.com/canonical/skill-bill/compare/main...contributor:$branch", status.compareUrl)
  }

  @Test
  fun `publishingStatus blocks single-origin topology by marking target likely canonical`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "https://github.com/canonical/skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(true, status.pushTarget?.isLikelyCanonical)
    assertNotNull(status.pushTarget?.canonicalWarning)
    assertEquals("https://github.com/canonical/skill-bill/compare/$branch", status.compareUrl)
  }

  @Test
  fun `publishingStatus keeps origin canonical when upstream is not a matching fork topology`() {
    val repo = newRepoWithCommit()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", "https://github.com/canonical/skill-bill.git")
    runGit(repo, "remote", "add", "upstream", "https://github.com/dummy/not-skill-bill.git")
    val service = RuntimeGitGateway()

    val status = service.publishingStatus(loadedSession(repo))

    assertEquals("origin/$branch", status.pushTarget?.displayName)
    assertEquals(true, status.pushTarget?.isLikelyCanonical)
    assertNotNull(status.pushTarget?.canonicalWarning)
  }

  @Test
  fun `push validates remote and branch names before invoking git`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)

    val badRemote = service.push(session, GitPushTarget(remoteName = "-c", branchName = "feature"))
    val badBranch = service.push(session, GitPushTarget(remoteName = "origin", branchName = "feature:main"))

    assertFalse(badRemote.success)
    assertEquals("Push remote name is invalid.", badRemote.errorMessage)
    assertFalse(badBranch.success)
    assertEquals("Push branch name is invalid.", badBranch.errorMessage)
  }

  @Test
  fun `push rejects stale target when current branch changed before invoking git`() {
    val repo = newRepoWithCommit()
    val remote = newBareRemote()
    runGit(repo, "remote", "add", "origin", remote.toUri().toString())
    runGit(repo, "checkout", "-b", "feature")
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    val target = service.publishingStatus(session).pushTarget
    assertNotNull(target)
    runGit(repo, "checkout", "-b", "other")

    val result = service.push(session, target)

    assertFalse(result.success)
    assertTrue(result.errorMessage.orEmpty().contains("Current branch changed from feature to other"))
    assertEquals(1, gitExitCode(remote, "show-ref", "--verify", "--quiet", "refs/heads/feature"))
  }

  @Test
  fun `commit error includes git output and credentialed URLs are redacted`() {
    val repo = newRepoWithCommit()
    val service = RuntimeGitGateway()

    val commitResult = service.commit(loadedSession(repo), "no changes")
    val redacted = redactCredentialedUrls(
      "fatal: Authentication failed for 'https://user:secret-token@github.com/owner/repo.git'",
    )

    assertFalse(commitResult.success)
    assertTrue(commitResult.errorMessage.orEmpty().contains("nothing to commit"))
    assertEquals(
      "fatal: Authentication failed for 'https://user:<redacted>@github.com/owner/repo.git'",
      redacted,
    )
  }

  @Test
  fun `push error redacts credentialed remote URL through gateway result`() {
    val repo = newRepoWithCommit()
    runGit(repo, "remote", "add", "origin", "https://user:secret-token@localhost:9/owner/repo.git")
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)
    val target = service.publishingStatus(session).pushTarget
    assertNotNull(target)

    val pushResult = service.push(session, target)

    assertFalse(pushResult.success)
    assertTrue(pushResult.errorMessage.orEmpty().contains("user:<redacted>@localhost:9"))
    assertFalse(pushResult.errorMessage.orEmpty().contains("secret-token"))
  }

  @Test
  fun `push to selected target refreshes ahead behind from remote tracking state`() {
    val repo = newRepoWithCommit()
    val remote = newBareRemote()
    val branch = currentBranch(repo)
    runGit(repo, "remote", "add", "origin", remote.toUri().toString())
    runGit(repo, "push", "-u", "origin", branch)
    val service = RuntimeGitGateway()
    val session = loadedSession(repo)

    Files.writeString(repo.resolve("after-push.md"), "queued\n")
    runGit(repo, "add", "after-push.md")
    assertTrue(service.commit(session, "queued push").success)
    val beforePush = service.publishingStatus(session)
    assertEquals(1, beforePush.aheadBehind?.ahead)

    val pushResult = service.push(session, beforePush.pushTarget!!)
    val afterPush = service.publishingStatus(session)

    assertTrue(pushResult.success, "push failed: ${pushResult.errorMessage}")
    assertEquals(0, afterPush.aheadBehind?.ahead)
    assertEquals(0, afterPush.aheadBehind?.behind)
  }

  // ---- helpers ----

  private fun newRepoWithCommit(): Path {
    val repo = Files.createTempDirectory("skillbill-git-")
    cleanupRoots.add(repo)
    runGit(repo, "init", "-q")
    runGit(repo, "config", "user.email", "test@example.com")
    runGit(repo, "config", "user.name", "Test")
    Files.createDirectories(repo.resolve("skills/bill-alpha"))
    Files.writeString(
      repo.resolve("skills/bill-alpha/content.md"),
      """
        |---
        |name: bill-alpha
        |description: Alpha guidance.
        |---
        |
        |# alpha
      """.trimMargin(),
    )
    runGit(repo, "add", ".")
    runGit(repo, "commit", "-q", "-m", "initial")
    return repo
  }

  private fun newBareRemote(): Path {
    val remote = Files.createTempDirectory("skillbill-remote-")
    cleanupRoots.add(remote)
    runGit(remote, "init", "--bare", "-q")
    return remote
  }

  private fun currentBranch(repo: Path): String {
    val process = ProcessBuilder("git", "-C", repo.toString(), "branch", "--show-current")
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git branch --show-current timed out")
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.exitValue() == 0 && output.isNotBlank()) { "git branch --show-current failed: $output" }
    return output
  }

  private fun loadedSession(repo: Path): RepoSession = RepoSession(
    repoPath = repo.toString(),
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
  )

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

  private fun gitExitCode(root: Path, vararg args: String): Int {
    val command = mutableListOf("git", "-C", root.toString())
    command.addAll(args)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("git ${args.joinToString(" ")} timed out")
    }
    return process.exitValue()
  }
}

// F-408: hashing file content (not mtime) verifies AC8 read-only invariant on filesystems with
// coarse mtime granularity (NTFS via WSL, ext4 with noatime) where a write within a single mtime
// tick would otherwise be invisible to the snapshot.
private fun repoFileSnapshot(repo: Path): Map<String, String> = Files.walk(repo).use { paths ->
  paths
    .filter(Files::isRegularFile)
    .filter { path -> !path.toString().replace('\\', '/').contains("/.git/") }
    .sorted()
    .toList()
    .associate { path ->
      path.relativeTo(repo).toString().replace('\\', '/') to sha256Hex(Files.readAllBytes(path))
    }
}

private fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) }
  }
}
