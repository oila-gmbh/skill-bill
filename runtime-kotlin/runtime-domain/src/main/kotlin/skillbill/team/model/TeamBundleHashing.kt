package skillbill.team.model

import java.security.MessageDigest

object TeamBundleHashing {
  const val BUNDLE_CHECKSUM_PLACEHOLDER =
    "sha256:0000000000000000000000000000000000000000000000000000000000000000"

  fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
  }

  fun contentHash(input: TeamBundleContentHashInput): String {
    val canonical = buildString {
      appendLine("bundle_id=${input.bundleId}")
      appendLine("version=${input.version}")
      appendLine("channel=${input.channel.wireValue}")
      appendLine("created_at=${input.createdAt}")
      appendLine("created_by=${input.createdBy}")
      appendLine("source_repo=${input.sourceRepo}")
      appendLine("source_ref=${input.sourceRef}")
      appendLine("source_commit=${input.sourceCommit.orEmpty()}")
      input.sources.sortedBy { it.path }.forEach { source ->
        append(source.path).append('\u0000').append(source.contentHash).append('\n')
      }
    }
    return sha256(canonical.toByteArray(Charsets.UTF_8))
  }
}

data class TeamBundleContentHashInput(
  val bundleId: String,
  val version: String,
  val channel: TeamBundleChannel,
  val createdAt: String,
  val createdBy: String,
  val sourceRepo: String,
  val sourceRef: String,
  val sourceCommit: String?,
  val sources: List<TeamBundleSourceHash>,
)

data class TeamBundleSourceHash(
  val path: String,
  val contentHash: String,
)
