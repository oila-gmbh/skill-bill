package skillbill.telemetry

const val STATE_DIR_ENVIRONMENT_KEY: String = "SKILL_BILL_STATE_DIR"
const val CONFIG_ENVIRONMENT_KEY: String = "SKILL_BILL_CONFIG_PATH"
const val TELEMETRY_ENABLED_ENVIRONMENT_KEY: String = "SKILL_BILL_TELEMETRY_ENABLED"
const val TELEMETRY_LEVEL_ENVIRONMENT_KEY: String = "SKILL_BILL_TELEMETRY_LEVEL"
const val TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: String = "SKILL_BILL_TELEMETRY_PROXY_URL"
const val TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY: String = "SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN"
const val INSTALL_ID_ENVIRONMENT_KEY: String = "SKILL_BILL_INSTALL_ID"
const val TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY: String = "SKILL_BILL_TELEMETRY_BATCH_SIZE"
const val DEFAULT_TELEMETRY_PROXY_URL: String = "https://skill-bill-telemetry-proxy.skillbill.workers.dev"
const val DEFAULT_TELEMETRY_BATCH_SIZE: Int = 50
const val TELEMETRY_PROXY_CONTRACT_VERSION: String = "1"
const val HTTP_OK_MIN: Int = 200
const val HTTP_OK_MAX: Int = 299

val telemetryLevels: List<String> = listOf("off", "anonymous", "full")
val remoteStatsWorkflows: List<String> = listOf("bill-feature-implement", "bill-feature-verify")
