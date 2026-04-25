package skillbill.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand

abstract class DocumentedCliCommand(
  name: String,
  private val helpText: String,
) : CliktCommand(name) {
  override fun help(context: Context): String = helpText
}

abstract class DocumentedNoOpCliCommand(
  name: String,
  private val helpText: String,
) : NoOpCliktCommand(name) {
  override fun help(context: Context): String = helpText
}
