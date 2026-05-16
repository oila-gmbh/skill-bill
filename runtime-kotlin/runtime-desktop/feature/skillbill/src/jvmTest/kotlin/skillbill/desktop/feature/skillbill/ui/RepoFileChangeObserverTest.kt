package skillbill.desktop.feature.skillbill.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

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
  fun `observer emits when git index changes`() = runBlocking {
    val repo = Files.createTempDirectory("skill-bill-repo-watch-git")
    Files.createDirectories(repo.resolve(".git"))
    val event = async {
      withTimeout(5_000) {
        observeRepoFileChanges(repo.toString()).first()
      }
    }

    delay(250)
    repo.resolve(".git").resolve("index").writeText("index\n")

    assertEquals(RepoFileChangeKind.GitStatus, event.await())
  }
}
