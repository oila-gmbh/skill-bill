package skillbill.managedskill

import skillbill.managedskill.model.IdentityCapability
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.PathObservation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class MachineSkillGuardedLinkMutation(
  private val inspector: FileSystemMachineSkillMutationInspector,
) {
  fun remove(expected: PathObservation, owned: Boolean) {
    require(owned) { "Refusing to remove a link without Skill Bill ownership proof" }
    requireCurrent(expected)
    Files.delete(expected.path)
  }

  fun replace(expected: PathObservation, target: Path, owned: Boolean) {
    require(owned) { "Refusing to replace a link without Skill Bill ownership proof" }
    requireCurrent(expected)
    val temporary = expected.path.resolveSibling(".${expected.path.fileName}.skill-bill-${System.nanoTime()}")
    Files.createSymbolicLink(temporary, target)
    try {
      requireCurrent(expected)
      Files.move(temporary, expected.path, ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun requireCurrent(expected: PathObservation) {
    require(expected.kind == NoFollowEntryKind.SYMBOLIC_LINK) { "Expected entry is not a symlink" }
    val current = inspector.observe(listOf(expected.path)).single()
    require(
      current.kind == NoFollowEntryKind.SYMBOLIC_LINK &&
        current.rawLinkTarget == expected.rawLinkTarget &&
        current.normalizedLinkTarget == expected.normalizedLinkTarget,
    ) {
      "Symlink changed before destructive operation: ${expected.path}"
    }
    if (expected.identity.capability == IdentityCapability.AVAILABLE) {
      require(current.identity == expected.identity) {
        "Symlink identity changed before destructive operation: ${expected.path}"
      }
    }
  }
}
