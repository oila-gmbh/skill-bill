package skillbill.contracts

import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import java.util.Locale

/**
 * networknt renders every violation message through `MessageFormat` under the JVM default locale, so the
 * same rejection reads differently per host: an `en_DE` machine regroups 4096 as "4.096", and a `de_DE`
 * one translates the sentence outright. These messages are governed text — they are echoed verbatim into
 * the retry prompt a producing agent must act on — so a regrouped digit reads as a different constraint
 * and a translated one as no constraint at all. Pin the rendering to English so it is host-independent.
 *
 * `pathType` is restated because supplying any config at all switches the factory off its LEGACY default
 * and onto JSON_POINTER. LEGACY and JSON_PATH agree on ordinary paths but not on keys holding a `/`:
 * LEGACY emits `$.pointers.code-review/bill-x[0].name`, JSON_PATH bracket-quotes the key. Callers parse
 * these locations back into field paths, so LEGACY is the one that keeps reported locations unchanged.
 */
internal val LOCALE_STABLE_SCHEMA_CONFIG: SchemaValidatorsConfig =
  SchemaValidatorsConfig.builder().locale(Locale.ENGLISH).pathType(PathType.LEGACY).build()
