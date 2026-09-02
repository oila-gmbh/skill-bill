package skillbill.ports.process

fun interface ShutdownHookPort {
  fun register(action: () -> Unit): ShutdownHookRegistration
}

fun interface ShutdownHookRegistration {
  fun unregister(): Boolean
}
