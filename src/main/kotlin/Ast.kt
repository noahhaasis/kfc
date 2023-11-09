class TypecheckException(message: String): Exception(message)

class Program(private val functions: Array<Function>) {
    fun compile(): String {
        val sb = StringBuilder()
        sb.appendLine("        .globl _main")
        sb.appendLine("        .p2align 2")
        functions.forEach { it.compile(sb) }
        return sb.toString()
    }
}

class Function(val name: String, val returnType: String, val params: Array<Pair<String, String>>, val body: Array<Statement>) {
    fun compile(sb: StringBuilder) {
        sb.appendLine("_$name:")
        // allocate 16 byte for x29 and x30 and 160 bytes for local variables
        sb.appendLine("        sub sp, sp, #176")
        sb.appendLine("        stp x29, x30, [sp, #160]")
        sb.appendLine("        add x29, sp, #160") // the new frame pointer (x29) points to the beginning of the local variables.
        body.forEach { it.compile(sb) }
    }
}

sealed class Statement {

    abstract fun compile(sb: StringBuilder)

    data class Return(val expr: Expression) : Statement() {
        override fun compile(sb: StringBuilder) {
            expr.compile(sb)
            // Pop the return value into x0
            sb.appendLine("        ldr x0, [sp]")
            sb.appendLine("        add sp, sp, #16")

            // restore fp, lr and sp
            sb.appendLine("        ldp x29, x30, [sp, #160]")
            sb.appendLine("        add sp, sp, #176")
            sb.appendLine("        ret")
        }
    }

    data class If(val cond: Expression, val then: Array<Statement>, val _else: Array<Statement>? = null): Statement(){
        override fun compile(sb: StringBuilder) {
            cond.compile(sb)
            sb.appendLine("        ldr x0, [sp]")
            sb.appendLine("        add sp, sp, #16")
            sb.appendLine("        cmp x0, #0")
            sb.appendLine("        b.eq ${if (_else != null) {"_else"} else {"_endif"}}")
            then.forEach { it.compile(sb) }
            sb.appendLine("        b _endif")
            if (_else != null) {
                sb.appendLine("_else:")
                _else.forEach { it.compile(sb) }
            }
            sb.appendLine("_endif:")
        }
    }

    data class While(val cond: Expression, val block: Array<Statement>): Statement() {
        override fun compile(sb: StringBuilder) {
            sb.appendLine("_while:")
            cond.compile(sb)
            sb.appendLine("        ldr x0, [sp]")
            sb.appendLine("        add sp, sp, #16")
            sb.appendLine("        cmp x0, #0")
            sb.appendLine("        b.eq _endwhile")
            block.forEach { it.compile(sb) }
            sb.appendLine("        b _while")
            sb.appendLine("_endwhile:")
        }
    }

    data class Expr(val expr: Expression): Statement(){
        override fun compile(sb: StringBuilder) {
            expr.compile(sb)
        }
    }

    data class Decl(val name: String, val type: String, val expr: Expression): Statement() {
        override fun compile(sb: StringBuilder) {

        }
    }

    data class Assign(val name: String, val expr: Expression): Statement() {
        override fun compile(sb: StringBuilder) {

        }
    }

}

enum class BinaryOperator {
    ADD,
    SUB,
    MUL,
    DIV,
    LESS_THAN,
    LESS_THAN_EQUAL,
    GREATER_THAN,
    GREATER_THAN_EQUAL,
    EQUALS,
    NOT_EQUALS,
    AND,
    OR;

    companion object {
        fun fromToken(token: Token): BinaryOperator {
            return when (token) {
                Token.Plus -> ADD
                Token.Minus -> SUB
                Token.Mul -> MUL
                Token.Div -> DIV
                Token.Lt -> LESS_THAN
                Token.Lte -> LESS_THAN_EQUAL
                Token.Gt -> GREATER_THAN
                Token.Gte -> GREATER_THAN_EQUAL
                Token.EqualityOperator -> EQUALS
                Token.NotEqualOperator -> NOT_EQUALS
                Token.And -> AND
                Token.Or -> OR

                else -> throw ParsingException("Unreachable: Unknown binary operator $token")
            }
        }
    }
}

sealed class Expression {
    // The result of the expression is going to be on the top of the stack
    abstract fun compile(sb: StringBuilder)

    data class Integer(val int: Int): Expression(){
        override fun compile(sb: StringBuilder) {
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        mov x0, #$int")
            sb.appendLine("        str x0, [sp]")
        }
    }

    data class Boolean(val b: kotlin.Boolean): Expression() {
        // TODO: Doublecheck this generated code
        override fun compile(sb: StringBuilder) {
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        mov x0, #${if (b) 1 else 0}")
            sb.appendLine("        str x0, [sp]")
        }
    }

    data class Variable(val identifier: String): Expression(){
        override fun compile(sb: StringBuilder) {

        }
    }

    data class Call(val function: String, val args: Array<Expression>): Expression(){
        override fun compile(sb: StringBuilder) {
            sb.appendLine("        bl _$function")
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        str x0, [sp]")
        }
    }

    data class Binop(val op: BinaryOperator, val left: Expression, val right: Expression): Expression(){
        override fun compile(sb: StringBuilder) {
            left.compile(sb)
            right.compile(sb)

            // load operands
            sb.appendLine("        ldr x1, [sp]")
            sb.appendLine("        ldr x0, [sp, #16]")
            when (op) {
                BinaryOperator.ADD -> {
                    sb.appendLine("        add x0, x0, x1")
                }
                BinaryOperator.SUB -> {
                    sb.appendLine("        sub x0, x0, x1")
                }
                BinaryOperator.MUL -> {
                    sb.appendLine("        mul x0, x0, x1")
                }
                BinaryOperator.DIV -> {
                    sb.appendLine("        udiv x0, x0, x1")
                }

                else -> TODO()
            }

            // store result on stack
            sb.appendLine("        add sp, sp, #16")
            sb.appendLine("        str x0, [sp]")
        }
    }

    data class UnaryMinus(val operand: Expression): Expression(){
        override fun compile(sb: StringBuilder) {

        }
    }

    data class Negate(val operand: Expression): Expression(){
        override fun compile(sb: StringBuilder) {

        }
    }
}