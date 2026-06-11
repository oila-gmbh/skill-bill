package skillbill.desktop.feature.skillbill.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

internal actual fun observeRepoFileChanges(repoPath: String): Flow<RepoFileChangeKind> = channelFlow {
  val root = runCatching { Path.of(repoPath).toAbsolutePath().normalize() }.getOrNull()
  if (root == null || !Files.isDirectory(root)) {
    close()
    return@channelFlow
  }
  val watchService = runCatching { FileSystems.getDefault().newWatchService() }
    .getOrElse {
      close()
      return@channelFlow
    }
  val observer = JvmRepoFileChangeObserver(
    root = root,
    watchService = watchService,
    emit = { change -> trySend(change).isSuccess },
  )
  val job = launch(Dispatchers.IO) {
    observer.run()
  }
  awaitClose {
    observer.close()
    job.cancel()
  }
}

private class JvmRepoFileChangeObserver(
  private val root: Path,
  private val watchService: WatchService,
  private val emit: (RepoFileChangeKind) -> Unit,
) {
  private val keysByDirectory = mutableMapOf<WatchKey, Path>()

  fun run() {
    try {
      registerInitialDirectories()
      while (true) {
        val key = watchService.take()
        val directory = keysByDirectory[key]
        if (directory != null) {
          handleEvents(key, directory)
        }
        if (!key.reset()) {
          keysByDirectory.remove(key)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (_: IOException) {
      emit(RepoFileChangeKind.RepoSnapshot)
    } catch (_: ClosedWatchServiceException) {
      // Normal cancellation path when the route switches repos or leaves composition.
    } finally {
      close()
    }
  }

  fun close() {
    runCatching { watchService.close() }
  }

  private fun handleEvents(key: WatchKey, directory: Path) {
    key.pollEvents().forEach { event ->
      if (event.kind() == java.nio.file.StandardWatchEventKinds.OVERFLOW) {
        emit(RepoFileChangeKind.RepoSnapshot)
        return@forEach
      }
      val changedPath = directory.resolve(event.contextPath()).normalize()
      if (!shouldObserve(changedPath)) {
        return@forEach
      }
      if (event.kind() == ENTRY_CREATE && Files.isDirectory(changedPath)) {
        registerCreatedDirectory(changedPath)
      }
      emit(RepoFileChangeKind.RepoSnapshot)
    }
  }

  private fun registerInitialDirectories() {
    Files.walkFileTree(
      root,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (!shouldObserve(dir)) {
            return FileVisitResult.SKIP_SUBTREE
          }
          registerDirectory(dir)
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
      },
    )
  }

  private fun registerCreatedDirectory(directory: Path) {
    runCatching {
      Files.walkFileTree(
        directory,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!shouldObserve(dir)) {
              return FileVisitResult.SKIP_SUBTREE
            }
            registerDirectory(dir)
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
      )
    }
  }

  private fun registerDirectory(directory: Path) {
    val key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
    keysByDirectory[key] = directory
  }

  private fun shouldObserve(path: Path): Boolean {
    val relative = runCatching { root.relativize(path) }.getOrNull() ?: return false
    if (relative.nameCount == 0) {
      return true
    }
    val names = (0 until relative.nameCount).map { index -> relative.getName(index).name }
    return names.none { name -> name in ignoredDirectoryNames }
  }

  private fun WatchEvent<*>.contextPath(): Path = context() as Path
}

private val ignoredDirectoryNames = setOf(
  ".git",
  ".gradle",
  ".idea",
  ".kotlin",
  ".fleet",
  ".run",
  "build",
  "node_modules",
  "out",
  "target",
)
