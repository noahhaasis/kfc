class ParsingException(message: String): java.lang.RuntimeException(message) {
    constructor(expected: Token, got: Token) : this("Expected token $expected but got $got") {}
}

class Parser(source: String) {

    fun parse(): Program {
        val functions = mutableListOf<Function>()
        while (peek() != Token.End) {
            functions.add(function())
        }
        return Program(functions.toTypedArray())
    }

    private fun function(): Function {
        expect(Token.Keyword("fn"))
        val name = expectIdentifier()
        expect(Token.OpenParen)

        val params = mutableListOf<Pair<String, String>>()
        while (peek() != Token.CloseParen) {
            val paramName = expectIdentifier()
            expect(Token.Colon)
            val paramType = expectIdentifier()
            params.add(Pair(paramName.identifier, paramType.identifier))
            if (peek() == Token.Comma) {
                next()
            }
        }
        expect(Token.CloseParen)
        expect(Token.Colon)
        val returnType = expectIdentifier()

        val body = block()
        return Function(name.identifier, returnType.identifier, params.toTypedArray(), body)
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
                    val statement = when(peek2) {
                        Token.Equal -> Statement.Assign(name.identifier, expr)
                        Token.PlusEqual -> Statement.Assign(name.identifier, Expression.Binop(BinaryOperator.ADD, Expression.Variable(name.identifier), expr))
                        Token.MinusEqual -> Statement.Assign(name.identifier, Expression.Binop(BinaryOperator.SUB, Expression.Variable(name.identifier), expr))
                        Token.TimesEqual -> Statement.Assign(name.identifier, Expression.Binop(BinaryOperator.MUL, Expression.Variable(name.identifier), expr))
                        Token.DivEqual -> Statement.Assign(name.identifier, Expression.Binop(BinaryOperator.DIV, Expression.Variable(name.identifier), expr))
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
        return when (val t = next()) {
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
                            break;
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
                throw ParsingException("Unexpected token $t")
            }
        }
    }

    private fun next(): Token {
        if (index >= tokens.size) {
            return Token.End
        }
        return tokens[index++];
    }

    private fun expect(expected: Token): Token {
        val got = next()
        if (got != expected) {
            throw ParsingException(expected, got)
        }
        return got

    }

    private fun expectIdentifier(): Token.Identifier {
        val got = next()
        if (got is Token.Identifier) {
            return got
        }
        throw ParsingException("Expected identifier but got $got")
    }

    private fun peek(): Token {
        if (index >= tokens.size) {
            return Token.End
        }
        return tokens[index]
    }

    private fun peek2(): Token {
        if (index + 1 >= tokens.size) {
            return Token.End
        }
        return tokens[index + 1]
    }

    private val tokens: Array<Token> = scan(source)
    private var index = 0;
}