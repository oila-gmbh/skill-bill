package skillbill.scaffold.model.command

import skillbill.scaffold.policy.SCAFFOLD_PAYLOAD_VERSION
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.SUPPORTED_SKILL_KINDS

/**
 * SKILL-52.2 subtask 2: re-export of the scaffold wire-payload constants that adapter parsers
 * (CLI / MCP / Desktop) need. The constants themselves live in `skillbill.scaffold.policy` so
 * the legacy filesystem orchestrator continues to reference them by their canonical names;
 * exposing them here under `skillbill.scaffold.model.command.*` keeps adapter imports inside
 * the architecturally-allowed `skillbill.scaffold.model.*` prefix (see
 * `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeImplementationImportRules.kt`).
 *
 * No values are duplicated — these are aliases of the policy constants.
 */
val SCAFFOLD_COMMAND_PAYLOAD_VERSION: String get() = SCAFFOLD_PAYLOAD_VERSION
val SCAFFOLD_COMMAND_KIND_HORIZONTAL: String get() = SKILL_KIND_HORIZONTAL
val SCAFFOLD_COMMAND_KIND_PLATFORM_OVERRIDE_PILOTED: String get() = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
val SCAFFOLD_COMMAND_KIND_PLATFORM_PACK: String get() = SKILL_KIND_PLATFORM_PACK
val SCAFFOLD_COMMAND_KIND_CODE_REVIEW_AREA: String get() = SKILL_KIND_CODE_REVIEW_AREA
val SCAFFOLD_COMMAND_KIND_ADD_ON: String get() = SKILL_KIND_ADD_ON
val SUPPORTED_SCAFFOLD_COMMAND_KINDS: Set<String> get() = SUPPORTED_SKILL_KINDS
