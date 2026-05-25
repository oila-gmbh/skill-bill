package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 5 — Desktop gateway policy-leakage guard.
 *
 * Desktop data gateways live in `runtime-desktop:core:data` (jvmMain) and must
 * consume **typed** runtime-application result types
 * (`skillbill.ports.scaffold.*.model.*`, `skillbill.ports.validation.model.*`,
 * etc.). Reaching into a typed result's `payload: Map<String, Any?>` from inside
 * a desktop service implementation re-introduces raw-map policy leakage at the
 * adapter boundary that SKILL-52.1 closed. Mappers in dedicated files (e.g.
 * `ValidationSummaryMapper.kt`) are the one allowed exception: they encapsulate
 * the raw-shape translation in a single seam and are explicitly named so the
 * boundary inventory can find them.
 *
 * Detection strategy:
 *  - Walk the jvmMain service source tree.
 *  - Flag any `.payload[`, `.payload.toSelected`, or `.payload as? Map` access
 *    in files that are not whitelisted mapper files.
 *  - Flag any non-private extension on `Map<String, Any?>` declared inside a
 *    mapper file (the historical raw-map mapper signature SKILL-52.2 retires).
 */
class RuntimeDesktopGatewayPolicyTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  private val serviceRoot: Path =
    runtimeRoot.resolve("runtime-desktop/core/data/src/jvmMain/kotlin")

  /**
   * The single directory in jvmMain that is allowed to hold raw-shape mapping
   * logic. Mappers still receive the **typed** runtime-application result type
   * and may read the documented `@OpenBoundaryMap` `payload` field on that
   * type. Service implementations and gateway entry-points must not.
   *
   * SKILL-52.2 subtask 5 (review-fix F-007): the whitelist is anchored to this
   * directory by **relative path**, not by file name. A future regression that
   * happens to name an unrelated file `ValidationSummaryMapper.kt` outside
   * `service/mapper/` therefore does not inherit the exemption.
   */
  private val mapperDirectory: Path =
    serviceRoot.resolve("skillbill/desktop/core/data/service/mapper")

  /**
   * Mapper files (anchored under [mapperDirectory]) that are allowed to read
   * the documented `@OpenBoundaryMap` `payload` field on typed
   * runtime-application result types. Listed by file name only — they are
   * always resolved relative to [mapperDirectory] before any check runs.
   */
  private val mapperFileNames: Set<String> =
    setOf(
      "ValidationSummaryMapper.kt",
      "ScaffoldListResultMapper.kt",
    )

  @Test
  fun `desktop runtime services do not read raw-map payload off typed results`() {
    val mapperPaths = mapperFileNames.map { name -> mapperDirectory.resolve(name).normalize() }.toSet()
    val serviceFiles = Files.walk(serviceRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
        .filter { path -> path.normalize() !in mapperPaths }
        .toList()
    }
    val violations = serviceFiles.flatMap { path -> findRawMapPayloadAccesses(path) }
    assertTrue(
      violations.isEmpty(),
      "Desktop data services must consume typed runtime-application results " +
        "instead of indexing the open-boundary `payload` map. Move raw-shape " +
        "translation into a dedicated mapper file (see ValidationSummaryMapper.kt) " +
        "that takes the typed result as its receiver.\n" +
        violations.joinToString(separator = "\n"),
    )
  }

  private fun findRawMapPayloadAccesses(path: Path): List<String> {
    val source = stripCommentsAndStringLiterals(Files.readString(path))
    val relative = runtimeRoot.relativize(path).toString().replace('\\', '/')
    return BAD_PAYLOAD_PATTERNS.flatMap { pattern ->
      pattern.findAll(source).map { match -> "$relative: raw-map access '${match.value.trim()}'" }.toList()
    }
  }

  @Test
  fun `desktop mappers consume only typed shared runtime-application result types`() {
    val mappers = mapperFileNames.map { name -> mapperDirectory.resolve(name) }
    // SKILL-52.2 subtask 5 (review-fix F-007/F-008): every whitelisted mapper
    // MUST exist on disk. Dropping the `Files.exists(path)` filter ensures a
    // rename or accidental deletion loud-fails here instead of silently
    // emptying the iteration and turning the receiver-shape guard vacuous.
    mappers.forEach { path ->
      assertTrue(
        Files.isRegularFile(path),
        "Whitelisted desktop mapper not found at expected anchored path: " +
          "${runtimeRoot.relativize(path).toString().replace('\\', '/')}",
      )
    }
    val violations = mappers.flatMap { path ->
      val source = Files.readString(path)
      val relative = runtimeRoot.relativize(path).toString().replace('\\', '/')
      PUBLIC_RAW_MAP_RECEIVER_PATTERN.findAll(source).map { match ->
        "$relative: '${match.value.trim()}' — mappers must receive typed results."
      }.toList()
    }
    assertTrue(
      violations.isEmpty(),
      "Desktop mappers must take the typed runtime-application result as the " +
        "receiver and read any documented `@OpenBoundaryMap` `payload` field " +
        "through it.\n" + violations.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `payload-indexing regex catches direct raw-map subscripts but not unrelated payload reads`() {
    // SKILL-52.2 subtask 5 (review-fix F-001): the production
    // `desktop runtime services do not read raw-map payload off typed results`
    // test only proves the `.payload[...]` regex is sound by asserting
    // `emptyList() == emptyList()` against the current source tree. A typo in
    // the pattern (e.g. swapping `\s*` for `\s+`, or dropping the bracket
    // anchor) would silently disable enforcement. This fixture self-test feeds
    // the exact pattern that production uses so a regex regression loud-fails.
    val pattern = BAD_PAYLOAD_PATTERNS[0]
    val mustMatch = listOf(
      "value.payload[\"skills\"]",
      "result.payload[\"issues\"]",
      "report.payload [\"status\"]",
    )
    val mustNotMatch = listOf(
      "payload.size",
      "value.payloadEntry[\"x\"]",
      "payload\"[\"skills\"]",
    )
    assertEquals(
      emptyList(),
      mustMatch.filterNot(pattern::containsMatchIn),
      "`.payload[` regex must catch every known-bad raw-map index expression.",
    )
    assertEquals(
      emptyList(),
      mustNotMatch.filter(pattern::containsMatchIn),
      "`.payload[` regex must not flag known-good payload reads or unrelated identifiers.",
    )
  }

  @Test
  fun `payload-toSelected regex catches chained mapper extensions but not unrelated chains`() {
    // SKILL-52.2 subtask 5 (review-fix F-001).
    val pattern = BAD_PAYLOAD_PATTERNS[1]
    val mustMatch = listOf(
      "result.payload.toSelectedValidationSummary(root)",
      "value.payload.toSelectedFoo()",
    )
    val mustNotMatch = listOf(
      "result.toSelectedValidationSummary(root)",
      "value.payload.size",
      "value.payload.toFoo()",
    )
    assertEquals(
      emptyList(),
      mustMatch.filterNot(pattern::containsMatchIn),
      "`.payload.toSelected*` regex must catch known-bad raw-map chained mapper calls.",
    )
    assertEquals(
      emptyList(),
      mustNotMatch.filter(pattern::containsMatchIn),
      "`.payload.toSelected*` regex must not flag typed-receiver `.toSelected*` calls.",
    )
  }

  @Test
  fun `payload-as-Map cast regex catches raw-map downcasts but not unrelated as-casts`() {
    // SKILL-52.2 subtask 5 (review-fix F-001).
    val pattern = BAD_PAYLOAD_PATTERNS[2]
    val mustMatch = listOf(
      "result.payload as? Map<String, Any?>",
      "value.payload as? Map<*, *>",
    )
    val mustNotMatch = listOf(
      "result.payload as Map<String, Any?>",
      "result.payload.size as? Int",
      "raw as? Map<String, Any?>",
    )
    assertEquals(
      emptyList(),
      mustMatch.filterNot(pattern::containsMatchIn),
      "`.payload as? Map` regex must catch known-bad raw-map downcasts on a typed result's payload.",
    )
    assertEquals(
      emptyList(),
      mustNotMatch.filter(pattern::containsMatchIn),
      "`.payload as? Map` regex must not flag unrelated `as?` casts or non-payload receivers.",
    )
  }

  @Test
  fun `comment and string stripper neutralises false-positive payload mentions`() {
    // SKILL-52.2 subtask 5 (review-fix F-001): KDoc, line comments, and string
    // literals that contain the substring `.payload[` would otherwise be flagged
    // by the raw-map indexing regex. The production scan strips them before
    // matching; this self-test confirms the stripper keeps doing so.
    val source = """
      // example: result.payload["skills"]
      /* block: result.payload["skills"] */
      val literal = "result.payload[\"skills\"]"
    """.trimIndent()
    val stripped = stripCommentsAndStringLiterals(source)
    assertEquals(
      emptyList(),
      BAD_PAYLOAD_PATTERNS.flatMap { pattern ->
        pattern.findAll(stripped).map { it.value }.toList()
      },
      "Comment/string stripper must remove `.payload[` mentions from KDoc, line comments, " +
        "block comments, and string literals so they are not flagged by the raw-map indexing regex.",
    )
  }

  @Test
  fun `public raw-map receiver regex catches non-private extensions but not private helpers`() {
    // SKILL-52.2 subtask 5 (review-fix F-001): the receiver-shape guard for
    // mapper files protects against a regression that would re-introduce a
    // raw-map mapper signature (mapper taking `Map<String, Any?>` instead of a
    // typed runtime-application result). The negative lookahead on `private`
    // must keep private intra-file helpers exempt. A regex regression would
    // either miss public reintroductions or false-positive on the existing
    // `private fun Map<String, Any?>.string(...)` helper.
    val mustMatch = listOf(
      "fun Map<String, Any?>.foo() = Unit",
      "internal fun Map<String, Any?>.bar(): X = TODO()",
      "public fun Map<String, Any?>.baz(): Y = TODO()",
    )
    val mustNotMatch = listOf(
      "private fun Map<String, Any?>.string(key: String): String? = this[key] as? String",
      "private fun Map<*, *>.string(key: String): String? = this[key] as? String",
      "fun Map<String, String>.untyped() = Unit",
    )
    assertEquals(
      emptyList(),
      mustMatch.filterNot(PUBLIC_RAW_MAP_RECEIVER_PATTERN::containsMatchIn),
      "Public raw-map-receiver regex must catch every non-private extension on Map<String, Any?>.",
    )
    assertEquals(
      emptyList(),
      mustNotMatch.filter(PUBLIC_RAW_MAP_RECEIVER_PATTERN::containsMatchIn),
      "Public raw-map-receiver regex must not flag private helpers or other map shapes.",
    )
  }

  /**
   * Strip Kotlin block comments, line comments, raw triple-quoted strings, and
   * single-line string literals so KDoc and inline-comment text that names the
   * legacy raw-map shape does not register as a violation. The strippers run
   * in order so each pass walks already-stripped output, keeping every
   * individual regex small and shifting all parsing complexity into the
   * regex engine rather than nested control flow.
   */
  private fun stripCommentsAndStringLiterals(source: String): String {
    var stripped = BLOCK_COMMENT_PATTERN.replace(source, " ")
    stripped = LINE_COMMENT_PATTERN.replace(stripped, " ")
    stripped = TRIPLE_QUOTED_STRING_PATTERN.replace(stripped, " ")
    stripped = SINGLE_LINE_STRING_PATTERN.replace(stripped, " ")
    return stripped
  }

  private companion object {
    val BAD_PAYLOAD_PATTERNS: List<Regex> =
      listOf(
        // `.payload[...]` — direct raw-map indexing on a typed result.
        Regex("\\.payload\\s*\\["),
        // `.payload.toSelected...` — chained raw-map mapper extension off the payload.
        Regex("\\.payload\\.toSelected[A-Za-z0-9_]+"),
        // `.payload` followed by `as?` cast back into a raw Map — also policy leakage.
        Regex("\\.payload\\s+as\\?\\s*Map"),
      )

    // Mappers may use a private `Map<*, *>.string(...)` helper to walk nested
    // raw-shape entries inside the documented payload. Only catch top-level
    // (non-private) mapping extensions defined on Map<String, Any?> — those
    // would mean the mapper accepts a raw map instead of a typed
    // runtime-application result.
    val PUBLIC_RAW_MAP_RECEIVER_PATTERN: Regex =
      Regex(
        "^(?!.*\\bprivate\\b).*fun\\s+Map<\\s*String\\s*,\\s*Any\\?\\s*>\\s*\\.",
        RegexOption.MULTILINE,
      )

    val BLOCK_COMMENT_PATTERN: Regex = Regex("/\\*[\\s\\S]*?\\*/")
    val LINE_COMMENT_PATTERN: Regex = Regex("//[^\\n]*")
    val TRIPLE_QUOTED_STRING_PATTERN: Regex = Regex("\"\"\"[\\s\\S]*?\"\"\"")
    val SINGLE_LINE_STRING_PATTERN: Regex = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"")
  }
}
