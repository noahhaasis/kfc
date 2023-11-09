sealed class Token {

    data class Identifier(val identifier: String) : Token()

    data class Keyword(val keyword: String): Token()

    data class Integer(val value: Int): Token()

    data class Bool(val value: Boolean): Token()

    object Semicolon : Token()

    object OpenCurly : Token()

    object CloseCurly : Token()

    object Colon : Token()

    object Comma : Token()

    object OpenParen : Token()

    object CloseParen : Token()

    object Minus: Token()

    object Plus: Token()

    object Mul: Token()

    object Div: Token()

    object Equal: Token()

    object PlusEqual: Token()

    object MinusEqual: Token()

    object TimesEqual: Token()

    object DivEqual: Token()

    object Lt: Token()

    object Lte: Token()

    object Gt: Token()

    object Gte: Token()

    object EqualityOperator: Token()

    object NotEqualOperator: Token()

    object And: Token()

    object Or: Token()

    object Exclamation: Token()

    object End: Token()

}

class ScanException(message: String): RuntimeException(message)

fun scan(source: String): Array<Token> {
    // TODO:
    // Check for array bounds
    val keywords = arrayOf("fn", "if", "while", "return", "let", "else")
    val res = mutableListOf<Token>()

    var index = 0
    while (index < source.length) {
        // Skip whitespace
        while (index < source.length && source[index].isWhitespace()) {
            index++;
        }
        if (index >= source.length)
            break;

        var c = source[index]

        val t: Token = when (c) {
            '{' -> {index++; Token.OpenCurly}
            '}' -> {index++; Token.CloseCurly}
            '(' -> {index++; Token.OpenParen}
            ')' -> {index++; Token.CloseParen}
            ':' -> {index++; Token.Colon}
            ';' -> {index++; Token.Semicolon}
            ',' -> {index++; Token.Comma}
            '*' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.TimesEqual
                } else
                    Token.Mul
            }
            '/' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.DivEqual
                } else
                    Token.Div
            }
            '-' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.MinusEqual
                } else
                    Token.Minus
            }
            '+' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.PlusEqual
                } else
                    Token.Plus
            }
            '!' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.NotEqualOperator
                } else
                    Token.Exclamation
            }
            '=' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.EqualityOperator
                } else
                    Token.Equal
            }
            '>' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.Gte
                } else
                    Token.Gt
            }
            '<' -> {
                index++
                if (index < source.length && source[index] == '=') {
                    index++
                    Token.Lte
                } else
                    Token.Lt
            }
            '&' -> {
                index++
                if (index < source.length && source[index] == '&') {
                    index++
                    Token.And
                } else
                    throw ScanException("Unexpected character $c");
            }
            '|' -> {
                index++
                if (index < source.length && source[index] == '|') {
                    index++
                    Token.Or
                } else
                    throw ScanException("Unexpected character $c");
            }
            else -> {
                if (c.isDigit()) {
                    var int = 0
                    while (c.isDigit()) {
                        int *= 10
                        int += c.digitToInt()

                        c = source[++index];
                    }

                    Token.Integer(int)
                } else if (c.isLetter()) {
                    val sb = StringBuilder()
                    sb.append(c)

                    c = source[++index]
                    while (c.isLetterOrDigit()) {
                        sb.append(c)
                        c = source[++index];
                    }
                    val literal = sb.toString();

                    if (literal in keywords) {
                        Token.Keyword(literal)
                    } else if (literal == "true" ) {
                        Token.Bool(true)
                    } else if (literal == "false") {
                        Token.Bool(false)
                    } else {
                        Token.Identifier(literal)
                    }
                } else {
                    throw ScanException("Unexpected character $c");
                }
            }
        }
        res.add(t)
    }
    res.add(Token.End)

    return res.toTypedArray()
}