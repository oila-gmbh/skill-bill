package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.PrPublishingRequest
import skillbill.desktop.core.domain.model.PrPublishingResult
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimePrPublishingGatewayTest {
  private val cleanupRoots = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    cleanupRoots.forEach { root -> runCatching { deleteRecursively(root) } }
    cleanupRoots.clear()
  }

  @Test
  fun `publish creates draft pr with explicit pushed head`() {
    val repo = newRepo()
    val log = repo.resolve("gh-args.log")
    val gh = fakeGh(
      repo = repo,
      body = """
        |#!/usr/bin/env bash
        |printf '%s\n' "${'$'}@" >> "$log"
        |if [[ "${'$'}1 ${'$'}2" == "pr create" ]]; then
        |  printf 'https://github.com/canonical/skill-bill/pull/123\n'
        |fi
      """.trimMargin(),
    )
    val gateway = RuntimePrPublishingGateway { gh }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature", branchOwner = "contributor"),
        compareUrl = "https://github.com/canonical/skill-bill/compare/main...contributor:feature",
        title = "Publish runtime shell",
        body = "Review notes",
        draft = true,
      ),
    )

    assertEquals(
      PrPublishingResult.CreatedDraftPullRequest("https://github.com/canonical/skill-bill/pull/123"),
      result,
    )
    val args = Files.readString(log)
    val expectedCreateArgs = listOf(
      "pr",
      "create",
      "--head",
      "contributor:feature",
      "--draft",
      "--title",
      "Publish runtime shell",
      "--body",
      "Review notes",
    ).joinToString(separator = "\n", postfix = "\n")
    assertTrue("pr\nlist\n--search\nhead:contributor:feature\n" in args, args)
    assertTrue(expectedCreateArgs in args, args)
  }

  @Test
  fun `publish opens existing pr for pushed branch before creating a duplicate`() {
    val repo = newRepo()
    val log = repo.resolve("gh-args.log")
    val gh = fakeGh(
      repo = repo,
      body = """
        |#!/usr/bin/env bash
        |printf '%s\n' "${'$'}@" >> "$log"
        |if [[ "${'$'}1 ${'$'}2" == "pr list" ]]; then
        |  printf 'https://github.com/canonical/skill-bill/pull/99\n'
        |fi
      """.trimMargin(),
    )
    val gateway = RuntimePrPublishingGateway { gh }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
        compareUrl = "https://github.com/canonical/skill-bill/compare/feature",
        title = "Publish runtime shell",
        body = "",
        draft = true,
      ),
    )

    assertEquals(PrPublishingResult.ExistingPullRequest("https://github.com/canonical/skill-bill/pull/99"), result)
    val args = Files.readString(log)
    assertTrue("pr\nlist\n--head\nfeature\n" in args, args)
    assertTrue("pr\ncreate" !in args, args)
  }

  @Test
  fun `publish returns compare fallback when provider create fails and compare url is available`() {
    val repo = newRepo()
    val gh = fakeGh(
      repo = repo,
      body = """
        |#!/usr/bin/env bash
        |if [[ "${'$'}1 ${'$'}2" == "pr create" ]]; then
        |  printf 'authentication required\n'
        |  exit 1
        |fi
      """.trimMargin(),
    )
    val gateway = RuntimePrPublishingGateway { gh }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
        compareUrl = "https://github.com/canonical/skill-bill/compare/feature",
        title = "Publish runtime shell",
        body = "",
        draft = true,
      ),
    )

    assertEquals(
      PrPublishingResult.CompareUrlFallback(
        url = "https://github.com/canonical/skill-bill/compare/feature",
        reason = "authentication required",
      ),
      result,
    )
  }

  @Test
  fun `publish returns compare fallback when gh executable is unavailable`() {
    val repo = newRepo()
    val gateway = RuntimePrPublishingGateway { null }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
        compareUrl = "https://github.com/canonical/skill-bill/compare/feature",
        title = "Publish runtime shell",
        body = "",
        draft = true,
      ),
    )

    assertEquals(
      PrPublishingResult.CompareUrlFallback(
        url = "https://github.com/canonical/skill-bill/compare/feature",
        reason = "GitHub CLI executable was not found on PATH.",
      ),
      result,
    )
  }

  @Test
  fun `publish redacts token username credentials from provider failures`() {
    val repo = newRepo()
    val gh = fakeGh(
      repo = repo,
      body = """
        |#!/usr/bin/env bash
        |if [[ "${'$'}1 ${'$'}2" == "pr create" ]]; then
        |  printf 'failed to access https://ghp_secret123@github.com/org/repo.git\n'
        |  exit 1
        |fi
      """.trimMargin(),
    )
    val gateway = RuntimePrPublishingGateway { gh }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
        compareUrl = null,
        title = "Publish runtime shell",
        body = "",
        draft = false,
      ),
    )

    val failed = assertIs<PrPublishingResult.Failed>(result)
    assertEquals("failed to access https://<redacted>@github.com/org/repo.git", failed.message)
  }

  @Test
  fun `publish returns failed error when provider create fails and no compare url is available`() {
    val repo = newRepo()
    val gh = fakeGh(
      repo = repo,
      body = """
        |#!/usr/bin/env bash
        |if [[ "${'$'}1 ${'$'}2" == "pr create" ]]; then
        |  printf 'remote unavailable\n'
        |  exit 1
        |fi
      """.trimMargin(),
    )
    val gateway = RuntimePrPublishingGateway { gh }

    val result = gateway.publish(
      PrPublishingRequest(
        session = loadedSession(repo),
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
        compareUrl = null,
        title = "Publish runtime shell",
        body = "",
        draft = false,
      ),
    )

    val failed = assertIs<PrPublishingResult.Failed>(result)
    assertEquals("remote unavailable", failed.message)
  }

  private fun newRepo(): Path {
    val repo = Files.createTempDirectory("skillbill-pr-")
    cleanupRoots.add(repo)
    return repo
  }

  private fun fakeGh(repo: Path, body: String): Path {
    val gh = repo.resolve("fake-gh")
    Files.writeString(gh, body)
    gh.toFile().setExecutable(true)
    return gh
  }

  private fun loadedSession(repo: Path): RepoSession = RepoSession(
    repoPath = repo.toString(),
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
  )
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) }
  }
}
