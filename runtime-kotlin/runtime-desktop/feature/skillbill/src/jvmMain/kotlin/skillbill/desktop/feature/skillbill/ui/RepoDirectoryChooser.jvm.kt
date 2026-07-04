package skillbill.desktop.feature.skillbill.ui

import java.io.File
import javax.swing.JFileChooser

actual fun chooseDirectory(initialPath: String, title: String): String? {
  val chooser = JFileChooser(initialPath.takeIf(String::isNotBlank)?.let(::File)).apply {
    dialogTitle = title
    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    isAcceptAllFileFilterUsed = false
  }
  return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
    chooser.selectedFile?.absolutePath
  } else {
    null
  }
}
