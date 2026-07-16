package skillbill.managedskill

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val READ_BUFFER_BYTES = 8192

internal fun readBundleAttributes(path: Path): BasicFileAttributes =
  bundleOperation("Cannot inspect bundle entry: $path") {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  }

internal fun readStableBytes(
  directory: BundleDirectory,
  name: Path,
  relative: String,
  before: BasicFileAttributes,
): ByteArray = bundleOperation("Cannot read bundle entry without following links: $relative") {
  if (!before.isRegularFile || before.isSymbolicLink) {
    invalidBundle("Bundle entry changed type while being read: $relative")
  }
  val bytes = directory.newByteChannel(name).use(::readAll)
  requireStableFile(before, directory.attributes(name), relative)
  bytes
}

private fun requireStableFile(before: BasicFileAttributes, after: BasicFileAttributes, relative: String) {
  if (!after.isRegularFile || after.isSymbolicLink) {
    invalidBundle("Bundle entry changed while being read: $relative")
  }
  if (!hasSameBundleIdentity(before, after)) {
    invalidBundle("Bundle entry changed while being read: $relative")
  }
  if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
    invalidBundle("Bundle entry changed while being read: $relative")
  }
}

private fun readAll(channel: SeekableByteChannel): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
  while (channel.read(buffer) >= 0) {
    buffer.flip()
    output.write(buffer.array(), 0, buffer.remaining())
    buffer.clear()
  }
  return output.toByteArray()
}

internal fun hasSameBundleIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean {
  val beforeKey = before.fileKey() ?: return true
  val afterKey = after.fileKey() ?: return true
  return beforeKey == afterKey
}
