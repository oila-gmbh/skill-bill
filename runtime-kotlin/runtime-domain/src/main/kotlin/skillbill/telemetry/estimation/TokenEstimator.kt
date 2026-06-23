package skillbill.telemetry.estimation

import kotlin.math.ceil

const val TOKEN_BYTES_PER_TOKEN = 4

fun estimateTokens(text: String): Int = ceil(text.encodeToByteArray().size.toDouble() / TOKEN_BYTES_PER_TOKEN).toInt()
