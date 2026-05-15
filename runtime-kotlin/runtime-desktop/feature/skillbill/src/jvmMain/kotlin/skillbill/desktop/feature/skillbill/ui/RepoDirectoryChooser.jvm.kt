package skillbill.desktop.feature.skillbill.ui

import java.io.File
import javax.swing.JFileChooser

actual fun chooseRepoDirectory(initialPath: String): String? {
  val chooser = JFileChooser(initialPath.takeIf(String::isNotBlank)?.let(::File)).apply {
    dialogTitle = "Open Skill Bill Repository"
    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    isAcceptAllFileFilterUsed = false
  }
  return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
    chooser.selectedFile?.absolutePath
  } else {
    null
  }
}
