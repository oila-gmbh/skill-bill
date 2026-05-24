package skillbill.ports.review

interface ReviewInputSource {
  fun readInput(inputPath: String, stdinText: String? = null): Pair<String, String?>
}
