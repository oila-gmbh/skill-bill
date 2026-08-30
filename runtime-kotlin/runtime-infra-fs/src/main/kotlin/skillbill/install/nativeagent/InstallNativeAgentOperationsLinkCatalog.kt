package skillbill.install.nativeagent

import skillbill.scaffold.platformpack.loadPlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun stageReviewCatalogPacks(platformPacksRoot: Path, selectedPlatforms: List<String>?, staging: Path) {
  val selected = selectedPlatforms?.toSet()
  val desiredPacks = Files.list(platformPacksRoot).use { packs ->
    packs.filter(Files::isDirectory)
      .filter { selected == null || it.fileName.toString() in selected }
      .toList()
  }
  desiredPacks.forEach { source -> stageReviewCatalogPack(source, staging) }
}

private fun stageReviewCatalogPack(source: Path, staging: Path) {
  val stagedPack = staging.resolve(source.fileName.toString())
  val manifest = loadPlatformManifest(source)
  val runtimeFiles = buildList {
    add(source.resolve("platform.yaml"))
    manifest.declaredFiles.baseline?.let(::add)
    addAll(manifest.declaredFiles.areas.values)
    val declaredAddons = manifest.addonUsage.flatMap { it.addons } +
      manifest.featureAddonUsage.flatMap { it.addons }
    declaredAddons.forEach { addon ->
      add(source.resolve("addons").resolve(addon.entrypoint))
      addon.companionPointers.forEach { pointer -> add(source.resolve("addons").resolve(pointer)) }
    }
  }.distinct()
  runtimeFiles.forEach { path ->
    val relative = source.relativize(path.toAbsolutePath().normalize())
    require(!relative.startsWith("..")) {
      "Installed review catalog path escapes platform pack '${manifest.slug}'."
    }
    val target = stagedPack.resolve(relative).normalize()
    require(target.startsWith(staging)) { "Installed review catalog path escapes its cache root." }
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
      "Installed review catalog source must be a regular manifest-declared file: '$path'."
    }
    target.parent?.let(Files::createDirectories)
    Files.copy(path, target, REPLACE_EXISTING)
  }
}

internal fun journalReviewCatalogSwap(catalogRoot: Path, staging: Path, journal: ProviderMutationJournal) {
  if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
    Files.walk(catalogRoot).use { paths -> paths.sorted().forEach(journal::beforeMutation) }
  }
  Files.walk(staging).use { paths ->
    paths.sorted().forEach { staged ->
      journal.beforeMutation(catalogRoot.resolve(staging.relativize(staged)))
    }
  }
}

internal fun swapReviewCatalogIntoPlace(catalogRoot: Path, staging: Path, superseded: Path) {
  if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
    Files.move(catalogRoot, superseded, ATOMIC_MOVE)
  }
  Files.move(staging, catalogRoot, ATOMIC_MOVE)
  deleteRecursively(superseded)
}
