package skillbill.contracts

/**
 * Stable identifier for a governed runtime contract that Kotlin code can expose while parity work
 * is still in progress.
 */
interface RuntimeContract {
  val name: String
  val version: String
}
