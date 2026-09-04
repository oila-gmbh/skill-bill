package skillbill.scaffold

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.testComposeNativeAgentSource
import skillbill.review.context.model.ReviewPacketConsumerContract
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.scaffold.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.HARBOR_ADDON_SLUG
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_ENTRYPOINT_MARKER
import skillbill.testing.HARBOR_PACK_SLUG
import skillbill.testing.repoRootFromTest
import skillbill.testing.seedHarborAddonPack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposedReviewLaunchPlanTest {
  @Test
  fun `composed kmp plan resolves seven kmp lanes and three kotlin baseline lanes`() {
    val plan = ReviewLaunchPlanPolicy.flatten("kmp", manifests("kmp", "kotlin", "generic"), APPROVED_CODE_REVIEW_AREAS)

    assertEquals(
      mapOf(
        "architecture" to "bill-kmp-code-review-architecture",
        "platform-correctness" to "bill-kmp-code-review-platform-correctness",
        "persistence" to "bill-kmp-code-review-persistence",
        "reliability" to "bill-kmp-code-review-reliability",
        "ui" to "bill-kmp-code-review-ui",
        "ux-accessibility" to "bill-kmp-code-review-ux-accessibility",
        "performance" to "bill-kotlin-code-review-performance",
        "security" to "bill-kmp-code-review-security",
        "testing" to "bill-kotlin-code-review-testing",
        "api-contracts" to "bill-kotlin-code-review-api-contracts",
      ),
      plan.lanes.associate { it.area to it.skillName },
    )
  }

  @Test
  fun `ios composed plan is unchanged by the kmp declarations`() {
    val plan = ReviewLaunchPlanPolicy.flatten("ios", manifests("ios", "generic"), APPROVED_CODE_REVIEW_AREAS)

    assertEquals(
      APPROVED_CODE_REVIEW_AREAS.associateWith { "bill-ios-code-review-$it" },
      plan.lanes.associate { it.area to it.skillName },
    )
    assertTrue(plan.lanes.none { it.skillName.startsWith("bill-kmp-") || it.packSlug == "kmp" })
  }

  @Test
  fun `reported add-on slugs match the content present in the rendered worker`() {
    val pack = seedHarborAddonPack()
    val manifest = loadPlatformPack(pack.packRoot)
    val plan = ReviewLaunchPlanPolicy.flatten(HARBOR_PACK_SLUG, listOf(manifest), setOf("architecture"))
    val reported = plan.lanes.single { lane -> lane.skillName == HARBOR_ARCHITECTURE_WORKER }.addOns.toSet()
    val source = discoverNativeAgentSourceEntries(
      pack.repoRoot.resolve("platform-packs"),
      null,
      listOf(HARBOR_PACK_SLUG),
    ).single { entry -> entry.name == HARBOR_ARCHITECTURE_WORKER }
    val body = testComposeNativeAgentSource(pack.repoRoot, source).body
    val headings = Regex("""### Add-On: ([^ (\n]+)""").findAll(body).map { match -> match.groupValues[1] }.toSet()
    assertEquals(setOf(HARBOR_ADDON_SLUG), reported)
    assertEquals(reported, headings)
    reported.forEach { slug ->
      assertTrue("### Add-On: $slug" in body)
    }
    assertTrue(HARBOR_ENTRYPOINT_MARKER in body)
  }

  @Test
  fun `every composed review worker renders its body from the governed commit-focused source`() {
    val repoRoot = repoRootFromTest()
    val contract = Files.readString(repoRoot.resolve(ReviewPacketConsumerContract.SOURCE_PATH))
    listOf(
      ReviewPacketConsumerContract.AUTHORITATIVE_LAUNCH_CONTRACT,
      ReviewPacketConsumerContract.INTEGRATION_CONTRACT,
    ).forEach { block ->
      assertTrue(block in contract, "The governed source must carry the authoritative block verbatim.")
    }

    val handAuthored = Files.walk(repoRoot.resolve("platform-packs")).use { paths ->
      paths.filter { it.fileName.toString() == "agents.yaml" && "code-review" in it.toString() }.toList()
    }.flatMap { source ->
      val bundle = YAMLMapper().readTree(source.toFile())
      bundle.path("agents").mapNotNull { entry ->
        val name = entry.path("name").asText()
        // A hand-written body cannot be pinned to the governed prose, so it is the one way a
        // rendered worker prompt could still instruct broad discovery or per-commit stepping.
        "$source/$name".takeIf { entry.path("compose").asText() != "governed-content" }
      }
    }

    assertEquals(emptyList(), handAuthored, handAuthored.joinToString("\n"))
  }

  private fun manifests(vararg slugs: String) =
    slugs.map { slug -> loadPlatformPack(repoRootFromTest().resolve("platform-packs/$slug")) }
}
