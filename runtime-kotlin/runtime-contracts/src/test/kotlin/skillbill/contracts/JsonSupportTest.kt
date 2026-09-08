package skillbill.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSupportTest {
  @Test
  fun `json strings that start with numbers remain strings`() {
    val parsed = requireNotNull(
      JsonSupport.parseObjectOrNull("""{"decisions":["1 fix","2 reject"],"count":2}"""),
    )
    val decoded = requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))

    assertEquals(listOf("1 fix", "2 reject"), decoded["decisions"])
    assertEquals(2, decoded["count"])
  }
}
