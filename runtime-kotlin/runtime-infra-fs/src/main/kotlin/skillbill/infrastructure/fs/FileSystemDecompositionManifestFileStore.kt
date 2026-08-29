package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Inject
@Suppress("TooManyFunctions")
class FileSystemDecompositionManifestFileStore : DecompositionManifestFileStore {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val bundleJournal = DecompositionManifestBundleJournal()

  override fun readText(path: Path): String {
    return withBundleLock(path.parent) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.readString(path)
    }
  }

  override fun readTextWithoutRecovery(path: Path): String {
    bundleJournal.failIfPending(path.parent)
    return Files.readString(path)
  }

  override fun isRegularFile(path: Path): Boolean {
    return withBundleLock(path.parent) {
      bundleJournal.recoverPendingUnlocked(path.parent)
      Files.isRegularFile(path)
    }
  }

  override fun isRegularFileWithoutRecovery(path: Path): Boolean {
    bundleJournal.failIfPending(path.parent)
    return Files.isRegularFile(path)
  }

  override fun listDirectChildDirectories(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.toList()
    }
  }

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> {
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecsRoot)) return emptyList()
    Files.walk(featureSpecsRoot).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.forEach(bundleJournal::recoverPending)
    }
    return Files.walk(featureSpecsRoot).use { paths ->
      paths
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "decomposition-manifest.yaml" }
        .toList()
    }
  }

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> {
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecsRoot)) return emptyList()
    bundleJournal.failIfPendingUnder(featureSpecsRoot)
    return Files.walk(featureSpecsRoot).use { paths ->
      paths
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "decomposition-manifest.yaml" }
        .toList()
    }
  }

  override fun <T> writeBundleAtomically(writes: List<Pair<Path, String>>, verify: () -> T): T {
    val distinctWrites = writes.distinctBy { (path, _) -> path.toAbsolutePath().normalize() }
    val parents = distinctWrites.map { (path, _) -> path.toAbsolutePath().normalize().parent }.distinct()
    if (parents.size != 1 || distinctWrites.isEmpty()) {
      return super<DecompositionManifestFileStore>.writeBundleAtomically(writes, verify)
    }

    val parent = requireNotNull(parents.single())
    return withBundleLock(parent) {
      bundleJournal.recoverPendingUnlocked(parent)
      val snapshots = distinctWrites.map { (path, _) ->
        val normalized = path.toAbsolutePath().normalize()
        val existed = Files.isRegularFile(normalized)
        BundleSnapshot(normalized, existed, if (existed) Files.readString(normalized) else null)
      }
      val transaction = bundleJournal.create(parent, distinctWrites)
      runCatching {
        bundleJournal.apply(transaction)
        val result = verify()
        bundleJournal.cleanup(transaction)
        result
      }.getOrElse { failure ->
        snapshots.asReversed().forEach { snapshot ->
          runCatching {
            if (snapshot.existed) {
              bundleJournal.writeAtomically(snapshot.path, requireNotNull(snapshot.content))
            } else {
              Files.deleteIfExists(snapshot.path)
            }
          }.onFailure(failure::addSuppressed)
        }
        runCatching { bundleJournal.cleanup(transaction) }.onFailure(failure::addSuppressed)
        throw failure
      }
    }
  }

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = yamlMapper.writeValueAsString(wireMap)

  override fun deleteIfExists(target: Path) {
    withBundleLock(target.parent) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      Files.deleteIfExists(target)
    }
  }

  override fun writeTextAtomically(target: Path, content: String) {
    withBundleLock(target.parent) {
      bundleJournal.recoverPendingUnlocked(target.parent)
      bundleJournal.writeAtomically(target, content)
    }
  }
}

private data class BundleEntry(val target: Path, val staged: Path, val sha256: String)

private data class BundleTransaction(
  val marker: Path,
  val stagingDirectory: Path,
  val entries: List<BundleEntry>,
)

@Suppress("TooManyFunctions")
private class DecompositionManifestBundleJournal {
  private val yamlMapper = YAMLMapper()

  fun create(parent: Path, writes: List<Pair<Path, String>>): BundleTransaction {
    val transactionId = UUID.randomUUID().toString()
    val stagingDirectory = parent.resolve("$BUNDLE_PREFIX$transactionId$STAGING_SUFFIX")
    Files.createDirectories(stagingDirectory)
    val entries = writes.mapIndexed { index, (path, content) ->
      val target = path.toAbsolutePath().normalize()
      val staged = stagingDirectory.resolve("entry-$index")
      writeAtomically(staged, content)
      BundleEntry(target, staged, sha256(content))
    }
    val marker = parent.resolve("$BUNDLE_PREFIX$transactionId$MARKER_SUFFIX")
    writeAtomically(
      marker,
      yamlMapper.writeValueAsString(
        mapOf(
          "contract_version" to BUNDLE_CONTRACT_VERSION,
          "staging_directory" to stagingDirectory.toString(),
          "entries" to entries.map { entry ->
            mapOf(
              "target" to entry.target.toString(),
              "staged" to entry.staged.toString(),
              "sha256" to entry.sha256,
            )
          },
        ),
      ),
    )
    return BundleTransaction(marker, stagingDirectory, entries)
  }

  fun apply(transaction: BundleTransaction) {
    transaction.entries.forEach { entry ->
      when {
        Files.isRegularFile(entry.staged) -> moveAtomically(entry.staged, entry.target)
        Files.isRegularFile(entry.target) && sha256(Files.readString(entry.target)) == entry.sha256 -> Unit
        else -> error("Decomposition manifest bundle journal is incomplete for '${entry.target}'.")
      }
    }
  }

  fun recoverPending(parent: Path?) {
    withBundleLock(parent) { recoverPendingUnlocked(parent) }
  }

  fun recoverPendingUnlocked(parent: Path?) {
    if (parent == null || !Files.isDirectory(parent)) return
    Files.newDirectoryStream(parent, "$BUNDLE_PREFIX*$MARKER_SUFFIX").use { markers ->
      markers.toList().forEach { marker ->
        val transaction = read(marker)
        apply(transaction)
        cleanup(transaction)
      }
    }
  }

  fun failIfPending(parent: Path?) {
    if (parent == null || !Files.isDirectory(parent)) return
    Files.newDirectoryStream(parent, "$BUNDLE_PREFIX*$MARKER_SUFFIX").use { markers ->
      if (markers.iterator().hasNext()) {
        throw InvalidDecompositionManifestSchemaError(
          sourceLabel = parent.toString(),
          reason = "decomposition manifest bundle has an incomplete journal; launch recovery is required.",
          failureCode = "incomplete_bundle",
        )
      }
    }
  }

  fun failIfPendingUnder(root: Path) {
    Files.walk(root).use { paths ->
      paths
        .filter(Files::isDirectory)
        .forEach(::failIfPending)
    }
  }

  fun cleanup(transaction: BundleTransaction) {
    Files.deleteIfExists(transaction.marker)
    deleteRecursively(transaction.stagingDirectory)
  }

  fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(requireNotNull(target.parent))
    val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
    try {
      Files.writeString(temp, content, StandardOpenOption.TRUNCATE_EXISTING)
      Files.newByteChannel(temp, StandardOpenOption.WRITE).use { channel ->
        (channel as? java.nio.channels.FileChannel)?.force(true)
      }
      moveAtomically(temp, target)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun read(marker: Path): BundleTransaction {
    val raw = yamlMapper.readValue(Files.readString(marker), Map::class.java) as Map<String, Any?>
    require(raw["contract_version"] == BUNDLE_CONTRACT_VERSION) {
      "Unsupported decomposition manifest bundle journal contract."
    }
    val stagingDirectory = Path.of(requireNotNull(raw["staging_directory"] as? String))
    val entries = requireNotNull(raw["entries"] as? List<*>)
      .map { rawEntry ->
        val entry = rawEntry as? Map<*, *> ?: error("Malformed decomposition manifest bundle journal entry.")
        BundleEntry(
          target = Path.of(requireNotNull(entry["target"] as? String)).toAbsolutePath().normalize(),
          staged = Path.of(requireNotNull(entry["staged"] as? String)).toAbsolutePath().normalize(),
          sha256 = requireNotNull(entry["sha256"] as? String),
        )
      }
    require(stagingDirectory.toAbsolutePath().normalize().parent == marker.parent.toAbsolutePath().normalize()) {
      "Decomposition manifest bundle journal staging directory is outside its parent."
    }
    entries.forEach { entry ->
      require(entry.target.parent == marker.parent.toAbsolutePath().normalize()) {
        "Decomposition manifest bundle journal target is outside its parent."
      }
      require(entry.staged.parent == stagingDirectory.toAbsolutePath().normalize()) {
        "Decomposition manifest bundle journal entry is outside its staging directory."
      }
    }
    return BundleTransaction(marker, stagingDirectory, entries)
  }

  private fun moveAtomically(source: Path, target: Path) {
    Files.createDirectories(requireNotNull(target.parent))
    try {
      Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source, target, REPLACE_EXISTING)
    }
  }

  private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

  private companion object {
    const val BUNDLE_CONTRACT_VERSION = "0.1"
    const val BUNDLE_PREFIX = ".decomposition-manifest-bundle-"
    const val MARKER_SUFFIX = ".commit"
    const val STAGING_SUFFIX = ".staging"
  }
}

private data class BundleSnapshot(val path: Path, val existed: Boolean, val content: String?)

private val processBundleLocks = ConcurrentHashMap<Path, ReentrantLock>()

private fun <T> withBundleLock(parent: Path?, action: () -> T): T {
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
  val digest = MessageDigest.getInstance(LOCK_DIGEST_ALGORITHM)
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
