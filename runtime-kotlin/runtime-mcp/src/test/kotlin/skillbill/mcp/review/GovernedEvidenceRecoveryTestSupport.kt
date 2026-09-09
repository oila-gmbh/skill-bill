package skillbill.mcp.review

import skillbill.contracts.JsonSupport
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.launcher.review.GovernedReviewEvidenceEndpoint
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceCoordinates
import skillbill.ports.review.model.ReviewEvidenceSource
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewRevision
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

internal fun recoveryBinding(root: Path, merged: Boolean): ReviewEvidenceBrokerBinding {
  val shared = ReviewChangedHunk("Shared.kt", 1, 1, 1, 1, "-old\n+shared", commitScope = "first")
  val second = ReviewChangedHunk("Shared.kt", 1, 1, 1, 1, "-shared\n+second", commitScope = "second")
  val unique = ReviewChangedHunk("Unique.kt", 1, 1, 1, 1, "-old\n+unique")
  Files.writeString(root.resolve("Shared.kt"), "whole shared file\n")
  Files.writeString(root.resolve("Unique.kt"), "")
  val first = assignment("first", listOf(shared, second))
  val other = assignment("other", listOf(shared, unique))
  val hunks = if (merged) listOf(shared, second, unique) else listOf(shared, second)
  val union = first.copy(assignedPaths = hunks.map { it.path }.distinct(), assignedHunks = hunks.map { it.hunkId })
  val sources = listOf(
    ReviewEvidenceSource(
      first,
      "rubric-first",
      coordinates = checkpoint(
        root,
        hunks.map { it.path },
      ),
    ),
  ) +
    if (merged) {
      listOf(
        ReviewEvidenceSource(
          other,
          "rubric-other",
          coordinates = checkpoint(
            root,
            hunks.map { it.path },
          ),
        ),
      )
    } else {
      emptyList()
    }
  return ReviewEvidenceBrokerBinding(
    root,
    union,
    "rubric-first",
    ReviewContextBudgetPolicy.DEFAULT,
    projectedHunks = hunks,
    sources = sources,
  )
}

internal fun discoverEntries(client: Client): List<Map<String, Any?>> {
  val entries = mutableListOf<Map<String, Any?>>()
  var cursor: String? = null
  do {
    val arguments = linkedMapOf<String, Any?>("operation" to "discover", "page_size" to 1)
    cursor?.let { arguments["cursor"] = it }
    val page = payload(client.exchange(call(arguments)))
    entries += requireNotNull(JsonSupport.anyToStringAnyMapList(page["entries"]))
    cursor = page["next_cursor"] as? String
  } while (cursor != null)
  return entries
}

internal fun checkpoint(root: Path, paths: List<String>) = ReviewEvidenceCoordinates.Checkpoint(
  ReviewEvidenceCoordinates.Checkpoint.Kind.WORKTREE,
  FileSystemDiffResolver().reviewWorktreeFileIdentities(root, paths.distinct()),
)

internal fun git(root: Path, vararg args: String): String {
  val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText()
  assertEquals(0, process.waitFor(), output)
  return output
}

internal fun read(entry: Map<String, Any?>): Map<String, Any?> = mapOf(
  "operation" to "read",
  "requests" to listOf(entry.filterKeys { it in setOf("path", "selector", "expansion_id") }),
)

internal fun assignment(lane: String, hunks: List<ReviewChangedHunk>): ReviewAssignment {
  val paths = hunks.map { it.path }.distinct()
  return ReviewAssignment(
    "review", "a".repeat(64), lane, "base", "head", paths, hunks.map { it.hunkId },
    reviewRevision = ReviewRevision("session", 1),
    laneDecision = ReviewLaneDecision(
      lane,
      true,
      "assigned",
      ownedPaths = paths,
      originLayerChains = listOf(listOf("custom")),
      owningPack = "custom",
      specialistSkillName = "rubric-$lane",
    ),
  )
}

internal fun frame(method: String, params: Map<String, Any?> = emptyMap()): String = JsonSupport.mapToJsonString(
  linkedMapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params),
)

internal fun call(arguments: Map<String, Any?>): String = frame(
  "tools/call",
  mapOf("name" to "read_evidence", "arguments" to arguments),
)

internal fun payload(response: String): Map<String, Any?> {
  val result = JsonSupport.anyToStringAnyMap(
    JsonSupport.parseObjectOrNull(response)?.get("result")?.let(JsonSupport::jsonElementToValue),
  ).orEmpty()
  val content = requireNotNull(JsonSupport.anyToStringAnyMapList(result["content"])).single()["text"] as String
  return JsonSupport.anyToStringAnyMap(
    JsonSupport.parseObjectOrNull(content)?.let(JsonSupport::jsonElementToValue),
  ).orEmpty()
}

internal class Client(endpoint: GovernedReviewEvidenceEndpoint) : AutoCloseable {
  private val channel = SocketChannel.open(UnixDomainSocketAddress.of(endpoint.descriptor.socketPath))
  private val reader = Channels.newInputStream(channel).bufferedReader()
  private val writer = Channels.newOutputStream(channel).bufferedWriter()
  init {
    forward(JsonSupport.mapToJsonString(mapOf("params" to mapOf("token" to endpoint.descriptor.token))))
  }
  fun forward(line: String): String? {
    writer.appendLine(line)
    writer.flush()
    return reader.readLine()
  }
  fun exchange(line: String, onPublished: () -> Unit = {}): String {
    var response = ""
    GovernedReviewEvidenceBridge.exchange(line, ::forward) {
      response = it
      onPublished()
    }
    return response
  }
  fun failWrite(line: String) = GovernedReviewEvidenceBridge.exchange(
    line,
    ::forward,
  ) { throw IOException("closed worker") }
  override fun close() = channel.close()
}
