import java.io.File

fun main(args: Array<String>) {
//    if (args.size != 1) {
//        println("Usage: ./kfc <filename>")
//        exitProcess(1)
//    }
//    val filename = args[1]
    val filename = "fac.kfc"
    val content = File(filename).readText()
    val program = Parser(content).parse()

    program.typecheck()

    File(filename.removeSuffix(".kfc") + ".s").writeText(program.compile())

    println("Success!")
}