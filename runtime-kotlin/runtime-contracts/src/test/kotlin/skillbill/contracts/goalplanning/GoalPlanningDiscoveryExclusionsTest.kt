package skillbill.contracts.goalplanning

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidGoalPlanningDiscoveryExclusionsSchemaError
import skillbill.error.ShellContentContractException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
  fun `excluded directory names deny at any depth`() {
    listOf("build", ".gradle", "node_modules", ".venv", "vendor", "__pycache__").forEach { name ->
      assertContains(GoalPlanningDiscoveryExclusions.excludedDirectoryNames, name)
    }
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/runtime-contracts/build/classes/agent"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("tooling/web/node_modules/pkg/agent/history.md"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/.gradle/caches"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("services/api/.venv/lib/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/buildSrc/agent/history.md"))
  }

  @Test
  fun `prefix matching is segment aware`() {
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("platform-packsX/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded(""))
  }

  @Test
  fun `interior dot segments cannot dress an excluded root up as an allowed one`() {
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/../platform-packs/kmp/agent/history.md"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("./platform-packs/./kmp/agent/history.md"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("a/b/../../build/agent/history.md"))
    assertFalse(GoalPlanningDiscoveryExclusions.isExcluded("platform-packs/../runtime-kotlin/agent/history.md"))
  }

  @Test
  fun `a path escaping the repository root is denied rather than allowed`() {
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("../outside/agent/history.md"))
    assertTrue(GoalPlanningDiscoveryExclusions.isExcluded("runtime-kotlin/../../outside/agent/history.md"))
  }

  @Test
  fun `an unknown key is rejected rather than silently ignored`() {
    val unknown = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(
        contract(extra = "excluded_paths:\n  - \"secrets/\"\n"),
      )
    }
    assertContains(unknown.message.orEmpty(), "unknown keys: excluded_paths")
  }

  @Test
  fun `a contract without excluded directory names loud fails`() {
    val missing = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(
        "contract_version: \"${GoalPlanningDiscoveryExclusions.CONTRACT_VERSION}\"\n" +
          "excluded_roots:\n  - \"platform-packs/\"\n",
      )
    }
    assertContains(missing.message.orEmpty(), "no excluded_directory_names")

    val nested = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(directoryNames = listOf("a/b")))
    }
    assertContains(nested.message.orEmpty(), "bare directory name")
  }

  @Test
  fun `every shipped root is a normalized repo relative prefix`() {
    assertTrue(GoalPlanningDiscoveryExclusions.excludedRoots.isNotEmpty())
    assertTrue(GoalPlanningDiscoveryExclusions.excludedRoots.all { root -> root.endsWith("/") })
    assertFalse(GoalPlanningDiscoveryExclusions.excludedRoots.any { root -> root.startsWith("/") })
  }

  @Test
  fun `malformed contracts loud fail instead of degrading to allow all`() {
    val emptyRoots = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(roots = emptyList()))
    }
    assertContains(emptyRoots.message.orEmpty(), "no excluded_roots")

    val wrongVersion = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(version = "9.9"))
    }
    assertContains(wrongVersion.message.orEmpty(), "unsupported")

    assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse("- not-a-mapping\n")
    }

    val absoluteRoot = assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(roots = listOf("/etc/")))
    }
    assertContains(absoluteRoot.message.orEmpty(), "normalized repo-relative prefix")

    assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(roots = listOf("../escape/")))
    }

    assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
      GoalPlanningDiscoveryExclusions.parse(contract(roots = listOf("no-trailing-slash")))
    }
  }

  // The declared supertype is the assertion: the MCP server, CLI, and quarantine classifier all match
  // on ShellContentContractException, so this fails to compile if the error stops extending it.
  @Test
  fun `contract failures are governed contract exceptions the runtime already classifies`() {
    val failure: ShellContentContractException =
      assertFailsWith<InvalidGoalPlanningDiscoveryExclusionsSchemaError> {
        GoalPlanningDiscoveryExclusions.parse("- not-a-mapping\n")
      }
    assertContains(failure.message.orEmpty(), "not a YAML mapping")
  }

  // Parity is constant vs canonical file vs schema. Reading only the staged copy pins a snapshot.
  @Test
  fun `contract version is pinned across the constant, the canonical file, and its schema`() {
    val repoRoot = assertNotNull(repoRoot(), "contract parity requires the checked-in repository")
    val canonical = repoRoot.resolve(GoalPlanningDiscoveryExclusions.CONTRACT_FILE)
    val schema = repoRoot.resolve(GoalPlanningDiscoveryExclusions.SCHEMA_FILE)
    assertTrue(Files.isRegularFile(canonical), "missing ${GoalPlanningDiscoveryExclusions.CONTRACT_FILE}")
    assertTrue(Files.isRegularFile(schema), "missing ${GoalPlanningDiscoveryExclusions.SCHEMA_FILE}")

    val canonicalDocument = Yaml().load<Map<String, Any?>>(Files.readString(canonical))
    assertEquals(GoalPlanningDiscoveryExclusions.CONTRACT_VERSION, canonicalDocument["contract_version"])

    val schemaDocument = Yaml().load<Map<String, Any?>>(Files.readString(schema))
    val properties = assertNotNull(schemaDocument["properties"] as? Map<*, *>)
    val versionProperty = assertNotNull(properties["contract_version"] as? Map<*, *>)
    assertEquals(GoalPlanningDiscoveryExclusions.CONTRACT_VERSION, versionProperty["const"])
    assertEquals(false, schemaDocument["additionalProperties"], "the contract schema must stay closed")
    assertEquals(
      listOf("contract_version", "excluded_roots", "excluded_directory_names"),
      schemaDocument["required"],
    )

    // The staged classpath copy must be the canonical file, not a drifted snapshot of it.
    assertEquals(
      GoalPlanningDiscoveryExclusions.parse(Files.readString(canonical)).roots,
      GoalPlanningDiscoveryExclusions.excludedRoots,
    )
    assertEquals(
      GoalPlanningDiscoveryExclusions.parse(Files.readString(canonical)).directoryNames,
      GoalPlanningDiscoveryExclusions.excludedDirectoryNames,
    )
  }

  private fun contract(
    version: String = GoalPlanningDiscoveryExclusions.CONTRACT_VERSION,
    roots: List<String> = listOf("platform-packs/"),
    directoryNames: List<String> = listOf("build"),
    extra: String = "",
  ): String = buildString {
    append("contract_version: \"$version\"\n")
    append("excluded_roots:")
    if (roots.isEmpty()) append(" []\n") else roots.forEach { root -> append("\n  - \"$root\"") }
    if (roots.isNotEmpty()) append("\n")
    append("excluded_directory_names:")
    if (directoryNames.isEmpty()) append(" []\n") else directoryNames.forEach { name -> append("\n  - \"$name\"") }
    if (directoryNames.isNotEmpty()) append("\n")
    append(extra)
  }

  private fun repoRoot(): Path? {
    var candidate: Path? = Path.of("").toAbsolutePath()
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve(".git")) || Files.isRegularFile(candidate.resolve(".git"))) {
        return candidate
      }
      candidate = candidate.parent
    }
    return null
  }
}
