package skillbill.desktop.feature.skillbill.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind

class SkillBillRouteDeletionTargetTest {
  @Test
  fun `external add-on deletion target carries source root and filename`() {
    val node = SkillBillTreeItem(
      id = "repo:abc|addon-external:kmp:/home/user/Desktop/Addons/Awesome.md",
      label = "Awesome",
      kind = TreeItemKind.ADD_ON,
      authoredPath = "/home/user/Desktop/Addons/Awesome.md",
      external = true,
      metadata = SkillBillTreeItemMetadata(
        kind = "add-on",
        platform = "kmp",
        externalSourcePath = "/home/user/Desktop/Addons",
      ),
    )

    val target = resolveDeletionTarget(node)

    assertEquals(
      DesktopSkillRemovalTarget.ExternalAddOn(
        sourceRootAbsolutePath = "/home/user/Desktop/Addons",
        platform = "kmp",
        fileName = "Awesome.md",
      ),
      target,
    )
  }
}
