package skillbill.contracts.goalplanning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalPlanningDiscoveryExclusionsTest {
  @Test
  fun `shipped contract is staged on the classpath and denies platform pack agent memory`() {
    assertContains(GoalPlanningDiscoveryExclusions.excludedRoots, "platform-packs/")
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("platform-packs/kmp/agent/history.md"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("platform-packs"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("build/generated/agent/history.md"))
  }

  @Test
  fun `prefix matching is segment aware`() {
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("platform-packsX/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded(""))
  }

  @Test
  fun `every shipped root is a normalized repo relative prefix`() {
    assertTrue(GoalPlanningDiscoveryExclusions.excludedRoots.isNotEmpty())
    assertTrue(GoalPlanningDiscoveryExclusions.excludedRoots.all { root -> root.endsWith("/") })
    assertFalse(GoalPlanningDiscoveryExclusions.excludedRoots.any { root -> root.startsWith("/") })
  }

  @Test
  fun `malformed contracts loud fail instead of degrading to allow all`() {
    val emptyRoots = assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("contract_version: \"0.1\"\nexcluded_roots: []\n")
    }
    assertContains(emptyRoots.message.orEmpty(), "no excluded_roots")

    val wrongVersion = assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("contract_version: \"9.9\"\nexcluded_roots:\n  - \"platform-packs/\"\n")
    }
    assertContains(wrongVersion.message.orEmpty(), "unsupported")

    assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("- not-a-mapping\n")
    }

    val absoluteRoot = assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("contract_version: \"0.1\"\nexcluded_roots:\n  - \"/etc/\"\n")
    }
    assertContains(absoluteRoot.message.orEmpty(), "normalized repo-relative prefix")

    assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("contract_version: \"0.1\"\nexcluded_roots:\n  - \"../escape/\"\n")
    }

    assertFailsWith<GoalPlanningDiscoveryExclusionsException> {
      GoalPlanningDiscoveryExclusions.parse("contract_version: \"0.1\"\nexcluded_roots:\n  - \"no-trailing-slash\"\n")
    }
  }

  @Test
  fun `contract version constant matches the shipped contract`() {
    assertEquals("0.1", GoalPlanningDiscoveryExclusions.CONTRACT_VERSION)
  }
}
