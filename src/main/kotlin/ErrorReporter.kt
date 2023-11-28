class ErrorReporter(val filename: String, val source: String) {
    fun report(got: Span<Token>, message: String) {
        // count lines until the token
        var lineCount = 1
        var posInLine = 0
        for (i in 0 until  got.start) {
            if (source[i] == '\n') {
                lineCount++
                posInLine = 0
            } else {
                posInLine++
            }
        }
        val startOfLine = got.start - posInLine
        var endOfLine = 0
        for (i in got.start until source.length) {
            if (source[i] == '\n') {
                endOfLine = i
                break
            }
        }

        val red = "\u001b[31m"
        val blue = "\u001b[34m"
        val reset = "\u001b[0m"

        val line = source.substring(startOfLine until endOfLine)
        val prefix = "$lineCount | "

        println("$blue$filename:$reset")
        println(blue + prefix + reset + line)
        println(" ".repeat(posInLine + prefix.length) + red + "^".repeat(got.end - got.start) + reset)
        println(red + message + reset)
    }
}