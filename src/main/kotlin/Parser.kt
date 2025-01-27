class ParsingException(message: String): java.lang.RuntimeException(message) {
    constructor(expected: Token, got: Token) : this("Expected token $expected but got $got") {}
}

class Parser(filename: String, private val source: String) {

    fun parse(): Program {
        val functions = mutableListOf<Function>()
        while (peek() != Token.End) {
            functions.add(functionOrExternFunction())
        }
        return Program(functions.toTypedArray())
    }

    private fun functionOrExternFunction(): Function {
        return when (val token = next()) {
            is Token.Keyword -> {
                when (token.keyword) {
                    "fn" -> {
                        function()
                    }
                    "extern" -> {
                        externFunction()
                    }
                    else -> {
                        throw ParsingException("Unreachable: Unknown identifier ${token.keyword}")
                    }
                }
            }
            else -> {
                throw ParsingException("Unreachable: Unknown token $token")
            }
        }
    }

    private fun externFunction(): Function.ExternFunction {
        // Keyword "extern" was already consumed
        expect(Token.Keyword("fn"))
        val name = expectIdentifier().identifier
        expect(Token.OpenParen)

        val types: MutableList<String> = mutableListOf()
        while (peek() != Token.CloseParen) {
            types.add(expectIdentifier().identifier)
            if (peek() != Token.Comma) {
                break
            }
            next() // Skip comma
        }
        expect(Token.CloseParen)
        expect(Token.Colon)

        val returnType = expectIdentifier().identifier
        expect(Token.Semicolon)

        return Function.ExternFunction(name, returnType, types.toTypedArray())
    }

    private fun function(): Function.FunctionDef {
        // Keyword "fn" was already consumed
        val name = expectIdentifier()
        expect(Token.OpenParen)

        val params = mutableListOf<Pair<String, String>>()
        while (peek() != Token.CloseParen) {
            val paramName = expectIdentifier()
            expect(Token.Colon)
            val paramType = expectIdentifier()
            params.add(Pair(paramName.identifier, paramType.identifier))
            if (peek() != Token.Comma) {
                break
            }
            next() // Skip comma
        }
        expect(Token.CloseParen)
        expect(Token.Colon)
        val returnType = expectIdentifier()

        val body = block()
        return Function.FunctionDef(name.identifier, returnType.identifier, params.toTypedArray(), body)
    }

    private fun block(): Array<Statement> {
        expect(Token.OpenCurly)
        val statements = mutableListOf<Statement>()
        while (peek() != Token.CloseCurly) {
            statements.add(statement())
        }
        expect(Token.CloseCurly)
        return statements.toTypedArray()
    }

    private fun statement(): Statement {
        // TODO: Maybe wrap it in a try catch, store the error and find the next semicolon
        try {
            return when (val t = peek()) {
                is Token.Keyword ->
                    when (t.keyword) {
                        "if" -> {
                            next()
                            expect(Token.OpenParen)
                            val cond = expr()
                            expect(Token.CloseParen)
                            val then = block()

                            if (peek() == Token.Keyword("else")) {
                                next()
                                val elseBlock = block()
                                Statement.If(cond, then, elseBlock)
                            } else {
                                Statement.If(cond, then)
                            }
                        }
                        "while" -> {
                            next()
                            expect(Token.OpenParen)
                            val cond = expr()
                            expect(Token.CloseParen)
                            val block = block()
                            Statement.While(cond, block)
                        }
                        "return" -> {
                            next()
                            val statement = Statement.Return(expr())
                            expect(Token.Semicolon)
                            statement
                        }
                        "let" -> {
                            next()
                            val name = expectIdentifier()
                            expect(Token.Colon)
                            val type = expectIdentifier()
                            expect(Token.Equal)
                            val expr = expr()
                            val statement = Statement.Decl(name.identifier, type.identifier, expr)
                            expect(Token.Semicolon)
                            statement
                        }
                        else -> {
                            throw ParsingException("Unreachable: Unknown identifier ${t.keyword}")
                        }
                    }
                else -> {
                    val peek2 = peek2()
                    if (peek2 == Token.Equal || peek2 == Token.PlusEqual || peek2 == Token.MinusEqual || peek2 == Token.TimesEqual || peek2 == Token.DivEqual) {
                        val name = expectIdentifier()
                        next()
                        val expr = expr()
                        val statement = when (peek2) {
                            Token.Equal -> Statement.Assign(name.identifier, expr)
                            Token.PlusEqual -> Statement.Assign(
                                name.identifier,
                                Expression.Binop(BinaryOperator.ADD, Expression.Variable(name.identifier), expr)
                            )
                            Token.MinusEqual -> Statement.Assign(
                                name.identifier,
                                Expression.Binop(BinaryOperator.SUB, Expression.Variable(name.identifier), expr)
                            )
                            Token.TimesEqual -> Statement.Assign(
                                name.identifier,
                                Expression.Binop(BinaryOperator.MUL, Expression.Variable(name.identifier), expr)
                            )
                            Token.DivEqual -> Statement.Assign(
                                name.identifier,
                                Expression.Binop(BinaryOperator.DIV, Expression.Variable(name.identifier), expr)
                            )
                            else -> throw ParsingException("Unreachable: Unknown token $peek2")
                        }
                        expect(Token.Semicolon)
                        statement
                    } else {
                        val statement = Statement.Expr(expr())
                        expect(Token.Semicolon)
                        statement
                    }
                }
            }
        } catch (e: ParsingException) {
            // Find the next semicolon
            while (peek() != Token.Semicolon && peek() != Token.End) {
                next()
            }
            next() // Skip semicolon
            return Statement.Expr(Expression.Integer(0)) // FIXME this is a hack
        }
    }

    // ||
    private fun expr(): Expression {
        var lhs = expr2()

        var t = peek()
        while (t == Token.Or) {
            next()
            lhs = Expression.Binop(BinaryOperator.OR, lhs, expr2())
            t = peek()
        }
        return lhs
    }

    // &&
    private fun expr2(): Expression {
        var lhs = expr3()

        var t = peek()
        while (t == Token.And) {
            next()
            lhs = Expression.Binop(BinaryOperator.AND, lhs, expr3())
            t = peek()
        }
        return lhs
    }

    // == !=
    private fun expr3(): Expression {
        var lhs = expr4()

        var t = peek()
        while (t == Token.EqualityOperator || t == Token.NotEqualOperator) {
            next()
            lhs = Expression.Binop(BinaryOperator.fromToken(t), lhs, expr4())
            t = peek()
        }
        return lhs
    }

    // < <= > >=
    private fun expr4(): Expression {
        var lhs = expr5()

        var t = peek()
        while (t == Token.Lt || t == Token.Lte || t == Token.Gt || t == Token.Gte) {
            next()
            lhs = Expression.Binop(BinaryOperator.fromToken(t), lhs, expr5())
            t = peek()
        }
        return lhs
    }

    // + -
    private fun expr5(): Expression {
        var lhs = expr6()

        var t = peek()
        while (t == Token.Plus || t == Token.Minus) {
            next()
            lhs = Expression.Binop(BinaryOperator.fromToken(t), lhs, expr6())
            t = peek()
        }
        return lhs
    }

    // * /
    private fun expr6(): Expression {
        var lhs = factor()

        var t = peek()
        while (t == Token.Mul || t == Token.Div) {
            next()
            lhs = Expression.Binop(BinaryOperator.fromToken(t), lhs, factor())
            t = peek()
        }
        return lhs
    }


    private fun factor(): Expression {
        // <integer> | <bool> | <identifier> | <function call> | ( <expr> ) | -<factor> | !<factor>
        val span = nextSpan()
        return when (val t = span.item) {
            is Token.Integer -> {
                Expression.Integer(t.value)
            }
            is Token.Bool -> {
                Expression.Boolean(t.value)
            }
            is Token.Identifier -> {
                if (peek() == Token.OpenParen) { // Function call
                    next()
                    val args = mutableListOf<Expression>()
                    while (true) {
                        if (peek() == Token.CloseParen) {
                            break
                        }
                        args.add(expr())
                        if (peek() == Token.Comma) {
                            next()
                        }
                    }
                    expect(Token.CloseParen)
                    Expression.Call(t.identifier, args.toTypedArray())
                } else { // Variable
                    Expression.Variable(t.identifier)
                }
            }
            Token.OpenParen -> {
                val expr = expr()
                expect(Token.CloseParen)
                expr
            }
            Token.Minus -> {
                Expression.UnaryMinus(factor())
            }
            Token.Exclamation -> {
                Expression.Negate(factor())
            }
            else -> {
                errorReporter.report(span, "Unexpected token '$t'")
                throw ParsingException("Unexpected token '$t'")
            }
        }
    }

    private fun next(): Token {
        return nextSpan().item
    }

    private fun nextSpan(): Span<Token> {
        if (index >= tokens.size) {
            return Span(Location(source.length, source.length), Token.End)
        }
        return tokens[index++]
    }

    private fun expect(expected: Token): Token {
        val got = nextSpan()
        if (got.item != expected) {
            errorReporter.report(got, "Expected token $expected")
            throw ParsingException(expected, got.item)
        }
        return got.item
    }

    private fun expectIdentifier(): Token.Identifier {
        val got = nextSpan()
        if (got.item is Token.Identifier) {
            return got.item
        }
        errorReporter.report(got, "Expected identifier")
        throw ParsingException("Expected identifier but got '${got.item}'")
    }

    private fun peek(): Token {
        if (index >= tokens.size) {
            return Token.End
        }
        return tokens[index].item
    }

    private fun peek2(): Token {
        if (index + 1 >= tokens.size) {
            return Token.End
        }
        return tokens[index + 1].item
    }

    private val tokens: Array<Span<Token>> = scan(source)
    private var index = 0

    private val errorReporter = ErrorReporter(filename, source)
}