package skillbill.install.identity

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidSkillContentIdentityError
import skillbill.error.SkillContentIdentityMismatchError
import skillbill.scaffold.validation.parseSkillFrontmatter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal const val SKILL_CONTENT_IDENTITY_CONTRACT_VERSION = "0.1"
internal const val SKILL_CONTENT_IDENTITY_FILENAME = ".content-identity"

/**
 * Compact, body-free identity for an authored skill. The full digest is intentionally separate
 * from the short install-cache hash: the latter is a directory key, not an equality proof.
 */
internal data class SkillContentIdentity(
  val canonicalSourceIdentity: String,
  val exactContentSha256: String,
  val normalizedMetadata: Map<String, String>,
) {
  init {
    require(canonicalSourceIdentity.isNotBlank()) { "canonicalSourceIdentity is required." }
    require(exactContentSha256.matches(SHA256_PATTERN)) { "exactContentSha256 must be a SHA-256 digest." }
    require(normalizedMetadata.keys.all { it.isNotBlank() }) { "normalizedMetadata keys must not be blank." }
  }

  /** The compact form is suitable for diagnostics and contains no skill body. */
  fun compact(): String = JsonSupport.mapToJsonString(toMap())

  fun toMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to SKILL_CONTENT_IDENTITY_CONTRACT_VERSION,
    "canonical_source_identity" to canonicalSourceIdentity,
    "exact_content_sha256" to exactContentSha256,
    "normalized_metadata" to normalizedMetadata.toSortedMap(),
  )

  companion object {
    fun fromSource(sourceSkillDir: Path): SkillContentIdentity {
      val source = sourceSkillDir.toAbsolutePath().normalize()
      val contentFile = source.resolve("content.md")
      if (!Files.isRegularFile(contentFile)) {
        throw InvalidSkillContentIdentityError(source.toString(), "content.md is missing")
      }
      val content = Files.readAllBytes(contentFile)
      val metadata = runCatching {
        parseSkillFrontmatter(content.toString(StandardCharsets.UTF_8))
      }.getOrElse { error ->
        throw InvalidSkillContentIdentityError(
          source.toString(),
          "content.md frontmatter could not be normalized",
          error,
        )
      }
      if (metadata.isEmpty()) {
        throw InvalidSkillContentIdentityError(source.toString(), "content.md frontmatter is missing")
      }
      val canonical = runCatching { source.toRealPath().toString() }.getOrElse { source.toString() }
      return SkillContentIdentity(canonical, sha256(content), metadata.toSortedMap())
    }

    /** Reads only the compact marker; it never replays the installed SKILL.md or content.md body. */
    fun fromInstalled(stagingDir: Path): SkillContentIdentity {
      val marker = stagingDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME)
      if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
        throw InvalidSkillContentIdentityError(stagingDir.toString(), "identity marker is missing")
      }
      val parsed = JsonSupport.parseObjectOrNull(Files.readString(marker, StandardCharsets.UTF_8))
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
        ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "identity marker is not an object")
      val expectedFields = setOf(
        "contract_version",
        "canonical_source_identity",
        "exact_content_sha256",
        "normalized_metadata",
      )
      if (parsed.keys != expectedFields) {
        throw InvalidSkillContentIdentityError(stagingDir.toString(), "identity marker fields are invalid")
      }
      if (parsed["contract_version"] != SKILL_CONTENT_IDENTITY_CONTRACT_VERSION) {
        throw InvalidSkillContentIdentityError(stagingDir.toString(), "identity marker contract version is invalid")
      }
      val source = parsed["canonical_source_identity"] as? String
        ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "canonical source identity is missing")
      val digest = parsed["exact_content_sha256"] as? String
        ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "exact content digest is missing")
      val metadata = (parsed["normalized_metadata"] as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) ->
          (key as? String ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "metadata key is invalid")) to
            (value as? String ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "metadata value is invalid"))
        }
        ?: throw InvalidSkillContentIdentityError(stagingDir.toString(), "normalized metadata is missing")
      return runCatching { SkillContentIdentity(source, digest, metadata.toSortedMap()) }
        .getOrElse { error ->
          throw InvalidSkillContentIdentityError(stagingDir.toString(), error.message ?: "identity values are invalid", error)
        }
    }

    fun requireMatch(supplied: SkillContentIdentity, installed: SkillContentIdentity) {
      if (supplied != installed) {
        throw SkillContentIdentityMismatchError(supplied.compact(), installed.compact())
      }
    }

    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}

internal fun suppliedSkillContentIdentity(sourceSkillDir: Path): SkillContentIdentity =
  SkillContentIdentity.fromSource(sourceSkillDir)

internal fun installedSkillContentIdentity(stagingDir: Path): SkillContentIdentity =
  SkillContentIdentity.fromInstalled(stagingDir)

internal fun requireMatchingSkillContentIdentity(
  supplied: SkillContentIdentity,
  installed: SkillContentIdentity,
) = SkillContentIdentity.requireMatch(supplied, installed)
