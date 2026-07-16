package skillbill.managedskill

import skillbill.managedskill.model.SymlinkCapability
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemMachineSkillMutationInspectorTest {
  @Test
  fun `symlink preflight result is authoritative`() {
    val home = Files.createTempDirectory("machine-skill-preflight")
    val inspector = FileSystemMachineSkillMutationInspector(
      home,
      emptyList(),
      NativeSymlinkCapabilityProbe { SymlinkCapability.UNAVAILABLE },
    )

    assertEquals(SymlinkCapability.UNAVAILABLE, inspector.symlinkCapability())
  }
}
