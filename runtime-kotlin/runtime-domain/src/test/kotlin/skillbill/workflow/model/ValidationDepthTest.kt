package skillbill.workflow.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationDepthTest {
  @Test
  fun `fromWire accepts build_only and full`() {
    assertEquals(ValidationDepth.BUILD_ONLY, ValidationDepth.fromWire("build_only"))
    assertEquals(ValidationDepth.FULL, ValidationDepth.fromWire("full"))
  }

  @Test
  fun `fromWire rejects unknown values`() {
    assertFailsWith<IllegalArgumentException> {
      ValidationDepth.fromWire("partial")
    }
  }

  @Test
  fun `DEFAULT is full`() {
    assertEquals(ValidationDepth.FULL, ValidationDepth.DEFAULT)
  }
}
