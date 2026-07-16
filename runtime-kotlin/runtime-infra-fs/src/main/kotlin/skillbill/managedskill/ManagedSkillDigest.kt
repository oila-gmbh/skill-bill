package skillbill.managedskill

import java.security.MessageDigest

internal fun digestBytes(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
