package skillbill.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

internal fun ParameterHolder.formatOption() = option("--format", help = "Output format.")
  .choice(
    CliFormat.TEXT.wireName to CliFormat.TEXT,
    CliFormat.JSON.wireName to CliFormat.JSON,
  )
  .default(CliFormat.TEXT)
