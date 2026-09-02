package skillbill.ports.process

fun interface IdentifierGeneratorPort {
  fun randomToken(): String
}
