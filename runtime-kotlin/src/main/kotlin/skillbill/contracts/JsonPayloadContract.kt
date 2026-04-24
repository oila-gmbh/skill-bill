package skillbill.contracts

/** Explicit mapper surface for stable JSON-compatible runtime payloads. */
interface JsonPayloadContract {
  fun toPayload(): Map<String, Any?>
}
