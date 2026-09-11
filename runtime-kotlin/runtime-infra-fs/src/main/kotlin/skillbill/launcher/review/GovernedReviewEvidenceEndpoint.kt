package skillbill.launcher.review
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.error.ShellContentContractException
import skillbill.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.model.EnvironmentContext
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.model.readReviewEvidenceFrame
import skillbill.ports.review.model.validReviewEvidenceRequestId
import skillbill.review.context.model.ReviewEvidenceLimits
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
private const val TOKEN_BYTES = 24
private const val UNIX_SOCKET_PATH_LIMIT = 103
private const val TEMP_SUFFIX_DIGITS = 20
private const val PER_LAUNCH_PREFIX = "skill-bill-review-evidence-"
private const val SOCKET_FILE_NAME = "evidence.sock"
private const val DELIVERY_DRAIN_SECONDS = 1L

@Inject
class UnixSocketGovernedReviewEvidenceEndpointBinder(
  private val environment: EnvironmentContext,
) : GovernedReviewEvidenceEndpointBinder {
  override fun bind(
    lane: String,
    protocol: NativeReviewOperationProtocol,
    onEvidenceRead: (() -> Unit)?,
  ): GovernedReviewEvidenceEndpointHandle = GovernedReviewEvidenceEndpoint.bind(
    lane,
    protocol,
    bridgeCommand(environment.environment, environment.userHome),
    onEvidenceRead,
  )
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

class GovernedReviewEvidenceEndpoint private constructor(
  override val descriptor: GovernedReviewEvidenceEndpointDescriptor,
  private val protocol: NativeReviewOperationProtocol,
  private val channel: ServerSocketChannel,
  private val directory: Path,
  private val onEvidenceRead: (() -> Unit)?,
) : GovernedReviewEvidenceEndpointHandle {

  @Volatile
  private var closed = false

  @Volatile
  private var sessionFinished = false

  private val deliveryLock = ReentrantLock()
  private val deliveryCompleted = deliveryLock.newCondition()
  private val pendingDeliveries = mutableSetOf<String>()
  private var closing = false

  @Volatile
  private var activeConnection: SocketChannel? = null
  private val acceptor = thread(name = "skill-bill-review-evidence-${descriptor.lane}", isDaemon = true) {
    acceptLoop()
  }
  override fun unbindListener() {
    deliveryLock.withLock {
      if (closing) return
      closing = true
      runCatching { channel.close() }
      try {
        var remaining = TimeUnit.SECONDS.toNanos(DELIVERY_DRAIN_SECONDS)
        while (pendingDeliveries.isNotEmpty() && remaining > 0) {
          remaining = deliveryCompleted.awaitNanos(remaining)
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      } finally {
        closed = true
      }
    }
    runCatching { activeConnection?.close() }
    acceptor.interrupt()
    deleteGovernedReviewEndpointArtifacts(descriptor, directory)
  }

  override fun close() {
    unbindListener()
    if (!sessionFinished) {
      sessionFinished = true
      protocol.finishDeliverySession()
    }
  }
  private fun acceptLoop() {
    while (!closed) {
      val connection = try {
        channel.accept()
      } catch (_: IOException) {
        return
      } ?: return
      activeConnection = connection
      if (closed) {
        connection.close()
        return
      }
      try {
        runCatching { connection.use { serve(it) } }
      } finally {
        activeConnection = null
      }
    }
  }
  private fun serve(connection: SocketChannel) {
    val reader = Channels.newInputStream(connection).bufferedReader()
    val writer = Channels.newOutputStream(connection).bufferedWriter()
    if (!authenticated(reader.readReviewEvidenceFrame())) return
    writer.appendLine(JsonSupport.mapToJsonString(linkedMapOf("jsonrpc" to "2.0", "result" to "ok")))
    writer.flush()
    while (!closed) {
      val line = try {
        reader.readReviewEvidenceFrame() ?: return
      } catch (error: GovernedReviewEvidenceTransportError) {
        protocol.recordMalformedRequest()
        writer.appendLine(
          governedReviewEvidenceErrorResponse(
            null,
            GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
            error.message.orEmpty(),
          ),
        )
        writer.flush()
        return
      }
      val response = handleFrame(line)
      writer.appendLine(response)
      writer.flush()
      completeDelivery(line, response)
    }
  }
  private fun authenticated(handshake: String?): Boolean {
    val frame = handshake?.let(JsonSupport::parseObjectOrNull) ?: return false
    val params = JsonSupport.anyToStringAnyMap(frame["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
    val presented = params["token"]?.toString().orEmpty()
    return MessageDigest.isEqual(
      presented.toByteArray(Charsets.UTF_8),
      descriptor.token.toByteArray(Charsets.UTF_8),
    )
  }
  private fun completeDelivery(line: String, response: String) {
    val frame = JsonSupport.parseObjectOrNull(line) ?: return
    if (frame["method"]?.let(JsonSupport::jsonElementToValue) != "evidence/delivered") return
    if (JsonSupport.parseObjectOrNull(response)?.containsKey("error") != false) return
    val params = JsonSupport.anyToStringAnyMap(frame["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
    deliveryLock.withLock {
      pendingDeliveries.remove(params["receipt"] as? String)
      deliveryCompleted.signalAll()
    }
  }
  internal fun handleFrame(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size > ReviewEvidenceLimits.REQUEST_BYTES) {
      protocol.recordMalformedRequest()
      return governedReviewEvidenceErrorResponse(
        null,
        GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
        "Governed evidence frame exceeds its byte limit.",
      )
    }
    val frame = JsonSupport.parseObjectOrNull(line)
    if (frame == null) {
      protocol.recordMalformedRequest()
      return governedReviewEvidenceErrorResponse(
        null,
        GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
        "Malformed governed evidence frame.",
      )
    }
    val id = frame["id"]?.let(JsonSupport::jsonElementToValue)
    val method = frame["method"]?.let(JsonSupport::jsonElementToValue)
    val reject = deliveryLock.withLock {
      when {
        closed || closing && method != "evidence/delivered" -> {
          protocol.recordMalformedRequest()
          governedReviewEvidenceErrorResponse(
            id,
            GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
            "Governed review evidence endpoint is closing.",
          )
        }
        !validReviewEvidenceRequestId(id) -> {
          protocol.recordMalformedRequest()
          governedReviewEvidenceErrorResponse(
            null,
            GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
            "Invalid request id.",
          )
        }
        else -> null
      }
    }
    if (reject != null) return reject
    return handleGovernedReviewMethod(frame, id, protocol, ::dispatch)
  }
  private fun dispatch(id: Any?, name: String, arguments: Map<String, Any?>): String = try {
    when (name) {
      GovernedReviewEvidenceCodec.READ_EVIDENCE ->
        governedReviewEvidenceToolResponse(id, read(arguments))
      GovernedReviewEvidenceCodec.REQUEST_EXPANSION ->
        governedReviewEvidenceToolResponse(id, expand(arguments))
      else -> {
        protocol.recordMalformedRequest()
        governedReviewEvidenceErrorResponse(
          id,
          GOVERNED_REVIEW_EVIDENCE_JSON_RPC_METHOD_NOT_FOUND,
          "Unknown governed operation: $name",
        )
      }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: ShellContentContractException) {
    governedReviewEvidenceErrorResponse(
      id,
      GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
      error.message.orEmpty(),
    )
  } catch (error: IOException) {
    governedReviewEvidenceErrorResponse(
      id,
      GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
      error.message.orEmpty(),
    )
  } catch (error: IllegalArgumentException) {
    governedReviewEvidenceErrorResponse(
      id,
      GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
      error.message.orEmpty(),
    )
  } catch (error: IllegalStateException) {
    governedReviewEvidenceErrorResponse(
      id,
      GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
      error.message.orEmpty(),
    )
  }
  private fun read(arguments: Map<String, Any?>): Map<String, Any?> {
    if (arguments["operation"] == "discover") {
      val request = protocol.decodeGovernedReviewRequest { GovernedReviewEvidenceCodec.discoveryRequest(arguments) }
      return GovernedReviewEvidenceCodec.payload(protocol.discover(request))
    }
    val request = protocol.decodeGovernedReviewRequest {
      GovernedReviewEvidenceCodec.readRequest(descriptor.lane, arguments, protocol::expansionById)
    }
    val result = protocol.read(request)
    result.deliveryReceipt?.let { receipt ->
      deliveryLock.withLock {
        pendingDeliveries.add(receipt)
      }
    }
    val payload = GovernedReviewEvidenceCodec.payload(result)
    onEvidenceRead?.invoke()
    return payload
  }
  private fun expand(arguments: Map<String, Any?>): Map<String, Any?> {
    val record = protocol.authorizeExpansion(
      protocol.decodeGovernedReviewRequest { GovernedReviewEvidenceCodec.expansionRequest(descriptor.lane, arguments) },
    )
    return GovernedReviewEvidenceCodec.payload(record)
  }
  companion object {
    fun bind(
      lane: String,
      protocol: NativeReviewOperationProtocol,
      bridgeCommand: List<String>,
      onEvidenceRead: (() -> Unit)? = null,
    ): GovernedReviewEvidenceEndpoint {
      val directory = privateDirectory()
      val socketPath = directory.resolve(SOCKET_FILE_NAME)
      val token = newToken()
      val channel = openGovernedReviewChannel(lane, socketPath, directory)
      var failure: Throwable? = null
      var endpoint: GovernedReviewEvidenceEndpoint? = null
      try {
        val configPath = GovernedReviewMcpConfigWriter.write(
          configPath = directory.resolve("mcp.json"),
          bridgeCommand = bridgeCommand,
          socketPath = socketPath,
          token = token,
          lane = lane,
        )
        endpoint = GovernedReviewEvidenceEndpoint(
          GovernedReviewEvidenceEndpointDescriptor(lane, socketPath, configPath, token),
          protocol,
          channel,
          directory,
          onEvidenceRead,
        )
      } catch (error: CancellationException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      } catch (error: IOException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      } catch (error: IllegalArgumentException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      } catch (error: IllegalStateException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      }
      failure?.let { throw it }
      return endpoint!!
    }

    private fun openGovernedReviewChannel(lane: String, socketPath: Path, directory: Path): ServerSocketChannel {
      var failure: Throwable? = null
      var channel: ServerSocketChannel? = null
      try {
        channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
          .bind(UnixDomainSocketAddress.of(socketPath))
      } catch (error: CancellationException) {
        failure = error
      } catch (error: IOException) {
        runCatching { Files.deleteIfExists(directory) }
        failure = GovernedReviewEvidenceTransportError(
          "Failed to bind the governed review evidence endpoint for lane '$lane'.",
          error,
        )
      } catch (error: IllegalArgumentException) {
        runCatching { Files.deleteIfExists(directory) }
        failure = GovernedReviewEvidenceTransportError(
          "Failed to bind the governed review evidence endpoint for lane '$lane'.",
          error,
        )
      } catch (error: IllegalStateException) {
        runCatching { Files.deleteIfExists(directory) }
        failure = GovernedReviewEvidenceTransportError(
          "Failed to bind the governed review evidence endpoint for lane '$lane'.",
          error,
        )
      }
      failure?.let { throw it }
      return channel!!
    }

    private fun rollbackGovernedReviewBindArtifacts(channel: ServerSocketChannel, socketPath: Path, directory: Path) {
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
    }
    private fun privateDirectory(): Path = try {
      Files.createTempDirectory(
        perLaunchRoot(),
        PER_LAUNCH_PREFIX,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
      )
    } catch (error: IOException) {
      throw GovernedReviewEvidenceTransportError("Failed to create the per-launch governed review directory.", error)
    }
    internal fun perLaunchRoot(): Path {
      val configured = Path.of(System.getProperty("java.io.tmpdir"))
      if (socketPathFits(configured)) return configured
      val shortest = Path.of("/tmp")
      return if (Files.isDirectory(shortest) && socketPathFits(shortest)) shortest else configured
    }
    private fun socketPathFits(root: Path): Boolean = root
      .resolve(PER_LAUNCH_PREFIX + "0".repeat(TEMP_SUFFIX_DIGITS))
      .resolve(SOCKET_FILE_NAME)
      .toString()
      .toByteArray(Charsets.UTF_8)
      .size <= UNIX_SOCKET_PATH_LIMIT
    private fun newToken(): String = ByteArray(TOKEN_BYTES)
      .also(SecureRandom()::nextBytes)
      .joinToString("") { "%02x".format(it) }
  }
}
