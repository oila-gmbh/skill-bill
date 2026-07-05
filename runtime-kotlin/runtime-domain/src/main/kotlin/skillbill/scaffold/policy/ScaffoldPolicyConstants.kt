package skillbill.scaffold.policy

import skillbill.error.RetiredScaffoldKindError
import skillbill.scaffold.policy.model.PlatformPackPreset

/**
 * SKILL-52.1 subtask 2: pure-policy constants extracted from `runtime-infra-fs` scaffold service.
 *
 * These values are configuration of the scaffold contract — they have no IO and no platform-pack
 * filesystem layout knowledge. The pure-policy callers in `runtime-domain` are the source of
 * truth; the adapter wrappers in `runtime-infra-fs` and the `ScaffoldCatalog` projection in
 * `runtime-infra-fs` re-export these so wizards and gateways stay consistent.
 */

/** Wire-version sentinel for scaffold payloads (CLI / MCP `scaffold_payload_version`). */
const val SCAFFOLD_PAYLOAD_VERSION: String = "1.0"

const val SKILL_KIND_HORIZONTAL: String = "horizontal"
const val SKILL_KIND_PLATFORM_OVERRIDE_PILOTED: String = "platform-override-piloted"
const val SKILL_KIND_PLATFORM_PACK: String = "platform-pack"
const val SKILL_KIND_CODE_REVIEW_AREA: String = "code-review-area"
const val SKILL_KIND_ADD_ON: String = "add-on"

/**
 * Closed set of legacy scaffold kinds still understood by compatibility internals and typed
 * models. New creation surfaces must use [ACTIVE_CREATION_SKILL_KINDS] instead.
 */
val SUPPORTED_SKILL_KINDS: Set<String> =
  setOf(
    SKILL_KIND_HORIZONTAL,
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    SKILL_KIND_PLATFORM_PACK,
    SKILL_KIND_CODE_REVIEW_AREA,
    SKILL_KIND_ADD_ON,
  )

/** Kinds that can be created through current scaffold entry points. */
val ACTIVE_CREATION_SKILL_KINDS: Set<String> =
  setOf(SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_PACK, SKILL_KIND_ADD_ON)

/** Retired partial scaffold creation kinds. Legacy source handling remains supported. */
val RETIRED_PARTIAL_SCAFFOLD_KINDS: Set<String> =
  setOf(SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, SKILL_KIND_CODE_REVIEW_AREA)

val RETIRED_PLATFORM_OVERRIDE_KIND_ALIASES: Set<String> =
  setOf("platform-override", SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, "override")

val RETIRED_CODE_REVIEW_AREA_KIND_ALIASES: Set<String> =
  setOf(SKILL_KIND_CODE_REVIEW_AREA, "area", "specialist")

val RETIRED_PARTIAL_SCAFFOLD_KIND_ALIASES: Set<String> =
  RETIRED_PLATFORM_OVERRIDE_KIND_ALIASES + RETIRED_CODE_REVIEW_AREA_KIND_ALIASES

/** Kinds that orchestrate specialist subagents (and therefore accept `subagent_specialists`). */
val ORCHESTRATOR_KINDS_FOR_SUBAGENTS: Set<String> =
  setOf(SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, SKILL_KIND_PLATFORM_PACK)

/** Naming pattern accepted for specialist subagent names. */
val SUBAGENT_NAME_PATTERN: Regex = Regex("^[a-z][a-z0-9-]*$")

/** Approved set of platform-pack code-review areas. */
val APPROVED_CODE_REVIEW_AREAS: Set<String> =
  setOf(
    "architecture",
    "performance",
    "platform-correctness",
    "security",
    "testing",
    "api-contracts",
    "persistence",
    "reliability",
    "ui",
    "ux-accessibility",
  )

/**
 * Built-in descriptor table keyed by platform slug. The slugs MUST match the keys of the simpler
 * wizard projection that `ScaffoldCatalog` (in `runtime-infra-fs`) exposes; the runtime is the
 * single source of truth for both. `PlatformPackPreset` lives in
 * `skillbill.scaffold.policy.model` per the domain `model` rule.
 */
val PLATFORM_PACK_PRESET_DESCRIPTORS: Map<String, PlatformPackPreset> =
  mapOf(
    "java" to PlatformPackPreset(
      displayName = "Java",
      strongSignals = listOf("pom.xml", "build.gradle", "src/main/java"),
      tieBreakers = listOf("Prefer Java when Maven metadata or Java source markers dominate generic JVM signals."),
    ),
    "python" to PlatformPackPreset(
      displayName = "Python",
      strongSignals = listOf(
        "pyproject.toml",
        "requirements.txt",
        "setup.py",
        "setup.cfg",
        "Pipfile",
        "poetry.lock",
        "uv.lock",
        "tox.ini",
        "pytest.ini",
        ".py",
        "*.py",
      ),
      tieBreakers = listOf(
        "Prefer Python when pyproject metadata, dependency lockfiles, or first-party .py source files " +
          "dominate the changed product surface.",
        "Do not prefer Python for mixed repositories where Python appears only as tooling, one-off " +
          "scripts, generated clients, or CI glue around another dominant stack.",
        "Exclude virtual environments, site-packages, build and dist outputs, generated protobuf or " +
          "OpenAPI clients, and vendored dependency trees from dominance scoring.",
      ),
    ),
  )

/**
 * Wizard-facing platform-pack preset projection (slug -> displayName) derived from
 * [PLATFORM_PACK_PRESET_DESCRIPTORS]. Kept as a stable, immutable map so the desktop wizard
 * catalog can re-export it verbatim without depending on the richer descriptor type.
 */
val PLATFORM_PACK_PRESETS: Map<String, String> =
  PLATFORM_PACK_PRESET_DESCRIPTORS.mapValues { (_, preset) -> preset.displayName }

fun isRetiredPartialScaffoldKindAlias(kind: String): Boolean =
  kind.trim().lowercase() in RETIRED_PARTIAL_SCAFFOLD_KIND_ALIASES

fun retiredPartialScaffoldKindError(kind: String): RetiredScaffoldKindError = RetiredScaffoldKindError(
  "Scaffold kind '$kind' is retired for new partial scaffold creation. " +
    "Create a full platform pack with kind '$SKILL_KIND_PLATFORM_PACK', or edit/remove existing " +
    "platform-pack content through normal authoring and removal commands instead of creating " +
    "partial scaffold pieces.",
)

fun rejectRetiredPartialScaffoldKind(kind: String): Nothing = throw retiredPartialScaffoldKindError(kind)
