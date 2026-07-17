package skillbill.managedskill

import java.nio.channels.SeekableByteChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal interface BundleDirectory : AutoCloseable {
  fun names(): List<Path>
  fun attributes(name: Path): BasicFileAttributes
  fun newByteChannel(name: Path): SeekableByteChannel
  fun openDirectory(name: Path): BundleDirectory
  override fun close() = Unit
}

internal class SecureBundleDirectory(
  private val stream: SecureDirectoryStream<Path>,
) : BundleDirectory {
  override fun names(): List<Path> = stream.map { it.fileName }

  override fun attributes(name: Path): BasicFileAttributes = secureBundleAttributes(stream, name)

  override fun newByteChannel(name: Path): SeekableByteChannel =
    stream.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS))

  override fun openDirectory(name: Path): BundleDirectory =
    SecureBundleDirectory(stream.newDirectoryStream(name, NOFOLLOW_LINKS))

  override fun close() = stream.close()
}

internal fun secureBundleAttributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
  bundleOperation("Cannot inspect bundle entry: $name") {
    directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
  }
