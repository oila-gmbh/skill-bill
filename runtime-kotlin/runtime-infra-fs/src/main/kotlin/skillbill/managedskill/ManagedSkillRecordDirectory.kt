package skillbill.managedskill

import java.nio.channels.SeekableByteChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal interface RecordDirectory {
  fun attributes(name: Path): BasicFileAttributes
  fun newByteChannel(name: Path, options: Set<java.nio.file.OpenOption>): SeekableByteChannel
  fun atomicMove(source: Path, target: Path)
  fun deleteFile(name: Path)
  fun exists(name: Path): Boolean = runCatching { attributes(name) }.fold(
    onSuccess = { true },
    onFailure = { error ->
      if (error is NoSuchFileException) false else throw error
    },
  )
}

internal class SecureRecordDirectory(
  private val directory: SecureDirectoryStream<Path>,
) : RecordDirectory {
  override fun attributes(name: Path): BasicFileAttributes = directory.getFileAttributeView(
    name,
    BasicFileAttributeView::class.java,
    NOFOLLOW_LINKS,
  ).readAttributes()

  override fun newByteChannel(name: Path, options: Set<java.nio.file.OpenOption>): SeekableByteChannel =
    directory.newByteChannel(name, options)

  override fun atomicMove(source: Path, target: Path) = directory.move(source, directory, target)

  override fun deleteFile(name: Path) = directory.deleteFile(name)
}
