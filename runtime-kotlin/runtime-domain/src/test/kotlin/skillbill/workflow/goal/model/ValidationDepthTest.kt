package skillbill.workflow.goal.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationDepthTest {
  @Test
  fun `fromWire accepts full`() {
    assertEquals(ValidationDepth.FULL, ValidationDepth.fromWire("full"))
  }

  @Test
  fun `fromWire decodes the retired build_only value to full`() {
    assertEquals(ValidationDepth.FULL, ValidationDepth.fromWire("build_only"))
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
