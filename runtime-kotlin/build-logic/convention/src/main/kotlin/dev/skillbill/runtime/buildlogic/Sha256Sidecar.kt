package dev.skillbill.runtime.buildlogic

import java.io.File
import java.security.MessageDigest

// SKILL-55 (F-001): single source of truth for the SHA-256 sidecar contract shared by
// BOTH the runtime-image zips (RuntimeImageConventionPlugin) and the desktop installers
// (runtime-desktop/build.gradle.kts). Subtask 3/4 verifiers rely on this sidecar being
// byte-identical across artifact kinds, so the streaming digest, the buffer size, and the
// `<hex>  <name>\n` (two-space, trailing-newline, `sha256sum -c` compatible) format live
// here ONCE rather than being re-implemented per consumer.
//
// No Gradle types are referenced: every function is pure and takes a plain java.io.File so
// callers resolve paths to Strings/Files OUTSIDE their doLast closures (config-cache and
// String-capture posture stays the callers' concern, exactly as before).

/** Streaming-read buffer size for the SHA-256 digest. */
const val SHA256_BUFFER_BYTES: Int = 64 * 1024

/**
 * Compute the lowercase hex SHA-256 digest of [file], streaming through a
 * [SHA256_BUFFER_BYTES]-sized buffer so arbitrarily large archives never load fully into
 * memory.
 */
fun sha256Hex(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { stream ->
    val buffer = ByteArray(SHA256_BUFFER_BYTES)
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { byteValue -> "%02x".format(byteValue) }
}

/**
 * Write a SHA-256 sidecar next to [archive], named `<archive>.sha256`, in the canonical
 * `<hex>  <name>\n` format (two spaces, trailing newline) that `sha256sum -c` accepts. The
 * embedded name is [archive].name (the bare file name, not a path), matching the existing
 * runtime-image and desktop-installer behavior.
 *
 * @return the sidecar [File] that was written.
 */
fun writeSha256Sidecar(archive: File): File {
  val hex = sha256Hex(archive)
  val sidecar = File(archive.parentFile, "${archive.name}.sha256")
  sidecar.writeText("$hex  ${archive.name}\n")
  return sidecar
}
