package skillbill.desktop.feature.skillbill.ui

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class RepoFileChangeObserverTest {
  @Test
  fun `observer emits when a file changes in the repo`() = runBlocking {
    val repo = Files.createTempDirectory("skill-bill-repo-watch")
    val event = async {
      withTimeout(5_000) {
        observeRepoFileChanges(repo.toString()).first()
      }
    }

    delay(250)
    repo.resolve("content.md").writeText("changed\n")

    assertEquals(RepoFileChangeKind.RepoSnapshot, event.await())
  }

  @Test
  fun `observer does not emit for git directory changes`() = runBlocking {
    val repo = Files.createTempDirectory("skill-bill-repo-watch-git-only")
    Files.createDirectories(repo.resolve(".git"))
    val event = async {
      withTimeout(1_500) {
        observeRepoFileChanges(repo.toString()).first()
      }
    }

    delay(250)
    repo.resolve(".git").resolve("index").writeText("index\n")

    try {
      val emitted = event.await()
      fail("Expected no emission for .git-only changes, but observed $emitted")
    } catch (_: TimeoutCancellationException) {
      // Expected: a write confined to .git must never produce a RepoSnapshot.
    }
  }

  @Test
  fun `observer emits for tracked file changes even when a git directory exists`() = runBlocking {
    val repo = Files.createTempDirectory("skill-bill-repo-watch-git-and-tracked")
    Files.createDirectories(repo.resolve(".git"))
    val event = async {
      withTimeout(5_000) {
        observeRepoFileChanges(repo.toString()).first()
      }
    }

    delay(250)
    repo.resolve("content.md").writeText("changed\n")

    assertEquals(RepoFileChangeKind.RepoSnapshot, event.await())
  }
}
