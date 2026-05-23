package skillbill.desktop

import kotlin.test.Test

class DesktopRuntimeClasspathTest {
  @Test
  fun `desktop app runtime classpath includes publish controls domain model`() {
    Class.forName("skillbill.desktop.core.domain.model.PublishLink")
    Class.forName("skillbill.desktop.core.domain.model.PublishLinkKind")
    Class.forName("skillbill.desktop.feature.skillbill.ui.SkillBillFrameKt")
  }
}
