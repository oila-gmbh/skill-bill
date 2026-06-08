package skillbill.config

import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigKey
import skillbill.config.model.RepoLocalConfigResolution
import skillbill.config.model.SpecType
import skillbill.config.model.parseCodeReviewParallelAgent
import skillbill.config.model.parseSpecType
import skillbill.install.model.InstallAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepoLocalConfigModelsTest {
  @Test
  fun `resolution precedence prefers explicit over config over builtin default`() {
    assertEquals("explicit", RepoLocalConfigResolution.resolve("explicit", "config", "builtin"))
    assertEquals("config", RepoLocalConfigResolution.resolve(null, "config", "builtin"))
    assertEquals("builtin", RepoLocalConfigResolution.resolve(null, null, "builtin"))
  }

  @Test
  fun `parseSpecType accepts known values case-insensitively and trims`() {
    assertEquals(SpecType.LOCAL, parseSpecType("local"))
    assertEquals(SpecType.LINEAR, parseSpecType("  LINEAR  "))
  }

  @Test
  fun `parseSpecType rejects unknown or absent values`() {
    assertNull(parseSpecType("remote"))
    assertNull(parseSpecType(null))
  }

  @Test
  fun `parseCodeReviewParallelAgent accepts supported agent ids and the none sentinel`() {
    assertEquals(RepoLocalConfig.NO_PARALLEL_AGENT, parseCodeReviewParallelAgent("none"))
    InstallAgent.supportedIds.forEach { id ->
      assertEquals(id, parseCodeReviewParallelAgent(id))
    }
  }

  @Test
  fun `parseCodeReviewParallelAgent rejects unknown or absent values`() {
    assertNull(parseCodeReviewParallelAgent("not-an-agent"))
    assertNull(parseCodeReviewParallelAgent(null))
  }

  @Test
  fun `builtin defaults match the known-key registry`() {
    val defaults = RepoLocalConfig.defaults()
    assertEquals(SpecType.LOCAL.id, RepoLocalConfigKey.SPEC_TYPE.builtinDefault)
    assertEquals(defaults.specType.id, RepoLocalConfigKey.SPEC_TYPE.builtinDefault)
    assertEquals(defaults.codeReviewParallelAgent, RepoLocalConfigKey.CODE_REVIEW_PARALLEL_AGENT.builtinDefault)
  }
}
