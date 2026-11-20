package com.andysocial.voidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The calculator's sums: + − × ÷ with the usual order (× and ÷ before + and −), brackets,
 * percent ("50%" is 0.5; "200 + 10%" is 220, like a shop calculator), and a leading minus.
 * BigDecimal throughout, so 0.1 + 0.2 is 0.3. Null for something that isn't a sum or ÷ 0.
 */
object Calc {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun eval(expr: String): BigDecimal? = runCatching {
        val p = Parser(expr.replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", ""))
        val v = p.sum()
        if (p.i != p.s.length) null else v
    }.getOrNull()

    /** The answer as people write it: no trailing zeros, at most 12 places, plain for big numbers. */
    fun format(v: BigDecimal?): String {
        if (v == null) return "Error"
        val r = v.round(MathContext(12, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val abs = r.abs()
        if (abs.signum() != 0 && (abs >= BigDecimal("1e15") || abs < BigDecimal("1e-9"))) return "%.6e".format(r.toDouble())
        val plain = r.toPlainString()
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }

    private class Parser(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun sum(): BigDecimal {
            var v = product()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val (r, pct) = productPct()
                val rhs = if (pct) v.multiply(r, mc) else r
                v = if (op == '+') v.add(rhs, mc) else v.subtract(rhs, mc)
            }
            return v
        }
        // a percent straight after + or − is a percent of the left side
        fun productPct(): Pair<BigDecimal, Boolean> {
            val start = i
            val f = factor(allowPct = false)
            if (peek() == '%' && (i + 1 == s.length || s[i + 1] in "+-)")) { i++; return f.divide(BigDecimal(100), mc) to true }
            i = start
            return product() to false
        }
        fun product(): BigDecimal {
            var v = factor(allowPct = true)
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = factor(allowPct = true)
                v = if (op == '*') v.multiply(r, mc) else { require(r.signum() != 0); v.divide(r, mc) }
            }
            return v
        }
        fun factor(allowPct: Boolean): BigDecimal {
            if (peek() == '-') { i++; return factor(allowPct).negate() }
            if (peek() == '+') { i++; return factor(allowPct) }
            var v = if (peek() == '(') {
                i++
                val inner = sum()
                if (peek() == ')') i++ // an unclosed bracket closes at the end
                inner
            } else {
                val st = i
                while (peek().isDigit() || peek() == '.') i++
                require(i > st)
                BigDecimal(s.substring(st, i).let { if (it.startsWith(".")) "0$it" else it })
            }
            if (allowPct) while (peek() == '%') { i++; v = v.divide(BigDecimal(100), mc) }
            return v
        }
    }

    /** Pure: what a key does to the line being typed. Returns the new line and whether to show the answer. */
    fun press(line: String, key: String): String {
        val ops = "+−×÷"
        val last = line.lastOrNull()
        return when (key) {
            "AC" -> ""
            "⌫" -> line.dropLast(1)
            "±" -> {
                // flip the sign of the last number
                val k = line.indexOfLast { !(it.isDigit() || it == '.' || it == '%') } + 1
                val num = line.substring(k)
                if (num.isEmpty()) line + "−"
                else if (k > 0 && line[k - 1] == '−' && (k == 1 || line[k - 2] in "$ops(")) line.substring(0, k - 1) + num
                else line.substring(0, k) + "−" + num
            }
            "( )" -> {
                val open = line.count { it == '(' } - line.count { it == ')' }
                if (open > 0 && last != null && (last.isDigit() || last == ')' || last == '%')) "$line)"
                else if (last != null && (last.isDigit() || last == ')')) "$line×(" else "$line("
            }
            "." -> {
                val k = line.indexOfLast { !(it.isDigit() || it == '.') } + 1
                if ('.' in line.substring(k)) line else if (k == line.length) line + "0." else "$line."
            }
            "%" -> if (last != null && (last.isDigit() || last == ')')) "$line%" else line
            in listOf("+", "−", "×", "÷") -> when {
                line.isEmpty() -> if (key == "−") "−" else line
                last != null && last in ops -> line.dropLast(1) + key
                last == '(' -> if (key == "−") line + key else line
                else -> line + key
            }
            else -> if (key.length == 1 && key[0].isDigit()) line + key else line
        }
    }
}

private val CalcRows = listOf(
    listOf("AC", "( )", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("±", "0", ".", "="),
)

/**
 * The calculator. One column of a display over the keys, sized from the room it gets: full
 * screen, or either half of the split standing up or lying down, the keys shrink and the
 * numbers shrink with them, so nothing is ever cut off.
 */
@Composable
fun CalculatorPane() {
    var line by rememberSaveable { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf<String?>(null) } // the answer after =
    var prev by rememberSaveable { mutableStateOf("") }
    val live = if (line.isEmpty()) null else Calc.eval(line)
    fun key(k: String) {
        when (k) {
            "=" -> {
                val v = Calc.eval(line) ?: run { shown = "Error"; return }
                prev = "$line ="
                line = v.stripTrailingZeros().toPlainString().replace('-', '−')
                shown = null
            }
            "⌫" -> { line = Calc.press(line, k); shown = null }
            else -> {
                // a digit straight after = starts a new sum; an operator carries on from the answer
                if (prev.isNotEmpty() && shown == null && line.isNotEmpty() && (k.firstOrNull()?.isDigit() == true || k == ".") && prev.endsWith("=")) { line = ""; prev = "" }
                else if (k !in listOf("AC")) prev = prev.takeIf { !it.endsWith("=") } ?: ""
                line = Calc.press(line, k)
                if (k == "AC") prev = ""
                shown = null
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "Calculator" }, contentAlignment = Alignment.Center) {
        val wide = maxWidth > maxHeight * 1.25f
        val gap = if (min(maxWidth, maxHeight) < 420.dp) 8.dp else 12.dp
        if (wide) {
            // lying down: display on the left, keys on the right
            val keysW = min(maxWidth * 0.56f, (maxHeight - gap * 4) / 5 * 4 + gap * 3)
            val key = calcKeySize(keysW, maxHeight, gap)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap * 2), verticalAlignment = Alignment.CenterVertically) {
                CalcDisplay(prev, line, shown, live, Modifier.weight(1f).height(key * 5 + gap * 4), key)
                CalcKeys(key, gap) { key(it) }
            }
        } else {
            val dispH = (maxHeight * 0.28f).coerceIn(90.dp, 220.dp)
            val key = calcKeySize(min(maxWidth, 560.dp), maxHeight - dispH - gap * 2, gap)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CalcDisplay(prev, line, shown, live, Modifier.width(key * 4 + gap * 3).height(dispH), key)
                Spacer(Modifier.height(gap * 2))
                CalcKeys(key, gap) { key(it) }
            }
        }
    }
}

/** Pure: the key size that fits 4 keys across and 5 down in this space. */
fun calcKeySize(w: Dp, h: Dp, gap: Dp): Dp =
    min((w - gap * 3) / 4, (h - gap * 4) / 5).coerceIn(28.dp, 104.dp)

/** Pure: the display's text size for this many characters in this width, never wider than the space. */
fun calcTextSize(chars: Int, widthDp: Float, maxSp: Float): Float =
    minOf(maxSp, widthDp / (chars.coerceAtLeast(1) * 0.62f)).coerceAtLeast(12f)

@Composable
private fun CalcDisplay(prev: String, line: String, shown: String?, live: BigDecimal?, modifier: Modifier, key: Dp) {
    BoxWithConstraints(
        modifier.clip(Chamfer(Radius.panel)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        val w = maxWidth.value
        val big = line.ifEmpty { "0" }
        val bigSp = calcTextSize(big.length, w, minOf(64f, maxHeight.value * 0.42f))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            VText(prev, size = calcTextSize(prev.length, w, 18f).sp, color = V.Muted, maxLines = 1, align = TextAlign.End, modifier = Modifier.semantics { contentDescription = "Last sum $prev" })
            Spacer(Modifier.weight(1f))
            VText(big, size = bigSp.sp, font = SansLight, color = V.White, maxLines = 1, align = TextAlign.End, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Display $big" })
            val sub = shown ?: if (live != null && line.any { it in "+−×÷%" }) "= ${Calc.format(live)}" else ""
            VText(sub, size = calcTextSize(sub.length, w, 22f).sp, color = if (shown == "Error") V.Coral else V.Teal, maxLines = 1, align = TextAlign.End,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Answer $sub" })
        }
    }
}

@Composable
private fun CalcKeys(key: Dp, gap: Dp, onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        CalcRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { k -> CalcKey(k, key, onKey) }
            }
        }
    }
}

@Composable
private fun CalcKey(k: String, size: Dp, onKey: (String) -> Unit) {
    val op = k in listOf("÷", "×", "−", "+")
    val eq = k == "="
    val fn = k in listOf("AC", "( )", "%", "±")
    val shape = Chamfer(size * 0.30f)
    Box(
        Modifier.size(size).clip(shape)
            .background(when { eq -> V.Teal.copy(alpha = 0.85f); op -> V.Teal.copy(alpha = 0.18f); fn -> Color.White.copy(alpha = 0.12f); else -> Color.White.copy(alpha = 0.07f) })
            .clickable { onKey(k) }
            .semantics { role = Role.Button; contentDescription = calcKeyName(k) },
        contentAlignment = Alignment.Center,
    ) {
        val s = (size.value * if (k.length > 1) 0.28f else 0.38f).sp
        VText(k, size = s, font = if (op || eq) SansMedium else SansLight, color = when { eq -> V.Bg; op -> V.Teal; else -> V.White }, maxLines = 1, align = TextAlign.Center)
    }
}

/** What a screen reader says for each key. */
fun calcKeyName(k: String): String = when (k) {
    "AC" -> "Clear"; "( )" -> "Brackets"; "%" -> "Percent"; "÷" -> "Divide"; "×" -> "Times"; "−" -> "Minus"; "+" -> "Plus"
    "±" -> "Plus or minus"; "." -> "Point"; "=" -> "Equals"; else -> k
}

@Suppress("unused") private val keepW = Modifier.widthIn()
