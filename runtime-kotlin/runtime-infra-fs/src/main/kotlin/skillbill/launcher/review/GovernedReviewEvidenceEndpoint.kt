package skillbill.launcher.review

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.model.EnvironmentContext
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.review.context.model.ReviewExpansionRecord
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TOKEN_BYTES = 24
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602

@Inject
class UnixSocketGovernedReviewEvidenceEndpointBinder(
  private val environment: EnvironmentContext,
) : GovernedReviewEvidenceEndpointBinder {
  override fun bind(lane: String, protocol: NativeReviewOperationProtocol): GovernedReviewEvidenceEndpointHandle =
    GovernedReviewEvidenceEndpoint.bind(lane, protocol, bridgeCommand(environment.environment, environment.userHome))
}

internal fun bridgeCommand(environment: Map<String, String>, userHome: Path): List<String> {
  val configured = environment["SKILL_BILL_RUNTIME_MCP_BIN"]?.takeIf(String::isNotBlank)
  val home = userHome.takeUnless { it.toString().isBlank() }
    ?: Path.of(environment["HOME"]?.takeIf(String::isNotBlank) ?: System.getProperty("user.home"))
  val bin = configured?.let(Path::of)
    ?: home.resolve(".skill-bill").resolve("runtime").resolve("runtime-mcp").resolve("bin").resolve("runtime-mcp")
  if (!Files.isExecutable(bin)) {
    throw GovernedReviewEvidenceTransportError(
      "Governed review evidence bridge binary '$bin' is missing or not executable.",
    )
  }
  return listOf(bin.toAbsolutePath().normalize().toString())
}

/**
 * Per-launch listener that serves the two governed evidence operations by delegating verbatim to
 * the supplied protocol. It holds no filesystem access of its own and re-implements no policy,
 * budget, expansion ledger, or lane termination: every answer is whatever the broker returned.
 */
@Suppress("TooManyFunctions")
class GovernedReviewEvidenceEndpoint private constructor(
  override val descriptor: GovernedReviewEvidenceEndpointDescriptor,
  private val protocol: NativeReviewOperationProtocol,
  private val channel: ServerSocketChannel,
  private val directory: Path,
) : GovernedReviewEvidenceEndpointHandle {
  private val issuedExpansions = ConcurrentHashMap<String, ReviewExpansionRecord>()

  @Volatile
  private var closed = false

  private val acceptor = thread(name = "skill-bill-review-evidence-${descriptor.lane}", isDaemon = true) {
    acceptLoop()
  }

  override fun close() {
    if (closed) return
    closed = true
    runCatching { channel.close() }
    acceptor.interrupt()
    deleteDirectory()
  }

  private fun deleteDirectory() {
    runCatching { Files.deleteIfExists(descriptor.socketPath) }
    runCatching { Files.deleteIfExists(descriptor.mcpConfigPath) }
    runCatching { Files.deleteIfExists(GovernedReviewMcpConfigWriter.tomlConfigPath(descriptor.mcpConfigPath)) }
    val cursorConfig = GovernedReviewMcpConfigWriter.cursorProjectConfigPath(descriptor.mcpConfigPath)
    runCatching { Files.deleteIfExists(cursorConfig) }
    runCatching { Files.deleteIfExists(GovernedReviewMcpConfigWriter.cursorCliConfigPath(descriptor.mcpConfigPath)) }
    runCatching { Files.deleteIfExists(cursorConfig.parent) }
    runCatching { Files.deleteIfExists(directory) }
  }

  private fun acceptLoop() {
    while (!closed) {
      val connection = try {
        channel.accept()
      } catch (_: IOException) {
        return
      } ?: return
      runCatching { connection.use { serve(it) } }
    }
  }

  private fun serve(connection: SocketChannel) {
    val reader = Channels.newInputStream(connection).bufferedReader()
    val writer = Channels.newOutputStream(connection).bufferedWriter()
    if (!authenticated(reader.readLine())) return
    writer.appendLine(JsonSupport.mapToJsonString(linkedMapOf("jsonrpc" to "2.0", "result" to "ok")))
    writer.flush()
    while (!closed) {
      val line = reader.readLine() ?: return
      writer.appendLine(handleFrame(line))
      writer.flush()
    }
  }

  private fun authenticated(handshake: String?): Boolean {
    val frame = handshake?.let(JsonSupport::parseObjectOrNull) ?: return false
    val params = JsonSupport.anyToStringAnyMap(frame["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
    val presented = params["token"]?.toString().orEmpty()
    return java.security.MessageDigest.isEqual(
      presented.toByteArray(Charsets.UTF_8),
      descriptor.token.toByteArray(Charsets.UTF_8),
    )
  }

  internal fun handleFrame(line: String): String {
    val frame = JsonSupport.parseObjectOrNull(line)
      ?: return errorResponse(null, JSON_RPC_INVALID_PARAMS, "Malformed governed evidence frame.")
    val id = frame["id"]?.let(JsonSupport::jsonElementToValue)
    val method = frame["method"]?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
    if (method != "tools/call") {
      return errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Method not found: $method")
    }
    val params = JsonSupport.anyToStringAnyMap(frame["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
    val name = params["name"]?.toString().orEmpty()
    val arguments = JsonSupport.anyToStringAnyMap(params["arguments"]).orEmpty()
    return dispatch(id, name, arguments)
  }

  @Suppress("TooGenericExceptionCaught")
  private fun dispatch(id: Any?, name: String, arguments: Map<String, Any?>): String = try {
    when (name) {
      GovernedReviewEvidenceCodec.READ_EVIDENCE -> toolResponse(id, read(arguments))
      GovernedReviewEvidenceCodec.REQUEST_EXPANSION -> toolResponse(id, expand(arguments))
      else -> errorResponse(id, JSON_RPC_METHOD_NOT_FOUND, "Unknown governed operation: $name")
    }
  } catch (error: Exception) {
    errorResponse(id, JSON_RPC_INVALID_PARAMS, error.message.orEmpty())
  }

  private fun read(arguments: Map<String, Any?>): Map<String, Any?> {
    val request = GovernedReviewEvidenceCodec.readRequest(
      descriptor.lane,
      arguments,
      issuedExpansions::get,
    )
    return GovernedReviewEvidenceCodec.payload(protocol.read(request))
  }

  private fun expand(arguments: Map<String, Any?>): Map<String, Any?> {
    val record = protocol.authorizeExpansion(
      GovernedReviewEvidenceCodec.expansionRequest(descriptor.lane, arguments),
    )
    if (record.authorized) issuedExpansions[record.expansionId] = record
    return GovernedReviewEvidenceCodec.payload(record)
  }

  private fun toolResponse(id: Any?, payload: Map<String, Any?>): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "result" to linkedMapOf(
        "content" to listOf(linkedMapOf("type" to "text", "text" to JsonSupport.mapToJsonString(payload))),
        "isError" to false,
      ),
    ),
  )

  private fun errorResponse(id: Any?, code: Int, message: String): String = JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "error" to linkedMapOf("code" to code, "message" to message),
    ),
  )

  companion object {
    fun bind(
      lane: String,
      protocol: NativeReviewOperationProtocol,
      bridgeCommand: List<String>,
    ): GovernedReviewEvidenceEndpoint {
      val directory = privateDirectory()
      val socketPath = directory.resolve("evidence.sock")
      val token = newToken()

      @Suppress("TooGenericExceptionCaught")
      val channel = try {
        ServerSocketChannel.open(StandardProtocolFamily.UNIX)
          .bind(UnixDomainSocketAddress.of(socketPath))
      } catch (error: Exception) {
        runCatching { Files.deleteIfExists(directory) }
        throw GovernedReviewEvidenceTransportError(
          "Failed to bind the governed review evidence endpoint for lane '$lane'.",
          error,
        )
      }
      @Suppress("TooGenericExceptionCaught")
      return try {
        val configPath = GovernedReviewMcpConfigWriter.write(
          configPath = directory.resolve("mcp.json"),
          bridgeCommand = bridgeCommand,
          socketPath = socketPath,
          token = token,
          lane = lane,
        )
        GovernedReviewEvidenceEndpoint(
          GovernedReviewEvidenceEndpointDescriptor(lane, socketPath, configPath, token),
          protocol,
          channel,
          directory,
        )
      } catch (error: Exception) {
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(directory.resolve("mcp.json")) }
        runCatching {
          Files.deleteIfExists(
            GovernedReviewMcpConfigWriter.tomlConfigPath(directory.resolve("mcp.json")),
          )
        }
        val cursorConfig = directory.resolve(".cursor").resolve("mcp.json")
        runCatching { Files.deleteIfExists(cursorConfig) }
        runCatching { Files.deleteIfExists(directory.resolve(".cursor").resolve("cli.json")) }
        runCatching { Files.deleteIfExists(cursorConfig.parent) }
        runCatching { Files.deleteIfExists(directory) }
        throw error
      }
    }

    private fun privateDirectory(): Path = try {
      Files.createTempDirectory(
        "skill-bill-review-evidence-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
      )
    } catch (error: IOException) {
      throw GovernedReviewEvidenceTransportError("Failed to create the per-launch governed review directory.", error)
    }

    private fun newToken(): String = ByteArray(TOKEN_BYTES)
      .also(SecureRandom()::nextBytes)
      .joinToString("") { "%02x".format(it) }
  }
}
