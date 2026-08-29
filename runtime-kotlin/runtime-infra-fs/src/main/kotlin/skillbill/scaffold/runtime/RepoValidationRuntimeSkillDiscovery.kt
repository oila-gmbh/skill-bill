package skillbill.scaffold.runtime

import skillbill.error.ShellContentContractException
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.loadPlatformPack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

internal fun discoverSkillFiles(root: Path, issues: MutableList<String>): Map<String, Path> {
    val skillsDir = root.resolve("skills")
    if (!skillsDir.isDirectory()) {
      issues += "skills/ directory is missing"
      return emptyMap()
    }
    val found = linkedMapOf<String, Path>()
    val seenContent = mutableSetOf<Path>()
    Files.walk(skillsDir).use { stream ->
      stream
        .filter { it.fileName.toString() == "content.md" }
        .sorted()
        .forEach { contentFile ->
          seenContent.add(contentFile.parent)
          val skillName = contentFile.parent.name
          val previous = found.putIfAbsent(skillName, contentFile)
          if (previous != null) {
            issues += "Duplicate skill directory name '$skillName' found at ${previous.parent.relativeTo(
              root,
            )} and ${contentFile.parent.relativeTo(root)}"
          }
        }
    }
    Files.walk(skillsDir).use { stream ->
      stream
        .filter { it.fileName.toString() == "SKILL.md" }
        .sorted()
        .forEach { skillFile ->
          val parent = skillFile.parent
          if (parent !in seenContent) {
            issues += "${parent.relativeTo(root)}: SKILL.md found without sibling content.md " +
              "(authored content.md required since SKILL-40 subtask 1)"
          }
        }
    }
    if (found.isEmpty()) {
      issues += "No skills were found under skills/"
    }
    return found
  }

internal fun discoverPlatformPackSkillFiles(root: Path, issues: MutableList<String>): Map<String, Path> {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) {
      return emptyMap()
    }
    val found = linkedMapOf<String, Path>()
    val seenContent = mutableSetOf<Path>()
    Files.walk(packsRoot).use { stream ->
      stream
        .filter { it.fileName.toString() == "content.md" && it.parent.name.startsWith("bill-") }
        .sorted()
        .forEach { contentFile ->
          seenContent.add(contentFile.parent)
          found[contentFile.parent.name] = contentFile
        }
    }
    Files.walk(packsRoot).use { stream ->
      stream
        .filter { it.fileName.toString() == "SKILL.md" && it.parent.name.startsWith("bill-") }
        .sorted()
        .forEach { skillFile ->
          val parent = skillFile.parent
          if (parent !in seenContent) {
            issues += "${parent.relativeTo(root)}: SKILL.md found without sibling content.md " +
              "(authored content.md required since SKILL-40 subtask 1)"
          }
        }
    }
    return found
  }

internal fun validatePlatformPacks(root: Path, issues: MutableList<String>): Int {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) {
      return 0
    }
    var validCount = 0
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .sorted()
        .forEach { packRoot ->
          try {
            loadPlatformPack(packRoot, enforceGovernedReviewStructure = true)
            validCount += 1
          } catch (error: ShellContentContractException) {
            issues += "platform-packs/${packRoot.name}: ${error.message}"
          }
        }
    }
    return validCount
  }

internal fun discoverPortableReviewSkills(root: Path): Set<String> {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) {
      return emptySet()
    }
    val reviewSkills = linkedSetOf<String>()
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .sorted()
        .forEach { packRoot ->
          reviewSkills += runCatching { loadPlatformManifest(packRoot).declaredCodeReviewSkillNames() }
            .getOrDefault(emptySet())
        }
    }
    return reviewSkills
  }
internal fun discoverAllAddonFiles(root: Path): List<Path> {
    val containers = listOf(root.resolve("skills"), root.resolve("platform-packs"))
    return containers.filter(Path::isDirectory).flatMap { container ->
      Files.walk(container).use { stream ->
        stream
          .filter {
            it.isRegularFile() &&
              it.fileName.toString().endsWith(".md") &&
              "addons" in it.relativeTo(container).map(Path::toString)
          }
          .toList()
      }
    }.sorted()
  }
