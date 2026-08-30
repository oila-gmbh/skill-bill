package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class DecompositionManifestBundleJournal {
  private val yamlMapper = YAMLMapper()

  fun create(parent: Path, writes: List<Pair<Path, String>>): DecompositionManifestBundleTransaction =
    DecompositionManifestBundleJournalCreate.create(parent, writes, yamlMapper, this)

  fun apply(transaction: DecompositionManifestBundleTransaction) =
    DecompositionManifestBundleJournalIo.apply(transaction)

  fun recoverPending(parent: Path?) = DecompositionManifestBundleJournalRecovery.recoverPending(parent, this)

  fun recoverPendingUnlocked(parent: Path?) =
    DecompositionManifestBundleJournalRecovery.recoverPendingUnlocked(parent, this)

  fun failIfPending(parent: Path?) = DecompositionManifestBundleJournalRecovery.failIfPending(parent)

  fun failIfPendingUnder(root: Path) = DecompositionManifestBundleJournalRecovery.failIfPendingUnder(root)

  fun cleanup(transaction: DecompositionManifestBundleTransaction) =
    DecompositionManifestBundleJournalIo.cleanup(transaction)

  fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(requireNotNull(target.parent))
    val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
    try {
      Files.writeString(temp, content, StandardOpenOption.TRUNCATE_EXISTING)
      Files.newByteChannel(temp, StandardOpenOption.WRITE).use { channel ->
        (channel as? FileChannel)?.force(true)
      }
      DecompositionManifestBundleJournalIo.moveAtomically(temp, target)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  internal fun read(marker: Path): DecompositionManifestBundleTransaction =
    DecompositionManifestBundleJournalIo.read(marker, yamlMapper)

  internal companion object {
    const val BUNDLE_CONTRACT_VERSION = "0.1"
    const val BUNDLE_PREFIX = ".decomposition-manifest-bundle-"
    const val MARKER_SUFFIX = ".commit"
    const val STAGING_SUFFIX = ".staging"
  }
}

private val processBundleLocks = ConcurrentHashMap<Path, ReentrantLock>()

internal fun <T> withDecompositionManifestBundleLock(parent: Path?, action: () -> T): T {
  if (parent == null) return action()
  val normalizedParent = parent.toAbsolutePath().normalize()
  val lockPath = decompositionManifestLockPath(normalizedParent)
  val processLock = processBundleLocks.computeIfAbsent(lockPath) { ReentrantLock() }
  val outermost = !processLock.isHeldByCurrentThread
  processLock.lock()
  try {
    if (!outermost) return action()
    Files.createDirectories(requireNotNull(lockPath.parent))
    return FileChannel.open(
      lockPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use { action() }
    }
  } finally {
    processLock.unlock()
  }
}

private fun decompositionManifestLockPath(parent: Path): Path {
  val owner = lockOwner(parent)
  val digest = java.security.MessageDigest.getInstance(LOCK_DIGEST_ALGORITHM)
    .digest(owner.toString().toByteArray(Charsets.UTF_8))
    .joinToString(LOCK_DIGEST_SEPARATOR) { byte -> "%02x".format(byte) }
  return Path.of(System.getProperty(JAVA_TEMP_DIRECTORY_PROPERTY))
    .resolve(LOCK_DIRECTORY_NAME)
    .resolve("$digest$LOCK_FILE_SUFFIX")
}

private fun lockOwner(parent: Path): Path {
  var current = parent
  while (true) {
    if (current.fileName?.toString() == FEATURE_SPECS_DIRECTORY_NAME) return current
    current = current.parent ?: return parent
  }
}

private const val FEATURE_SPECS_DIRECTORY_NAME = ".feature-specs"
private const val JAVA_TEMP_DIRECTORY_PROPERTY = "java.io.tmpdir"
private const val LOCK_DIRECTORY_NAME = "skill-bill-decomposition-manifest-locks"
private const val LOCK_DIGEST_ALGORITHM = "SHA-256"
private const val LOCK_DIGEST_SEPARATOR = ""
private const val LOCK_FILE_SUFFIX = ".lock"
