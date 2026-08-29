package skillbill.workflow.engine

import java.math.BigDecimal
import java.math.BigInteger

internal fun Any?.asExactIntOrNull(): Int? = when (this) {
  is Byte -> toInt()
  is Short -> toInt()
  is Int -> this
  is Long -> intValueExactOrNull()
  is BigInteger -> intValueExactOrNull()
  is BigDecimal -> intValueExactOrNull()
  is Float -> intValueExactOrNull()
  is Double -> intValueExactOrNull()
  else -> null
}

private fun Long.intValueExactOrNull(): Int? = takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun BigInteger.intValueExactOrNull(): Int? = try {
  intValueExact()
} catch (_: ArithmeticException) {
  null
}

private fun BigDecimal.intValueExactOrNull(): Int? = try {
  intValueExact()
} catch (_: ArithmeticException) {
  null
}

private fun Float.intValueExactOrNull(): Int? {
  if (!isFinite() || toDouble() < Int.MIN_VALUE.toDouble() || toDouble() > Int.MAX_VALUE.toDouble()) {
    return null
  }
  val intValue = toInt()
  return intValue.takeIf { it.toFloat() == this }
}

private fun Double.intValueExactOrNull(): Int? {
  if (!isFinite() || this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) {
    return null
  }
  val intValue = toInt()
  return intValue.takeIf { it.toDouble() == this }
}

internal fun Any?.toStringOrEmpty(): String = this?.toString().orEmpty()
