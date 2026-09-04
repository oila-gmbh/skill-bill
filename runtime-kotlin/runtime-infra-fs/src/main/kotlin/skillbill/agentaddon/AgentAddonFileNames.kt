package skillbill.agentaddon

import java.text.Normalizer
import java.util.Locale

internal fun portableFileName(name: String): String =
  Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
