import java.util.Stack

/*
To simplify typechecking we'll assume there's only two types for now. i64 and bool.
 */

/* Next:
    * TODO: Is x8 the first temp register or x9?
    * Debug fac.kfc DONE: The problem is that the exit code is between 0-255
    * Compile && and ||
    * Nested calls. Need to save all used param registers on the stack and restore them afterwards
    * Write tests
    * C FFI
    * i32, u32, u64, f32, f64 (Better typechecking + more code generation)
    * Don't use 16 bytes for every value on the stack
    * Strings + Arrays (Garbage collection?)
    * Sort functions in order or usage or declare symbols globally in assembler
 */

// NOTE
/*
Factorial seems to work. The problem is that the exit code is only 1 byte;
 */

/*
TODO: Handle this case
Code generation can't handle this case.
Maybe introduce a renaming pass?
if (condition) {
    let something = 10;
else {
    let something = 20;
}
 */

object SymbolGenerator {
    private var counter = 0
    fun generateSymbol(name: String): String {
        counter++
        return "$name$counter"
    }
}

sealed class RuntimeLocation {
    data class Register(val name: String): RuntimeLocation()
    data class Stack(val offset: Int): RuntimeLocation()
}


class TypecheckException(message: String): Exception(message)

class Program(private val functions: Array<Function>) {
    fun compile(): String {
        val sb = StringBuilder()
        // Make all functions global for now
        functions.forEach { sb.appendLine("        .globl _${it.name}") }
        sb.appendLine("        .p2align 2")
        functions.forEach { it.compile(sb) }
        return sb.toString()
    }
    
    fun typecheck() {
        val typeEnvironment = TypeEnvironment()
        typeEnvironment.pushScope() // Global scope
        functions.forEach { typeEnvironment.declare(it.name, it.getType()) }
        functions.forEach { it.typecheck(typeEnvironment) }
    }
}

class TypeEnvironment {
    private val scopes: Stack<MutableMap<String, Type>> = Stack()

    fun get(identifier: String): Type {
        scopes.forEach { if (it.containsKey(identifier)) return it[identifier]!! }
        throw TypecheckException("Variable $identifier not found in environment")
    }
    
    fun declare(identifier: String, type: Type) {
        // Shadowing is not allowed
        if (tryGet(identifier) != null) throw TypecheckException("Variable $identifier already declared")
        scopes.peek()[identifier] = type
    }
    
    fun pushScope() {
        scopes.push(mutableMapOf())
    }
    
    fun popScope() {
        scopes.pop()
    }
    
    fun runScoped(block: () -> Unit) {
        pushScope()
        block()
        popScope()
    }

    private fun tryGet(identifier: String): Type? {
        scopes.forEach { if (it.containsKey(identifier)) return it[identifier]!! }
        return null
    }
}

class FunctionCompilationContext(val environment: Map<String, RuntimeLocation>, val spaceForLocalVars: Int)

class Function(val name: String, val returnType: String, val params: Array<Pair<String, String>>, val body: Array<Statement>) {
    fun compile(sb: StringBuilder) {
        val functionCompilationContext = constructFunctionComputationContext()

        sb.appendLine("_$name:")
        // allocate 16 byte for x29 and x30 and and space for local variables (padded to 16 bytes)
        sb.appendLine("        sub sp, sp, #${16 + functionCompilationContext.spaceForLocalVars}")
        sb.appendLine("        stp x29, x30, [sp, #${functionCompilationContext.spaceForLocalVars}]")
        sb.appendLine("        add x29, sp, #${functionCompilationContext.spaceForLocalVars}") // the new frame pointer (x29) points to the beginning of the local variables.

        // TODO: Add arguments to the offset map
        body.forEach { it.compile(sb, functionCompilationContext) }
    }

    fun visit(visitor: StatementVisitor) {
        body.forEach { it.visit(visitor) }
    }

    fun typecheck(typeEnvironment: TypeEnvironment) {
        typeEnvironment.runScoped {
            params.forEach { typeEnvironment.declare(it.first, Type.PrimitiveType(it.second)) }
            body.forEach { it.typecheck(typeEnvironment, Type.PrimitiveType(returnType)) }
        }
    }

    fun getType(): Type {
        return Type.FunctionType(Type.PrimitiveType(returnType), params.map { Type.PrimitiveType(it.second) }.toTypedArray())
    }

    private fun constructFunctionComputationContext(): FunctionCompilationContext {
        // Map local variables to their offset to x29
        val declarations = getAllDeclarations()
        val environment = HashMap<String, RuntimeLocation>()
        var offset = 0
        for (i in declarations.indices) {
            val decl = declarations[i]
            val size = Type.sizeOfType(decl.type)
            if (offset % size != 0) { // Padding
                offset += size - (offset % size)
            }
            offset += Type.sizeOfType(decl.type)
            environment[decl.name] = RuntimeLocation.Stack(offset)
        }
        val spaceNeededWithPadding = offset + (16 - (offset % 16))

        // map parameters to registers
        if (params.size > 8) {
            TODO("Only up to 8 arguments are supported for now")
        }
        params.forEachIndexed() { index, param ->
            val registerPrefix = if (Type.sizeOfType(param.second) > 4)  "x" else "w"
            val register = "$registerPrefix${index}"
            environment[param.first] = RuntimeLocation.Register(register)
        }

        return FunctionCompilationContext(environment, spaceNeededWithPadding)
    }

    private fun getAllDeclarations(): Array<Statement.Decl> {
        val declarations = mutableListOf<Statement.Decl>()
        visit(object : StatementVisitor {
            override fun visit(statement: Statement.Decl) {
                declarations.add(statement)
            }

            override fun visit(statement: Statement.Return) {}
            override fun visit(statement: Statement.If) {}
            override fun visit(statement: Statement.While) {}
            override fun visit(statement: Statement.Expr) {}
            override fun visit(statement: Statement.Assign) {}
        })
        return declarations.toTypedArray();
    }
}

sealed class Type {
    data class PrimitiveType(val name: String): Type()
    data class FunctionType(val returnType: Type, val params: Array<Type>): Type()

    companion object {
        fun sizeOfType(type: String): Int {
            return when (type) {
                "i64" -> 8
                "bool" -> 1
                else -> throw TypecheckException("Unknown type $type")
            }
        }
    }
}

interface StatementVisitor {
    fun visit(statement: Statement.Return)
    fun visit(statement: Statement.If)
    fun visit(statement: Statement.While)
    fun visit(statement: Statement.Expr)
    fun visit(statement: Statement.Decl)
    fun visit(statement: Statement.Assign)
}

sealed class Statement {

    abstract fun compile(sb: StringBuilder, context: FunctionCompilationContext)
    abstract fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type)

    abstract fun visit(visitor: StatementVisitor)

    data class Return(val expr: Expression) : Statement() {
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            expr.compile(sb, context.environment)
            // Pop the return value into x0
            sb.appendLine("        ldr x0, [sp]")
            sb.appendLine("        add sp, sp, #16")

            // restore fp, lr and sp
            sb.appendLine("        ldp x29, x30, [sp, #${context.spaceForLocalVars}]")
            sb.appendLine("        add sp, sp, #${context.spaceForLocalVars+16}")
            sb.appendLine("        ret")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            expr.typecheck(typeEnvironment)
            if (expr.type!! != expectedReturnType) {
                throw TypecheckException("Expected return type $expectedReturnType but got ${expr.type}")
            }
        }

        override fun visit(visitor: StatementVisitor) {
            visitor.visit(this)
        }
    }

    data class If(val cond: Expression, val then: Array<Statement>, val _else: Array<Statement>? = null): Statement(){
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            val _endif: String = SymbolGenerator.generateSymbol("_endif")
            val _elseSymbol: String = SymbolGenerator.generateSymbol("_else")
            cond.compile(sb, context.environment)
            sb.appendLine("        ldrb w9, [sp]")
            sb.appendLine("        add sp, sp, #16")
            sb.appendLine("        cmp w9, #0")
            sb.appendLine("        b.eq ${if (_else != null) { _elseSymbol} else {_endif }}")
            then.forEach { it.compile(sb, context) }
            sb.appendLine("        b $_endif")
            if (_else != null) {
                sb.appendLine("$_elseSymbol:")
                _else.forEach { it.compile(sb, context) }
            }
            sb.appendLine("$_endif:")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            this.cond.typecheck(typeEnvironment)
            if (this.cond.type!! != Type.PrimitiveType("bool")) {
                throw TypecheckException("Expected condition of if statement to be of type bool, got ${this.cond.type}")
            }

            typeEnvironment.runScoped { this.then.map { it.typecheck(typeEnvironment, expectedReturnType) } }
            if (this._else != null) {
                typeEnvironment.runScoped { this._else.map { it.typecheck(typeEnvironment, expectedReturnType) } }
            }
        }

        override fun visit(visitor: StatementVisitor) {
            then.forEach { it.visit(visitor) }
            _else?.forEach { it.visit(visitor) }
            visitor.visit(this)
        }
    }

    data class While(val cond: Expression, val block: Array<Statement>): Statement() {
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            val _while = SymbolGenerator.generateSymbol("_while")
            val _endwhile = SymbolGenerator.generateSymbol("_endwhile")
            sb.appendLine("$_while:")
            cond.compile(sb, context.environment)
            sb.appendLine("        ldr x9, [sp]")
            sb.appendLine("        add sp, sp, #16")
            sb.appendLine("        cmp x9, #0")
            sb.appendLine("        b.eq $_endwhile")
            block.forEach { it.compile(sb, context) }
            sb.appendLine("        b $_while")
            sb.appendLine("$_endwhile:")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            cond.typecheck(typeEnvironment)
            if (cond.type!! != Type.PrimitiveType("bool")) {
                throw TypecheckException("Expected condition of while statement to be of type bool, got ${cond.type}")
            }

            typeEnvironment.runScoped { block.map { it.typecheck(typeEnvironment, expectedReturnType) } }
        }

        override fun visit(visitor: StatementVisitor) {
            block.forEach { it.visit(visitor) }
            visitor.visit(this)
        }
    }

    data class Expr(val expr: Expression): Statement(){
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            expr.compile(sb, context.environment)
            sb.appendLine("        add sp, sp, #16")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            this.expr.typecheck(typeEnvironment)
        }

        override fun visit(visitor: StatementVisitor) {
            visitor.visit(this)
        }
    }

    data class Decl(val name: String, val type: String, val expr: Expression): Statement() {
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            expr.compile(sb, context.environment)

            val location = context.environment[name]!!
            when (type) {
                "i64" -> {
                    // pop the value into x9
                    sb.appendLine("        ldr x9, [sp]")
                    sb.appendLine("        add sp, sp, #16")
                    // store x9 at offset from fp
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov ${location.name}, x9")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        str x9, [x29, #-${location.offset}]")
                        }
                    }
                }
                "bool" -> {
                    sb.appendLine("        ldrb w9, [sp]")
                    sb.appendLine("        add sp, sp, #16")
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov ${location.name}, w9")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        strb w9, [x29, #-${location.offset}]")
                        }
                    }
                }
                else -> TODO()
            }
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            expr.typecheck(typeEnvironment)
            if (expr.type!! != Type.PrimitiveType(type)) {
                throw TypecheckException("Expected type $type but got ${expr.type}")
            }
            typeEnvironment.declare(name, Type.PrimitiveType(type))
        }

        override fun visit(visitor: StatementVisitor) {
            visitor.visit(this)
        }
    }

    data class Assign(val name: String, val expr: Expression): Statement() {
        override fun compile(sb: StringBuilder, context: FunctionCompilationContext) {
            // TODO: Combine this with the code of Decl. It really is the same
            expr.compile(sb, context.environment)

            val location = context.environment[name]!!
            when (expr.type!!) {
                Type.PrimitiveType("i64") -> {
                    // pop the value into x9
                    sb.appendLine("        ldr x9, [sp]")
                    sb.appendLine("        add sp, sp, #16")
                    // store x9 at offset from fp
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov ${location.name}, x9")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        str x9, [x29, #-${location.offset}]")
                        }
                    }
                }
                Type.PrimitiveType("bool") -> {
                    sb.appendLine("        ldrb w9, [sp]")
                    sb.appendLine("        add sp, sp, #16")
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov ${location.name}, w9")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        strb w9, [x29, #-${location.offset}]")
                        }
                    }
                }
                else -> TODO()
            }
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            val varType = typeEnvironment.get(name)
            expr.typecheck(typeEnvironment)
            if (expr.type!! != varType) {
                throw TypecheckException("Expected type $varType but got ${expr.type}")
            }
        }

        override fun visit(visitor: StatementVisitor) {
            visitor.visit(this)
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

sealed class Expression(var type: Type?) {
    // The result of the expression is going to be on the top of the stack
    abstract fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>)

    // TODO: Return a optional type mismatch error and collect them
    abstract fun typecheck(typeEnvironment: TypeEnvironment)

    data class Integer(val int: Int): Expression(null){
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        mov x9, #$int")
            sb.appendLine("        str x9, [sp]")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = Type.PrimitiveType("i64")
        }
    }

    data class Boolean(val b: kotlin.Boolean): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        mov w9, #${if (b) 1 else 0}")
            sb.appendLine("        strb w9, [sp]")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = Type.PrimitiveType("bool")
        }
    }

    data class Variable(val identifier: String): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            val location = environment[identifier]!!
            sb.appendLine("        sub sp, sp, #16")
            when (type) {
                Type.PrimitiveType("i64") -> {
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov x9, ${location.name} // Load param $identifier")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        ldr x9, [x29, #-${location.offset}] //  Load var $identifier")
                        }
                    }
                    sb.appendLine("        str x9, [sp]")
                }
                Type.PrimitiveType("bool") -> {
                    when (location) {
                        is RuntimeLocation.Register -> {
                            sb.appendLine("        mov w9, ${location.name} // Load param $identifier")
                        }
                        is RuntimeLocation.Stack -> {
                            sb.appendLine("        ldrb w9, [x29, #-${location.offset}] // Load var $identifier")
                        }
                    }
                    sb.appendLine("        strb w9, [sp]")
                }
                else -> TODO()
            }
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = typeEnvironment.get(identifier)
        }
    }

    data class Call(val function: String, val args: Array<Expression>): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            args.reversed().forEach { it.compile(sb, environment) }
            for (i in args.size - 1 downTo 0) {
                val arg = args[i]
                when (arg.type) {
                    Type.PrimitiveType("i64") -> {
                        sb.appendLine("        ldr x$i, [sp]")
                        sb.appendLine("        add sp, sp, #16")
                    }
                    Type.PrimitiveType("bool") -> {
                        sb.appendLine("        ldrb w$i}, [sp]")
                        sb.appendLine("        add sp, sp, #16")
                    }
                    else -> TODO()
                }
            }
            sb.appendLine("        bl _$function")
            sb.appendLine("        sub sp, sp, #16")
            sb.appendLine("        str x0, [sp]")
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            val functionType = typeEnvironment.get(function)
            if (functionType !is Type.FunctionType) {
                throw TypecheckException("Expected type FunctionType but got $functionType")
            }
            args.forEach { it.typecheck(typeEnvironment) }
            functionType.params.zip(args.map { it.type!! }).forEach {
                if (it.first != it.second) {
                    throw TypecheckException("Expected type ${it.first} but got ${it.second}")
                }
            }
            if (functionType.params.size != args.size) {
                throw TypecheckException("Expected ${functionType.params.size} arguments but got ${args.size}")
            }
            this.type = functionType.returnType
        }
    }

    data class Binop(val op: BinaryOperator, val left: Expression, val right: Expression): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            left.compile(sb, environment)
            right.compile(sb, environment)

            // load operands
            sb.appendLine("        ldr x10, [sp]")
            sb.appendLine("        ldr x9, [sp, #16]")
            // make space for the result
            sb.appendLine("        add sp, sp, #16")
            when (op) {
                BinaryOperator.ADD -> {
                    sb.appendLine("        add x9, x9, x10")
                    sb.appendLine("        str x9, [sp]")
                }
                BinaryOperator.SUB -> {
                    sb.appendLine("        sub x9, x9, x10")
                    sb.appendLine("        str x9, [sp]")
                }
                BinaryOperator.MUL -> {
                    sb.appendLine("        mul x9, x9, x10")
                    sb.appendLine("        str x9, [sp]")
                }
                BinaryOperator.DIV -> {
                    sb.appendLine("        udiv x9, x9, x10")
                    sb.appendLine("        str x9, [sp]")
                }
                BinaryOperator.AND -> {
                    TODO()
                }
                BinaryOperator.OR -> {
                    TODO()
                }
                BinaryOperator.EQUALS -> {
                    sb.appendLine("        subs x9, x9, x10")
                    sb.appendLine("        cset w9, eq")
                    sb.appendLine("        mov w10, #1")
                    sb.appendLine("        and w9, w9, w10")
                    sb.appendLine("        strb w9, [sp]")
                }
                BinaryOperator.NOT_EQUALS -> {
                    TODO()
                }
                BinaryOperator.GREATER_THAN -> {
                    TODO()
                }
                BinaryOperator.GREATER_THAN_EQUAL -> {
                    TODO()
                }
                BinaryOperator.LESS_THAN -> {
                    /* copied from godbolt */
                    sb.appendLine("        subs x9, x9, x10")
                    sb.appendLine("        cset w9, lt")
                    sb.appendLine("        and w9, w9, #0x1")
                    sb.appendLine("        strb w9, [sp]")
                }
                BinaryOperator.LESS_THAN_EQUAL -> {
                    TODO()
                }
            }
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {

            this.type = when (op) {
                BinaryOperator.ADD -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("i64")
                }
                BinaryOperator.SUB -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("i64")
                }
                BinaryOperator.MUL -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("i64")
                }
                BinaryOperator.DIV -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("i64")
                }
                BinaryOperator.EQUALS -> {
                    assertOperandTypesAreEqual(typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.NOT_EQUALS -> {
                    assertOperandTypesAreEqual(typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.GREATER_THAN -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.LESS_THAN -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.LESS_THAN_EQUAL -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.GREATER_THAN_EQUAL -> {
                    assertOperandTypes(Type.PrimitiveType("i64"), Type.PrimitiveType("i64"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.AND -> {
                    assertOperandTypes(Type.PrimitiveType("bool"), Type.PrimitiveType("bool"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
                BinaryOperator.OR -> {
                    assertOperandTypes(Type.PrimitiveType("bool"), Type.PrimitiveType("bool"), typeEnvironment)
                    Type.PrimitiveType("bool")
                }
            }
        }

        private fun assertOperandTypes(leftType: Type, rightType: Type, typeEnvironment: TypeEnvironment) {
            this.left.typecheck(typeEnvironment)
            this.right.typecheck(typeEnvironment)
            if (this.left.type!! != leftType) {
                throw TypecheckException("Expected type $leftType but got ${this.left.type}")
            }
            if (this.right.type!! != rightType) {
                throw TypecheckException("Expected type $rightType but got ${this.right.type}")
            }
        }

        private fun assertOperandTypesAreEqual(typeEnvironment: TypeEnvironment) {
            this.left.typecheck(typeEnvironment)
            this.right.typecheck(typeEnvironment)
            if (this.left.type!! != this.right.type!!) {
                throw TypecheckException("Expected type ${this.left.type} but got ${this.right.type}")
            }
        }
    }

    data class UnaryMinus(val operand: Expression): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            TODO()
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.operand.typecheck(typeEnvironment)
            if (this.operand.type!! != Type.PrimitiveType("i64")) {
                throw TypecheckException("Expected type i64, got ${this.operand.type}")
            }
            this.type = Type.PrimitiveType("i64")
        }
    }

    data class Negate(val operand: Expression): Expression(null) {
        override fun compile(sb: StringBuilder, environment: Map<String, RuntimeLocation>) {
            operand.compile(sb, environment)
            TODO()
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.operand.typecheck(typeEnvironment)
            if (this.operand.type!! != Type.PrimitiveType("bool")) {
                throw TypecheckException("Expected type bool, got ${this.operand.type}")
            }
            this.type = Type.PrimitiveType("bool")
        }
    }
}