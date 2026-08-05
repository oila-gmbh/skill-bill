package skillbill.ports.agentrun

/**
 * Resolves whether a launcher executable is reachable from the current process. Keeps availability
 * checks out of the ambient PATH at every call site so they stay injectable and testable.
 */
fun interface ExecutableLookup {
  fun onPath(executable: String): Boolean
}
