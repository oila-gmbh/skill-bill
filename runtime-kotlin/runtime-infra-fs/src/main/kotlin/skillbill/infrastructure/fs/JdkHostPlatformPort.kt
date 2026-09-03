package skillbill.infrastructure.fs

import skillbill.ports.system.HostPlatformPort

object JdkHostPlatformPort : HostPlatformPort {
  override val osName: String get() = System.getProperty("os.name").orEmpty()
  override val jvmClassPath: String get() = System.getProperty("java.class.path").orEmpty()
  override val pathSeparator: String get() = System.getProperty("path.separator", ":")
}
