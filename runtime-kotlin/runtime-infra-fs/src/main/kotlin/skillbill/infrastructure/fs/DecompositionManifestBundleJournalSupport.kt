package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidDecompositionManifestSchemaError
import java.nio.file.Files
import java.nio.file.Path

internal object DecompositionManifestBundleJournalRecovery {
  fun recoverPending(parent: Path?, journal: DecompositionManifestBundleJournal) {
    withDecompositionManifestBundleLock(parent) { journal.recoverPendingUnlocked(parent) }
  }

  fun recoverPendingUnlocked(parent: Path?, journal: DecompositionManifestBundleJournal) {
    if (parent == null || !Files.isDirectory(parent)) return
    val markerGlob =
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}*" +
        "${DecompositionManifestBundleJournal.MARKER_SUFFIX}"
    Files.newDirectoryStream(parent, markerGlob)
      .use { markers ->
        markers.toList().forEach { marker ->
          val transaction = journal.read(marker)
          journal.apply(transaction)
          journal.cleanup(transaction)
        }
      }
  }

  fun failIfPending(parent: Path?) {
    if (parent == null || !Files.isDirectory(parent)) return
    val markerGlob =
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}*" +
        "${DecompositionManifestBundleJournal.MARKER_SUFFIX}"
    Files.newDirectoryStream(parent, markerGlob)
      .use { markers ->
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
}

internal object DecompositionManifestBundleJournalCreate {
  fun create(
    parent: Path,
    writes: List<Pair<Path, String>>,
    yamlMapper: YAMLMapper,
    journal: DecompositionManifestBundleJournal,
  ): DecompositionManifestBundleTransaction {
    val transactionId = java.util.UUID.randomUUID().toString()
    val stagingDirectory = parent.resolve(
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}$transactionId" +
        DecompositionManifestBundleJournal.STAGING_SUFFIX,
    )
    Files.createDirectories(stagingDirectory)
    val entries = writes.mapIndexed { index, (path, content) ->
      val target = path.toAbsolutePath().normalize()
      val staged = stagingDirectory.resolve("entry-$index")
      journal.writeAtomically(staged, content)
      DecompositionManifestBundleEntry(target, staged, DecompositionManifestBundleJournalIo.sha256(content))
    }
    val marker = parent.resolve(
      "${DecompositionManifestBundleJournal.BUNDLE_PREFIX}$transactionId" +
        DecompositionManifestBundleJournal.MARKER_SUFFIX,
    )
    journal.writeAtomically(
      marker,
      yamlMapper.writeValueAsString(
        mapOf(
          "contract_version" to DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION,
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
    return DecompositionManifestBundleTransaction(marker, stagingDirectory, entries)
  }
}

internal object DecompositionManifestBundleJournalIo {
  fun apply(transaction: DecompositionManifestBundleTransaction) {
    transaction.entries.forEach { entry ->
      when {
        Files.isRegularFile(entry.staged) -> moveAtomically(entry.staged, entry.target)
        Files.isRegularFile(entry.target) && sha256(Files.readString(entry.target)) == entry.sha256 -> Unit
        else -> error("Decomposition manifest bundle journal is incomplete for '${entry.target}'.")
      }
    }
  }

  fun cleanup(transaction: DecompositionManifestBundleTransaction) {
    Files.deleteIfExists(transaction.marker)
    deleteRecursively(transaction.stagingDirectory)
  }

  fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

  @Suppress("UNCHECKED_CAST")
  fun read(marker: Path, yamlMapper: YAMLMapper): DecompositionManifestBundleTransaction {
    val raw = yamlMapper.readValue(Files.readString(marker), Map::class.java) as Map<String, Any?>
    require(raw["contract_version"] == DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION) {
      "Unsupported decomposition manifest bundle journal contract."
    }
    val stagingDirectory = Path.of(requireNotNull(raw["staging_directory"] as? String))
    val entries = requireNotNull(raw["entries"] as? List<*>)
      .map { rawEntry ->
        val entry = rawEntry as? Map<*, *> ?: error("Malformed decomposition manifest bundle journal entry.")
        DecompositionManifestBundleEntry(
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
    return DecompositionManifestBundleTransaction(marker, stagingDirectory, entries)
  }

  internal fun moveAtomically(source: Path, target: Path) {
    Files.createDirectories(requireNotNull(target.parent))
    try {
      Files.move(
        source,
        target,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}
