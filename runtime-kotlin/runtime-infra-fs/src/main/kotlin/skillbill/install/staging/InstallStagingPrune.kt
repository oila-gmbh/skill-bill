package skillbill.install.staging

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val pruneLog: Logger = Logger.getLogger("skillbill.install.InstallStaging")

internal fun pruneStaleStagingDirs(home: Path, resolvedSource: Path, currentHash: String) {
  val cacheRoot = installedSkillsCacheRoot(home)
  val slug = installedSkillSlug(resolvedSource)
  if (!Files.isDirectory(cacheRoot) || slug.isEmpty()) {
    return
  }
  val currentLeaf = "$slug-$currentHash"
  val hashRegex = Regex("^${Regex.escape(slug)}-[0-9a-f]{${INSTALL_CACHE_KEY_BYTES * 2}}$")
  val candidates = try {
    Files.list(cacheRoot).use { stream ->
      stream
        .filter { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
        .filter { entry ->
          val name = entry.fileName.toString()
          name.matches(hashRegex) && name != currentLeaf
        }
        .toList()
    }
  } catch (error: IOException) {
    pruneLog.log(Level.WARNING, "pruneStaleStagingDirs list failure cacheRoot=$cacheRoot", error)
    emptyList()
  }
  candidates.forEach { stale ->
    try {
      deleteInstallStagingDirectory(stale)
    } catch (error: IOException) {
      pruneLog.log(
        Level.WARNING,
        "pruneStaleStagingDirs delete failure dir=$stale (suppressed; install completed successfully)",
        error,
      )
    } catch (error: IllegalStateException) {
      pruneLog.log(
        Level.WARNING,
        "pruneStaleStagingDirs delete failure dir=$stale (suppressed; install completed successfully)",
        error,
      )
    }
  }
}
