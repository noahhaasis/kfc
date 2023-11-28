import java.util.Stack

/*
To simplify typechecking we'll assume there's only three types for now. i64, bool and units.
 */

/* Next
    * Optimization: pass target register to expressions
    * More than 8 arguments
    * Compile && and || (short circuiting)
    * Write tests
    * Improve the parser error messages
    * comments
    * i32, u32, u64, f32, f64 (Better typechecking + more code generation)
    * Pointers! Strings + Arrays (Garbage collection?)
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
        val cg = CodeGenerator()
        // Make all functions global for now
        functions.filterIsInstance<Function.FunctionDef>().forEach { cg.genAssemblyMeta(".globl _${it.name}") }
        cg.genAssemblyMeta(".p2align 2")
        functions.filterIsInstance<Function.FunctionDef>().forEach { it.compile(cg) }
        return cg.generate()
    }

    fun typecheck() {
        val typeEnvironment = TypeEnvironment()
        typeEnvironment.pushScope() // Global scope
        functions.forEach { typeEnvironment.declare(it.name, it.getType()) }
        functions.filterIsInstance<Function.FunctionDef>().forEach { it.typecheck(typeEnvironment) }
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

sealed class Function(val name: String) {
    abstract fun getType(): Type

    class ExternFunction(name: String, val returnType: String, val params: Array<String>): Function(name) {
        override fun getType(): Type {
            return Type.FunctionType(Type.PrimitiveType(returnType), params.map { Type.PrimitiveType(it) }.toTypedArray())
        }
    }

    class FunctionDef(name: String, val returnType: String, val params: Array<Pair<String, String>>, val body: Array<Statement>): Function(name) {
        fun compile(cg: CodeGenerator) {
            val functionCompilationContext = constructFunctionComputationContext()
            cg.setFunctionCompilationContext(functionCompilationContext)

            cg.genAssemblyMeta("_$name:")
            // allocate 16 byte for x29 and x30 and and space for local variables (padded to 16 bytes)
            cg.genAssembly("sub sp, sp, #${16 + functionCompilationContext.spaceForLocalVars}")
            cg.genAssembly("stp x29, x30, [sp, #${functionCompilationContext.spaceForLocalVars}]")
            cg.genAssembly("add x29, sp, #${functionCompilationContext.spaceForLocalVars}") // the new frame pointer (x29) points to the beginning of the local variables.

            cg.saveArgumentsOnTheStack(params)

            body.forEach { it.compile(cg) }
        }

        fun typecheck(typeEnvironment: TypeEnvironment) {
            typeEnvironment.runScoped {
                params.forEach { typeEnvironment.declare(it.first, Type.PrimitiveType(it.second)) }
                body.forEach { it.typecheck(typeEnvironment, Type.PrimitiveType(returnType)) }
            }
        }

        override fun getType(): Type {
            return Type.FunctionType(Type.PrimitiveType(returnType), params.map { Type.PrimitiveType(it.second) }.toTypedArray())
        }

        private fun visit(visitor: StatementVisitor) {
            body.forEach { it.visit(visitor) }
        }

        private fun constructFunctionComputationContext(): FunctionCompilationContext {
            val environment = HashMap<String, RuntimeLocation>()
            var offset = 0

            // arguments are saved on the stack so the function can call other functions
            if (params.size > 8) {
                TODO("Only up to 8 arguments are supported for now")
            }

            val paramsAndDecls = mutableListOf<Pair<String, String>>()
            paramsAndDecls.addAll(params)
            paramsAndDecls.addAll(getAllDeclarations().map { Pair(it.name, it.type) })

            // Map local variables to their offset to x29
            for (nameAndType in paramsAndDecls) {
                val size = Type.sizeOfType(nameAndType.second)
                if (offset % size != 0) { // Padding
                    offset += size - (offset % size)
                }
                offset += Type.sizeOfType(nameAndType.second)
                environment[nameAndType.first] = RuntimeLocation.Stack(offset)
            }
            val spaceNeededWithPadding = offset + (16 - (offset % 16))

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
            return declarations.toTypedArray()
        }
    }
}

sealed class Type {
    data class PrimitiveType(val name: String): Type()
    data class FunctionType(val returnType: Type, val params: Array<Type>): Type()

    companion object {
        fun sizeOfType(type: Type): Int {
            return when (type) {
                is PrimitiveType -> sizeOfType(type.name)
                is FunctionType -> TODO()
            }
        }
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

    abstract fun compile(cg: CodeGenerator)
    abstract fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type)

    abstract fun visit(visitor: StatementVisitor)

    data class Return(val expr: Expression) : Statement() {
        override fun compile(cg: CodeGenerator) {
            expr.compile(cg)
            cg.pop(0, expr.type!!) // Pop the return value into x0/w0

            // restore fp, lr and sp
            cg.genReturn()
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
        override fun compile(cg: CodeGenerator) {
            val _endif: String = SymbolGenerator.generateSymbol("_endif")
            val _elseSymbol: String = SymbolGenerator.generateSymbol("_else")
            cond.compile(cg)
            cg.pop(8, cond.type!!)
            cg.genAssembly("cmp w8, #0")
            cg.genAssembly("b.eq ${if (_else != null) { _elseSymbol} else {_endif }}")
            then.forEach { it.compile(cg) }
            cg.genAssembly("b $_endif")
            if (_else != null) {
                cg.genAssembly("$_elseSymbol:")
                _else.forEach { it.compile(cg) }
            }
            cg.genAssembly("$_endif:")
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
        override fun compile(cg: CodeGenerator) {
            val _while = SymbolGenerator.generateSymbol("_while")
            val _endwhile = SymbolGenerator.generateSymbol("_endwhile")
            cg.genAssembly("$_while:")
            cond.compile(cg)
            cg.pop(8, cond.type!!)
            cg.genAssembly("cmp w8, #0")
            cg.genAssembly("b.eq $_endwhile")
            block.forEach { it.compile(cg) }
            cg.genAssembly("b $_while")
            cg.genAssembly("$_endwhile:")
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
        override fun compile(cg: CodeGenerator) {
            expr.compile(cg)
            if (expr.type != Type.PrimitiveType("unit")) {
                cg.popIgnore()
            }
        }

        override fun typecheck(typeEnvironment: TypeEnvironment, expectedReturnType: Type) {
            this.expr.typecheck(typeEnvironment)
        }

        override fun visit(visitor: StatementVisitor) {
            visitor.visit(this)
        }
    }

    data class Decl(val name: String, val type: String, val expr: Expression): Statement() {
        override fun compile(cg: CodeGenerator) {
            expr.compile(cg)

            cg.storeIntoVar(name, expr.type!!)
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
        override fun compile(cg: CodeGenerator) {
            expr.compile(cg)

            cg.storeIntoVar(name, expr.type!!)
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
    abstract fun compile(cg: CodeGenerator)

    // TODO: Return a optional type mismatch error and collect them
    abstract fun typecheck(typeEnvironment: TypeEnvironment)

    data class Integer(val int: Int): Expression(null){
        override fun compile(cg: CodeGenerator) {
            cg.genAssembly("mov x8, #$int")
            cg.push(8, type!!)
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = Type.PrimitiveType("i64")
        }
    }

    data class Boolean(val b: kotlin.Boolean): Expression(null) {
        override fun compile(cg: CodeGenerator) {
            cg.genAssembly("mov w8, #${if (b) 1 else 0}")
            cg.push(8, type!!)
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = Type.PrimitiveType("bool")
        }
    }

    data class Variable(val identifier: String): Expression(null) {
        override fun compile(cg: CodeGenerator) {
            cg.pushVariableOntoStack(identifier, type!!)
        }

        override fun typecheck(typeEnvironment: TypeEnvironment) {
            this.type = typeEnvironment.get(identifier)
        }
    }

    data class Call(val function: String, val args: Array<Expression>): Expression(null) {
        override fun compile(cg: CodeGenerator) {
            args.reversed().forEach { it.compile(cg) } // Evaluate all args
            args.forEachIndexed { i, arg -> cg.pop(i, arg.type!!)} // Pop the result into registers

            cg.genAssembly("bl _$function")
            if (type != Type.PrimitiveType("unit")) {
                cg.push(0, type!!) // Push the result onto the stack
            }
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
        override fun compile(cg: CodeGenerator) {
            // TODO: Remove unnecessary pop and push
            when (op) {
                BinaryOperator.ADD -> {
                    compileArgs(cg)
                    cg.genAssembly("add x8, x8, x9")
                }
                BinaryOperator.SUB -> {
                    compileArgs(cg)
                    cg.genAssembly("sub x8, x8, x9")
                }
                BinaryOperator.MUL -> {
                    compileArgs(cg)
                    cg.genAssembly("mul x8, x8, x9")
                }
                BinaryOperator.DIV -> {
                    compileArgs(cg)
                    cg.genAssembly("udiv x8, x8, x9")
                }
                BinaryOperator.AND -> { // TODO: Short circuiting
                    compileArgs(cg)
                    cg.genAssembly("and w8, w8, w9")
                }
                BinaryOperator.OR -> { // TODO: Short circuiting
                    compileArgs(cg)
                    cg.genAssembly("orr w8, w8, w9")
                }
                BinaryOperator.EQUALS -> {
                    compileArgs(cg)
                    // TODO: Equals on bools
                    cg.genAssembly("subs x8, x8, x9")
                    cg.genAssembly("cset w8, eq")
                    cg.genAssembly("and w8, w8, #0x1")
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
                    compileArgs(cg)
                    /* copied from godbolt */
                    cg.genAssembly("subs x8, x8, x9")
                    cg.genAssembly("cset w8, lt")
                    cg.genAssembly("and w8, w8, #0x1")
                }
                BinaryOperator.LESS_THAN_EQUAL -> {
                    TODO()
                }
            }
            cg.push(8, type!!)
        }

        fun compileArgs(cg: CodeGenerator) {
            left.compile(cg)
            right.compile(cg)
            cg.pop(9, right.type!!)
            cg.pop(8, left.type!!)
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
        override fun compile(cg: CodeGenerator) {
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
        override fun compile(cg: CodeGenerator) {
            operand.compile(cg)
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