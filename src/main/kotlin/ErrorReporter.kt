class ErrorReporter(private val filename: String, private val source: String) {
    fun report(t: Span<Token>, message: String) {
        // count lines until the token
        var lineCount = 1
        var posInLine = 0
        for (i in 0 until  t.location.start) {
            if (source[i] == '\n') {
                lineCount++
                posInLine = 0
            } else {
                posInLine++
            }
        }
        val startOfLine = t.location.start - posInLine
        var endOfLine = 0
        for (i in t.location.start until source.length) {
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
        println(" ".repeat(posInLine + prefix.length) + red + "^".repeat(t.location.length()) + reset)
        println(red + message + reset)
    }
}