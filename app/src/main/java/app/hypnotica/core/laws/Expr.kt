package app.hypnotica.core.laws

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A tiny arithmetic expression compiler.
 *
 * Any numeric field in Advanced mode accepts a formula, and a force law is an
 * expression in r, m1 and m2. Parsing happens once; evaluation allocates nothing,
 * so an expression may be evaluated per particle per step.
 *
 * Supported: + - * / % ^, unary minus, parentheses, variables, and the functions
 * listed in [FUNCTIONS]. Unknown names evaluate to 0 rather than throwing, so a
 * half-typed formula never crashes a running sim.
 */
class Expr private constructor(private val root: Node, val source: String) {

    /** Variable lookup. Returning NaN means "unknown". */
    fun interface Vars {
        fun value(name: String): Float
    }

    fun eval(vars: Vars): Float {
        val v = root.eval(vars)
        return if (v.isNaN() || v.isInfinite()) 0f else v
    }

    fun evalOr(default: Float, vars: Vars): Float {
        val v = root.eval(vars)
        return if (v.isNaN() || v.isInfinite()) default else v
    }

    override fun toString(): String = source

    // ---------------------------------------------------------------- nodes

    internal abstract class Node {
        abstract fun eval(vars: Vars): Float
    }

    private class Const(val v: Float) : Node() {
        override fun eval(vars: Vars) = v
    }

    private class Var(val name: String) : Node() {
        override fun eval(vars: Vars): Float {
            val v = vars.value(name)
            return if (v.isNaN()) 0f else v
        }
    }

    private class Unary(val op: Char, val a: Node) : Node() {
        override fun eval(vars: Vars): Float {
            val x = a.eval(vars)
            return if (op == '-') -x else x
        }
    }

    private class Binary(val op: Char, val a: Node, val b: Node) : Node() {
        override fun eval(vars: Vars): Float {
            val x = a.eval(vars)
            val y = b.eval(vars)
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                '/' -> if (abs(y) < 1e-12f) 0f else x / y
                '%' -> if (abs(y) < 1e-12f) 0f else x % y
                '^' -> x.toDouble().pow(y.toDouble()).toFloat()
                else -> 0f
            }
        }
    }

    private class Call(val name: String, val args: List<Node>) : Node() {
        override fun eval(vars: Vars): Float {
            val n = args.size
            val a = if (n > 0) args[0].eval(vars) else 0f
            val b = if (n > 1) args[1].eval(vars) else 0f
            val c = if (n > 2) args[2].eval(vars) else 0f
            return when (name) {
                "sin" -> sin(a)
                "cos" -> cos(a)
                "tan" -> tan(a)
                "asin" -> asin(a.coerceIn(-1f, 1f))
                "acos" -> acos(a.coerceIn(-1f, 1f))
                "atan" -> atan(a)
                "atan2" -> atan2(a, b)
                "abs" -> abs(a)
                "sqrt" -> if (a < 0f) 0f else sqrt(a)
                "exp" -> exp(a)
                "log" -> if (a <= 0f) 0f else ln(a)
                "log10" -> if (a <= 0f) 0f else log10(a)
                "floor" -> floor(a)
                "ceil" -> ceil(a)
                "round" -> kotlin.math.round(a)
                "sign" -> sign(a)
                "min" -> min(a, b)
                "max" -> max(a, b)
                "pow" -> a.toDouble().pow(b.toDouble()).toFloat()
                "clamp" -> a.coerceIn(min(b, c), max(b, c))
                "mix" -> a + (b - a) * c
                "step" -> if (b < a) 0f else 1f
                "smoothstep" -> {
                    val t = ((c - a) / (if (abs(b - a) < 1e-12f) 1e-12f else b - a)).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                else -> 0f
            }
        }
    }

    // --------------------------------------------------------------- parser

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Node {
            val n = parseExpr()
            skipSpace()
            if (pos < src.length) throw IllegalArgumentException("Unexpected '${src[pos]}' at $pos")
            return n
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            skipSpace()
            return if (pos < src.length) src[pos] else '\u0000'
        }

        private fun parseExpr(): Node {
            var left = parseTerm()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    pos++
                    left = Binary(c, left, parseTerm())
                } else return left
            }
        }

        private fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/' || c == '%') {
                    pos++
                    left = Binary(c, left, parseUnary())
                } else return left
            }
        }

        private fun parseUnary(): Node {
            val c = peek()
            if (c == '-') {
                pos++
                return Unary('-', parseUnary())
            }
            if (c == '+') {
                pos++
                return parseUnary()
            }
            return parsePower()
        }

        private fun parsePower(): Node {
            val base = parseAtom()
            if (peek() == '^') {
                pos++
                return Binary('^', base, parseUnary())
            }
            return base
        }

        private fun parseAtom(): Node {
            val c = peek()
            if (c == '(') {
                pos++
                val inner = parseExpr()
                if (peek() != ')') throw IllegalArgumentException("Missing ')' at $pos")
                pos++
                return inner
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter() || c == '_') return parseIdent()
            throw IllegalArgumentException("Unexpected '$c' at $pos")
        }

        private fun parseNumber(): Node {
            skipSpace()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos < src.length && src[pos].isDigit()) {
                    while (pos < src.length && src[pos].isDigit()) pos++
                } else pos = save
            }
            val text = src.substring(start, pos)
            val v = text.toFloatOrNull() ?: throw IllegalArgumentException("Bad number '$text'")
            return Const(v)
        }

        private fun parseIdent(): Node {
            skipSpace()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos).lowercase()
            if (peek() == '(') {
                pos++
                val args = ArrayList<Node>(3)
                if (peek() != ')') {
                    while (true) {
                        args.add(parseExpr())
                        if (peek() == ',') {
                            pos++
                            continue
                        }
                        break
                    }
                }
                if (peek() != ')') throw IllegalArgumentException("Missing ')' after $name")
                pos++
                if (name !in FUNCTIONS) throw IllegalArgumentException("Unknown function '$name'")
                return Call(name, args)
            }
            return when (name) {
                "pi" -> Const(Math.PI.toFloat())
                "e" -> Const(Math.E.toFloat())
                "tau" -> Const((Math.PI * 2.0).toFloat())
                else -> Var(name)
            }
        }
    }

    companion object {
        val FUNCTIONS = setOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "abs", "sqrt",
            "exp", "log", "log10", "floor", "ceil", "round", "sign", "min", "max",
            "pow", "clamp", "mix", "step", "smoothstep"
        )

        /** Throws IllegalArgumentException with a readable message on bad input. */
        fun compile(source: String): Expr {
            val trimmed = source.trim()
            require(trimmed.isNotEmpty()) { "Empty expression" }
            return Expr(Parser(trimmed).parse(), trimmed)
        }

        /** Null instead of throwing, for live-typed fields. */
        fun compileOrNull(source: String): Expr? = try {
            compile(source)
        } catch (e: Exception) {
            null
        }

        /** A constant expression, handy as a default. */
        fun constant(v: Float): Expr = Expr(Const(v), v.toString())

        val EMPTY_VARS = Vars { Float.NaN }
    }
}
