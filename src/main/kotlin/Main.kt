import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: ./kfc <filename>")
        exitProcess(1)
    }
    val filename = args[1]
    val content = File(filename).readText()
    val program = Parser(filename, content).parse()

    program.typecheck()

    val targetFileName = filename.removeSuffix(".kfc") + ".s"
    File(targetFileName).writeText(program.compile())

    println("Success! Compiled to '$targetFileName'.")
}
