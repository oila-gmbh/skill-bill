package skillbill.ports.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object JvmSystemClock : Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)

  override fun instant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
}
