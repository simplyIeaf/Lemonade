package com.leaf.lemonade.lua

/**
 * Converts common Luau syntax to Lua 5.2 so LuaJ can run it:
 * type annotations / type declarations / casts, compound assignment (+= etc), `continue`,
 * string interpolation, if-expressions, floor division and numeric literal extensions.
 * Line numbers are preserved. If the source can't be parsed, it is returned unchanged.
 */
object LuauTranspiler {
    private enum class K { NAME, KEYWORD, NUMBER, STRING, OP, EOF }

    private class Tok(val k: K, val text: String, val trivia: String) {
        var out: String = text
        var prefix = ""
        var suffix = ""
        fun isOp(s: String) = k == K.OP && text == s
        fun isKw(s: String) = k == K.KEYWORD && text == s
        fun isName(s: String) = k == K.NAME && text == s
    }

    private class ParseError : Exception()

    private val keywords = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil",
        "not", "or", "repeat", "return", "then", "true", "until", "while",
    )
    private val ops = listOf(
        "...", "..=", "//=", "..", "==", "~=", "<=", ">=", "//", "::", "->", "+=", "-=", "*=", "/=", "%=", "^=",
        "+", "-", "*", "/", "%", "^", "#", "&", "|", "~", "<", ">", "=", "(", ")", "{", "}", "[", "]", ";", ":", ",", ".", "?", "@", "!",
    )

    /** Where the last failed transpile stopped (tests and diagnostics). */
    @Volatile var lastFailure: String? = null

    /**
     * [hoistTopLocals]: turn the chunk's top-level `local`s into variables of the script's own
     * environment. Used when LuaJ refuses a function for having more than 60 upvalues (Luau
     * allows 200), e.g. React-Lua's reconciler; those names then aren't upvalues at all.
     */
    fun transpile(src: String, hoistTopLocals: Boolean = false): String {
        var parser: Parser? = null
        var toks: ArrayList<Tok>? = null
        return try {
            toks = lex(src)
            parser = Parser(toks, hoistTopLocals)
            parser.run()
            val sb = StringBuilder(src.length + 64)
            for (t in toks) {
                sb.append(t.trivia)
                sb.append(t.prefix)
                sb.append(t.out)
                sb.append(t.suffix)
            }
            sb.toString()
        } catch (e: Exception) {
            if (e !is ParseError && e !is IndexOutOfBoundsException) throw e
            val tk = toks; val pp = parser?.p ?: 0
            lastFailure = if (tk == null) null else {
                val line = tk.take(pp).sumOf { t -> t.trivia.count { it == '\n' } + t.text.count { it == '\n' } } + 1
                "line $line near: " + tk.subList((pp - 6).coerceAtLeast(0), (pp + 4).coerceAtMost(tk.size)).joinToString(" ") { it.text }
            }
            src
        }
    }

    // ------------------------------------------------------------------ lexer

    /** Lua 5.2 rejects "[[" inside a level-0 long bracket; Luau doesn't. Re-level such brackets. */
    private fun relevel(text: String, prefix: String): String {
        val body0 = text.removePrefix(prefix)
        if (!body0.startsWith("[[") || !body0.endsWith("]]")) return text
        val inner = body0.substring(2, body0.length - 2)
        if (!inner.contains("[[")) return text
        var lvl = 1
        while (inner.contains("]" + "=".repeat(lvl) + "]")) lvl++
        val eq = "=".repeat(lvl)
        return "$prefix[$eq[$inner]$eq]"
    }

    private fun lex(s: String): ArrayList<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = s.length
        val triv = StringBuilder()
        fun longBracketLevel(at: Int): Int {
            if (at >= n || s[at] != '[') return -1
            var j = at + 1; var lvl = 0
            while (j < n && s[j] == '=') { lvl++; j++ }
            return if (j < n && s[j] == '[') lvl else -1
        }
        fun skipLong(at: Int, lvl: Int): Int {
            val close = "]" + "=".repeat(lvl) + "]"
            val e = s.indexOf(close, at + lvl + 2)
            return if (e < 0) n else e + close.length
        }
        while (true) {
            // trivia
            while (i < n) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C') { triv.append(c); i++; continue }
                if (c == '-' && i + 1 < n && s[i + 1] == '-') {
                    val lvl = longBracketLevel(i + 2)
                    val e = if (lvl >= 0) skipLong(i + 2, lvl) else s.indexOf('\n', i).let { if (it < 0) n else it }
                    val comment = s.substring(i, e)
                    triv.append(if (lvl == 0) relevel(comment, "--") else comment); i = e; continue
                }
                break
            }
            if (i >= n) { out.add(Tok(K.EOF, "", triv.toString())); return out }
            val tr = triv.toString(); triv.setLength(0)
            val c = s[i]
            when {
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    val w = s.substring(i, j)
                    out.add(Tok(if (w in keywords) K.KEYWORD else K.NAME, w, tr)); i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit()) -> {
                    var j = i
                    if (c == '0' && i + 1 < n && (s[i + 1] == 'x' || s[i + 1] == 'X' || s[i + 1] == 'b' || s[i + 1] == 'B')) {
                        j += 2
                        while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    } else {
                        while (j < n && (s[j].isDigit() || s[j] == '.' || s[j] == '_')) j++
                        if (j < n && (s[j] == 'e' || s[j] == 'E')) {
                            j++
                            if (j < n && (s[j] == '+' || s[j] == '-')) j++
                            while (j < n && s[j].isDigit()) j++
                        }
                    }
                    var num = s.substring(i, j).replace("_", "")
                    if (num.startsWith("0b") || num.startsWith("0B")) num = (num.substring(2).toLongOrNull(2) ?: 0L).toString()
                    val t = Tok(K.NUMBER, s.substring(i, j), tr); t.out = num
                    out.add(t); i = j
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && s[j] != c) { if (s[j] == '\\') j++; if (j < n && s[j] == '\n' && s[j - 1] != '\\') break; j++ }
                    j = minOf(j + 1, n)
                    out.add(Tok(K.STRING, s.substring(i, j), tr)); i = j
                }
                c == '[' && longBracketLevel(i) >= 0 -> {
                    val lvl = longBracketLevel(i)
                    val e = skipLong(i, lvl)
                    val t = Tok(K.STRING, s.substring(i, e), tr)
                    if (lvl == 0) t.out = relevel(t.text, "")
                    out.add(t); i = e
                }
                c == '`' -> {
                    val (e, converted) = interpolated(s, i)
                    val t = Tok(K.STRING, s.substring(i, e), tr); t.out = converted
                    out.add(t); i = e
                }
                else -> {
                    val op = ops.firstOrNull { s.startsWith(it, i) } ?: c.toString()
                    out.add(Tok(K.OP, op, tr)); i += op.length
                }
            }
        }
    }

    /** `a {b} c` -> ("a " .. tostring(b) .. " c") */
    private fun interpolated(s: String, start: Int): Pair<Int, String> {
        val parts = ArrayList<String>()
        val lit = StringBuilder()
        var i = start + 1
        fun flush() {
            if (lit.isNotEmpty()) { parts.add("\"" + lit.toString() + "\""); lit.setLength(0) }
        }
        while (i < s.length && s[i] != '`') {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val nx = s[i + 1]
                    when (nx) {
                        '`', '{', '}' -> lit.append(nx)
                        '\n' -> lit.append("\\n")
                        else -> { lit.append('\\'); lit.append(nx) }
                    }
                    i += 2
                }
                c == '{' -> {
                    flush()
                    var depth = 1; var j = i + 1
                    while (j < s.length && depth > 0) {
                        when (s[j]) { '{' -> depth++; '}' -> depth--; '"', '\'' -> { val q = s[j]; j++; while (j < s.length && s[j] != q) { if (s[j] == '\\') j++; j++ } } }
                        if (depth > 0) j++
                    }
                    val expr = s.substring(i + 1, minOf(j, s.length))
                    parts.add("tostring(" + transpileExpr(expr) + ")")
                    i = j + 1
                }
                c == '"' -> { lit.append("\\\""); i++ }
                c == '\n' -> { lit.append("\\n"); i++ }
                else -> { lit.append(c); i++ }
            }
        }
        flush()
        val end = minOf(i + 1, s.length)
        val body = if (parts.isEmpty()) "\"\"" else parts.joinToString(" .. ")
        return end to "($body)"
    }

    private fun transpileExpr(e: String): String {
        val r = transpile("return $e")
        return if (r.startsWith("return ")) r.substring(7) else e
    }

    // ------------------------------------------------------------------ parser

    private class LoopCtx(val id: Int) { var usesContinue = false }

    private class Parser(val t: List<Tok>, val hoist: Boolean = false) {
        var p = 0
        var labelCounter = 0
        /** Block nesting (1 = the chunk's own top level). */
        var depth = 0
        val loops = ArrayList<LoopCtx?>() // null marks a function boundary

        fun peek(o: Int = 0) = t[minOf(p + o, t.size - 1)]
        fun next(): Tok = t[p].also { if (p < t.size - 1) p++ }
        fun atEof() = peek().k == K.EOF
        fun expectOp(s: String): Tok { if (!peek().isOp(s)) throw ParseError(); return next() }
        fun expectKw(s: String): Tok { if (!peek().isKw(s)) throw ParseError(); return next() }
        fun expectName(): Tok { if (peek().k != K.NAME) throw ParseError(); return next() }

        fun drop(from: Int, to: Int) {
            for (i in from until to) {
                val tk = t[i]
                tk.out = tk.text.filter { it == '\n' }
                tk.prefix = ""; tk.suffix = ""
            }
        }

        fun run() {
            block()
            if (!atEof()) throw ParseError()
        }

        fun blockEnd(): Boolean {
            val k = peek()
            return k.k == K.EOF || k.isKw("end") || k.isKw("else") || k.isKw("elseif") || k.isKw("until")
        }

        fun block() {
            depth++
            while (!blockEnd()) {
                if (peek().isKw("return")) {
                    next()
                    if (!blockEnd() && !peek().isOp(";")) exprList()
                    if (peek().isOp(";")) next()
                    continue
                }
                statement()
            }
            depth--
        }

        fun loopBody(endKw: String): Tok {
            val ctx = LoopCtx(++labelCounter)
            loops.add(ctx)
            val bodyStart = p
            block()
            loops.removeAt(loops.size - 1)
            val e = expectKw(endKw)
            if (ctx.usesContinue) {
                if (endKw == "end") {
                    // `do <body> end ::label::` — a body ending in `return` can't be followed by
                    // a label directly in Lua 5.2 ("return must be the last statement").
                    t[bodyStart].prefix = "do " + t[bodyStart].prefix
                    e.prefix = e.prefix + " end ::__continue${ctx.id}:: "
                } else e.prefix = "::__continue${ctx.id}:: "
            }
            return e
        }

        fun statement() {
            val k = peek()
            when {
                k.isOp(";") -> next()
                k.isKw("if") -> {
                    next(); expr(); expectKw("then"); block()
                    while (peek().isKw("elseif")) { next(); expr(); expectKw("then"); block() }
                    if (peek().isKw("else")) { next(); block() }
                    expectKw("end")
                }
                k.isKw("while") -> { next(); expr(); expectKw("do"); loopBody("end") }
                k.isKw("do") -> { next(); block(); expectKw("end") }
                k.isKw("for") -> {
                    next()
                    nameWithType()
                    if (peek().isOp("=")) {
                        next(); exprList()
                    } else {
                        while (peek().isOp(",")) { next(); nameWithType() }
                        expectKw("in")
                        // Luau generalized iteration: `for k, v in t do` -> `for k, v in __iter(t) do`
                        val first = p
                        expr()
                        if (peek().isOp(",")) { while (peek().isOp(",")) { next(); expr() } }
                        else { t[first].prefix = "__iter(" + t[first].prefix; t[p - 1].suffix += ")" }
                    }
                    expectKw("do")
                    loopBody("end")
                }
                k.isKw("repeat") -> {
                    next()
                    loopBody("until")
                    expr()
                }
                k.isKw("function") -> {
                    next()
                    expectName()
                    while (peek().isOp(".") || peek().isOp(":")) { next(); expectName() }
                    funcBody()
                }
                k.isKw("local") -> {
                    val localTok = next()
                    val hoistThis = hoist && depth == 1
                    if (hoistThis) localTok.out = ""
                    if (peek().isKw("function")) { next(); expectName(); funcBody() }
                    else {
                        localName()
                        while (peek().isOp(",")) { next(); localName() }
                        if (peek().isOp("=")) { next(); exprList() }
                        else if (hoistThis) t[p - 1].suffix += " = nil"
                    }
                }
                k.isKw("break") -> next()
                k.isKw("goto") -> { next(); expectName() }
                k.isOp("::") -> { next(); expectName(); expectOp("::") }
                k.isName("continue") && isStatementWord() -> {
                    val tok = next()
                    val ctx = loops.lastOrNull() ?: throw ParseError()
                    ctx.usesContinue = true
                    tok.out = "goto __continue${ctx.id}"
                }
                k.isName("type") && peek(1).k == K.NAME && (peek(2).isOp("=") || peek(2).isOp("<")) -> typeDecl(p)
                k.isName("export") && peek(1).isName("type") -> typeDecl(p)
                else -> exprStatement()
            }
        }

        fun isStatementWord(): Boolean {
            val n = peek(1)
            return !(n.isOp("(") || n.isOp("=") || n.isOp(".") || n.isOp("[") || n.isOp(":") || n.isOp(",") || n.k == K.STRING || n.isOp("{") ||
                n.isOp("+=") || n.isOp("-="))
        }

        fun typeDecl(start: Int) {
            if (peek().isName("export")) next()
            next() // type
            expectName()
            if (peek().isOp("<")) skipAngle()
            expectOp("=")
            type()
            drop(start, p)
        }

        fun nameWithType() {
            expectName()
            if (peek().isOp(":")) { val s = p; next(); type(); drop(s, p) }
        }

        fun localName() {
            expectName()
            if (peek().isOp("<")) { val s = p; skipAngle(); drop(s, p) }
            if (peek().isOp(":")) { val s = p; next(); type(); drop(s, p) }
        }

        fun funcBody() {
            if (peek().isOp("<")) { val s = p; skipAngle(); drop(s, p) }
            expectOp("(")
            while (!peek().isOp(")")) {
                if (peek().isOp("...")) {
                    next()
                    if (peek().isOp(":")) { val s = p; next(); type(); drop(s, p) }
                } else nameWithType()
                if (peek().isOp(",")) next() else break
            }
            expectOp(")")
            if (peek().isOp(":")) { val s = p; next(); returnType(); drop(s, p) }
            loops.add(null)
            block()
            loops.removeAt(loops.size - 1)
            expectKw("end")
        }

        // ---------------- types (skipped)

        fun skipAngle() {
            expectOp("<")
            var depth = 1
            while (depth > 0 && !atEof()) {
                val k = next()
                if (k.isOp("<")) depth++
                else if (k.isOp(">")) depth--
                else if (k.isOp(">=")) depth--
            }
        }

        fun skipBalanced(open: String, close: String) {
            expectOp(open)
            var depth = 1
            while (depth > 0 && !atEof()) {
                val k = next()
                if (k.isOp(open)) depth++ else if (k.isOp(close)) depth--
            }
        }

        fun returnType() {
            if (peek().isOp("(")) {
                skipBalanced("(", ")")
                if (peek().isOp("->")) { next(); type() }
                while (peek().isOp("?")) next()
                while (peek().isOp("|") || peek().isOp("&")) { next(); simpleType() }
            } else type()
        }

        fun type() {
            if (peek().isOp("|") || peek().isOp("&")) next()
            simpleType()
            while (peek().isOp("|") || peek().isOp("&")) { next(); simpleType() }
        }

        fun simpleType() {
            val k = peek()
            when {
                k.isOp("<") -> { skipAngle(); simpleType() }
                k.isOp("(") -> {
                    skipBalanced("(", ")")
                    if (peek().isOp("->")) { next(); type() }
                }
                k.isOp("{") -> skipBalanced("{", "}")
                k.isName("typeof") -> { next(); skipBalanced("(", ")") }
                k.isOp("...") -> { next(); simpleType() }
                k.k == K.NAME || k.isKw("nil") || k.isKw("true") || k.isKw("false") || k.k == K.STRING -> {
                    next()
                    while (peek().isOp(".")) { next(); expectName() }
                    if (peek().isOp("<")) skipAngle()
                    // A generic type pack: `() -> T...`
                    if (peek().isOp("...")) next()
                }
                else -> throw ParseError()
            }
            while (peek().isOp("?")) next()
        }

        // ---------------- expressions

        fun exprList() { expr(); while (peek().isOp(",")) { next(); expr() } }

        fun exprStatement() {
            val start = p
            suffixedExpr()
            val k = peek()
            when {
                k.isOp("=") || k.isOp(",") -> {
                    while (peek().isOp(",")) { next(); suffixedExpr() }
                    expectOp("="); exprList()
                }
                k.k == K.OP && k.text.length >= 2 && k.text.endsWith("=") && k.text !in setOf("==", "~=", "<=", ">=") -> {
                    val opTok = next()
                    val base = opTok.text.dropLast(1)
                    val lhs = (start until p - 1).joinToString(" ") { t[it].out }
                    val rhsStart = p
                    expr()
                    val last = t[p - 1]
                    if (base == "//") {
                        opTok.out = "= math.floor($lhs / ("
                        last.suffix += "))"
                    } else {
                        opTok.out = "= $lhs $base ("
                        last.suffix += ")"
                    }
                    if (rhsStart == p) throw ParseError()
                }
                else -> {}
            }
        }

        fun primaryExpr() {
            val k = peek()
            when {
                k.k == K.NAME -> next()
                k.isOp("(") -> { next(); expr(); expectOp(")") }
                else -> throw ParseError()
            }
        }

        fun suffixedExpr() {
            primaryExpr()
            while (true) {
                val k = peek()
                when {
                    k.isOp(".") -> { next(); if (peek().k == K.NAME || peek().k == K.KEYWORD) next() else throw ParseError() }
                    k.isOp("[") -> { next(); expr(); expectOp("]") }
                    k.isOp(":") -> { next(); expectName(); callArgs() }
                    k.isOp("(") || k.isOp("{") || k.k == K.STRING -> callArgs()
                    else -> return
                }
            }
        }

        fun callArgs() {
            val k = peek()
            when {
                k.k == K.STRING -> next()
                k.isOp("{") -> table()
                k.isOp("(") -> {
                    next()
                    if (!peek().isOp(")")) exprList()
                    expectOp(")")
                }
                else -> throw ParseError()
            }
        }

        fun table() {
            expectOp("{")
            while (!peek().isOp("}")) {
                if (peek().isOp("[")) { next(); expr(); expectOp("]"); expectOp("="); expr() }
                else if (peek().k == K.NAME && peek(1).isOp("=")) { next(); next(); expr() }
                else expr()
                if (peek().isOp(",") || peek().isOp(";")) next() else break
            }
            expectOp("}")
        }

        fun simpleExpr() {
            val k = peek()
            when {
                k.k == K.NUMBER || k.k == K.STRING || k.isKw("nil") || k.isKw("true") || k.isKw("false") || k.isOp("...") -> next()
                k.isOp("{") -> table()
                k.isKw("function") -> { next(); funcBody() }
                k.isKw("if") -> ifExpr()
                else -> suffixedExpr()
            }
            if (peek().isOp("::")) { val s = p; next(); type(); drop(s, p) }
        }

        fun ifExpr() {
            val ifTok = next()
            ifTok.out = "(function() if"
            expr()
            expectKw("then").out = "then return"
            expr()
            while (peek().isKw("elseif")) {
                next(); expr()
                expectKw("then").out = "then return"
                expr()
            }
            expectKw("else").out = "else return"
            expr()
            t[p - 1].suffix += " end end)()"
        }

        private fun binPrio(k: Tok): Pair<Int, Int>? {
            if (k.k == K.KEYWORD) return when (k.text) { "or" -> 1 to 1; "and" -> 2 to 2; else -> null }
            if (k.k != K.OP) return null
            return when (k.text) {
                "<", ">", "<=", ">=", "~=", "==" -> 3 to 3
                ".." -> 9 to 8
                "+", "-" -> 10 to 10
                "*", "/", "//", "%" -> 11 to 11
                "^" -> 14 to 13
                else -> null
            }
        }

        fun expr() = subExpr(0)

        fun subExpr(limit: Int) {
            val start = p
            val k = peek()
            if (k.isKw("not") || k.isOp("-") || k.isOp("#")) { next(); subExpr(12) } else simpleExpr()
            while (true) {
                val op = peek()
                val pr = binPrio(op) ?: break
                if (pr.first <= limit) break
                next()
                subExpr(pr.second)
                if (op.isOp("//")) {
                    op.out = "/"
                    t[start].prefix = "math.floor(" + t[start].prefix
                    t[p - 1].suffix += ")"
                }
            }
        }
    }
}
