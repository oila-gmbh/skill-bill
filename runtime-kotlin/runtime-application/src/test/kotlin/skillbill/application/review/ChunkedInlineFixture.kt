package skillbill.application.review

import skillbill.ports.diff.DiffResolverPort
import skillbill.scaffold.model.ReviewLaneCondition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull

internal data class ChunkedInlineFixture(
  val recorder: ReviewRecorder,
  val repoRoot: Path,
  val resolver: DiffResolverPort,
  val revision: String,
  val expansionPath: String,
) {
  fun git(vararg args: String) = assertNotNull(
    resolver.runProcess(listOf("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + args, repoRoot),
  )
}

internal fun chunkedInlineArchitectureSecurityManifests() = listOf(
  reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt")).copy(
    laneConditions = mapOf(
      "architecture" to ReviewLaneCondition(path = listOf("src/core/")),
      "security" to ReviewLaneCondition(path = listOf("src/secure/")),
    ),
  ),
)

internal fun prepareChunkedInlineRepository(directoryPrefix: String): ChunkedInlineFixture {
  val expansionPath = "src/secure/Auth.kt"
  val recorder = ReviewRecorder()
  val repoRoot = Files.createTempDirectory(directoryPrefix)
  val resolver = reviewFileSystemDiffResolver()
  val fixture = ChunkedInlineFixture(recorder, repoRoot, resolver, revision = "", expansionPath = expansionPath)
  fixture.git("init", "--quiet")
  Files.createDirectories(repoRoot.resolve("src/secure"))
  Files.createDirectories(repoRoot.resolve("src/core"))
  (1..40).forEach { index ->
    Files.writeString(repoRoot.resolve("src/core/chunk$index.kt"), "old$index\n")
  }
  Files.writeString(repoRoot.resolve(expansionPath), "committed security")
  fixture.git("add", ".")
  fixture.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
  val revision = fixture.git("rev-parse", "HEAD").trim()
  (1..40).forEach { index ->
    Files.writeString(repoRoot.resolve("src/core/chunk$index.kt"), "x".repeat(2_000) + "\n")
  }
  Files.writeString(repoRoot.resolve(expansionPath), "indexed security")
  fixture.git("add", ".")
  return fixture.copy(revision = revision)
}
