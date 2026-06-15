package skillbill.application.updatecheck

import skillbill.application.model.Semver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemverTest {
  @Test
  fun `parses leading v and orders prerelease identifiers by semver precedence`() {
    assertEquals(Semver(1, 2, 3), Semver.parse("v1.2.3"))
    assertTrue(Semver.parse("1.0.0-alpha")!! < Semver.parse("1.0.0-alpha.1")!!)
    assertTrue(Semver.parse("1.0.0-alpha.1")!! < Semver.parse("1.0.0-alpha.beta")!!)
    assertTrue(Semver.parse("1.0.0-beta.2")!! < Semver.parse("1.0.0-beta.11")!!)
    assertTrue(Semver.parse("1.0.0-rc.1")!! < Semver.parse("1.0.0")!!)
  }

  @Test
  fun `rejects non semver values`() {
    assertNull(Semver.parse(""))
    assertNull(Semver.parse("1.2"))
    assertNull(Semver.parse("latest"))
    assertNull(Semver.parse("1.2.3-"))
  }
}
