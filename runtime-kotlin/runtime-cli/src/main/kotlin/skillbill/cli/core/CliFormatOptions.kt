package skillbill.cli.core

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import skillbill.cli.model.CliFormat

internal fun ParameterHolder.formatOption() = option("--format", help = "Output format.")
  .choice(
    CliFormat.TEXT.wireName to CliFormat.TEXT,
    CliFormat.JSON.wireName to CliFormat.JSON,
  )
  .default(CliFormat.TEXT)
