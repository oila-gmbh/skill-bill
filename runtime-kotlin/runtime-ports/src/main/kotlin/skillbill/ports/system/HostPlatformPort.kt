package skillbill.ports.system

interface HostPlatformPort {
  val osName: String
  val jvmClassPath: String
  val pathSeparator: String
}
