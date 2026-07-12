package dev.skillbill.runtime.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object RuntimeImageLicense {
  fun stage(source: Path, destinations: List<Path>) {
    require(Files.isRegularFile(source)) { "Repository LICENSE is missing at $source." }
    destinations.forEach { destination ->
      Files.createDirectories(destination.parent)
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  fun matches(source: Path, candidate: Path): Boolean =
    Files.isRegularFile(source) && Files.isRegularFile(candidate) && Files.mismatch(source, candidate) == -1L
}
