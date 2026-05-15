package skillbill.desktop.feature.skillbill.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillBillFrameCompareUrlTest {
  @Test
  fun `compare URL row exposes parameterized content description`() {
    val url = "https://github.com/acme/repo/compare/main...feature"

    assertEquals("Open compare URL: $url", compareUrlRowContentDescription(url))
  }

  @Test
  fun `compare URL row shows copied affordance only for matching copied key`() {
    val url = "https://github.com/acme/repo/compare/main...feature"
    val copiedKey = url
    val unrelatedCopiedKey = "different"

    assertEquals("Copied", compareUrlActionLabel(showCopied = copiedKey == url, showOpened = false))
    assertEquals("open", compareUrlActionLabel(showCopied = unrelatedCopiedKey == url, showOpened = false))
  }

  @Test
  fun `compare URL row shows opened affordance after successful launch`() {
    assertEquals("Opened in browser", compareUrlActionLabel(showCopied = false, showOpened = true))
  }
}
