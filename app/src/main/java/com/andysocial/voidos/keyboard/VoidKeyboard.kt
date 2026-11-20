package com.andysocial.voidos.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.ResourcesCompat
import com.andysocial.voidos.Haptics
import com.andysocial.voidos.R
import com.andysocial.voidos.TapSound
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 2.0.1: the VoidOS keyboard. A plain InputMethodService whose one view draws chamfered glass
 * keys in Manrope on the VoidOS navy, with the teal accent on the action key and the soft
 * 1.20.1 tick on every press. UK QWERTY, shift and caps, two symbol pages, a number pad for
 * number, phone and PIN fields, backspace that repeats while held, an action key that follows
 * the field (Go, Search, Send, Next, Done, return), arrows and a space-bar drag to move the
 * cursor, and long-press for accents. Root turns it on and makes it the default (Policy.kt).
 */
class VoidKeyboardService : InputMethodService() {
    companion object {
        const val ID = "com.andysocial.voidos/.keyboard.VoidKeyboardService"

        /** Turn the keyboard on and make it the default, through root, so nothing needs doing on the tablet. */
        fun makeDefault(c: Context) {
            val im = c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val cur = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            if (cur == ID && im?.enabledInputMethodList?.any { it.id == ID } == true) return
            com.andysocial.voidos.Su.run("ime enable $ID; ime set $ID; settings put secure default_input_method $ID; settings put secure show_ime_with_hard_keyboard 1")
        }
    }

    private var view: VoidKeyboardView? = null

    override fun onCreateInputView(): View = VoidKeyboardView(this).also { v ->
        v.listener = object : VoidKeyboardView.Listener {
            override fun text(s: String) { currentInputConnection?.commitText(s, 1) }
            override fun delete() {
                val ic = currentInputConnection ?: return
                if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
            }
            override fun action() {
                val ei = currentInputEditorInfo
                val act = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val multi = ei != null && ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                if (multi || act == EditorInfo.IME_ACTION_NONE || act == EditorInfo.IME_ACTION_UNSPECIFIED || ei?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0)
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else currentInputConnection?.performEditorAction(act)
            }
            override fun cursor(dx: Int) { sendDownUpKeyEvents(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
            override fun hide() { requestHideSelf(0) }
            override fun feedback() { runCatching { Haptics.tick(v) }; runCatching { TapSound.play() } }
        }
        view = v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.setup(info)
    }

    // the keyboard is short enough never to need Android's full-screen landscape editor
    override fun onEvaluateFullscreenMode() = false
}

/** The keys, drawn by hand so nothing of the stock keyboard shows. */
class VoidKeyboardView(ctx: Context) : View(ctx) {
    interface Listener { fun text(s: String); fun delete(); fun action(); fun cursor(dx: Int); fun hide(); fun feedback() }
    var listener: Listener? = null

    enum class Page { LETTERS, SYM1, SYM2, NUM }
    enum class Kind { CHAR, SHIFT, DEL, PAGE, SPACE, ACTION, LEFT, RIGHT, HIDE }
    data class Key(val label: String, val kind: Kind = Kind.CHAR, val w: Float = 1f, val out: String = label, val to: Page? = null)

    var page = Page.LETTERS; private set
    var shift = false; private set
    var caps = false; private set
    private var actionLabel = "return"
    private var lastShiftTap = 0L

    private val dens = resources.displayMetrics.density
    private val sans = runCatching { ResourcesCompat.getFont(ctx, R.font.manrope_regular) }.getOrNull() ?: Typeface.SANS_SERIF
    private val semi = runCatching { ResourcesCompat.getFont(ctx, R.font.manrope_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    // the VoidOS palette (Theme.kt)
    private val navy = 0xFF0B1424.toInt()
    private val teal = 0xFF52D4DC.toInt()
    private val text = 0xFFE9F1F7.toInt()
    private val muted = 0x99C4D2E0.toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dens * 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = text }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = muted }

    private fun rows(): List<List<Key>> = when (page) {
        Page.LETTERS -> listOf(
            "qwertyuiop".map { Key(it.toString()) },
            listOf(Key("", Kind.CHAR, 0.5f, "")) + "asdfghjkl".map { Key(it.toString()) } + listOf(Key("", Kind.CHAR, 0.5f, "")),
            listOf(Key("shift", Kind.SHIFT, 1.5f)) + "zxcvbnm".map { Key(it.toString()) } + listOf(Key("del", Kind.DEL, 1.5f)),
            listOf(Key("123", Kind.PAGE, 1.5f, to = Page.SYM1), Key(",", w = 1f), Key("", Kind.SPACE, 4.5f, " "), Key(".", w = 1f), Key("<", Kind.LEFT, 0.8f), Key(">", Kind.RIGHT, 0.8f), Key(actionLabel, Kind.ACTION, 1.9f)),
        )
        Page.SYM1 -> listOf(
            "1234567890".map { Key(it.toString()) },
            "-/:;()\u00a3&@\"".map { Key(it.toString()) },
            listOf(Key("#+=", Kind.PAGE, 1.5f, to = Page.SYM2)) + ".,?!'\u20ac$".map { Key(it.toString()) } + listOf(Key("del", Kind.DEL, 1.5f)),
            listOf(Key("ABC", Kind.PAGE, 1.5f, to = Page.LETTERS), Key("_"), Key("", Kind.SPACE, 4.5f, " "), Key("%"), Key("<", Kind.LEFT, 0.8f), Key(">", Kind.RIGHT, 0.8f), Key(actionLabel, Kind.ACTION, 1.9f)),
        )
        Page.SYM2 -> listOf(
            "[]{}#%^*+=".map { Key(it.toString()) },
            "_\\|~<>\u20ac$\u00a5\u2022".map { Key(it.toString()) },
            listOf(Key("123", Kind.PAGE, 1.5f, to = Page.SYM1)) + ".,?!'`\u00b0".map { Key(it.toString()) } + listOf(Key("del", Kind.DEL, 1.5f)),
            listOf(Key("ABC", Kind.PAGE, 1.5f, to = Page.LETTERS), Key("\u00a7"), Key("", Kind.SPACE, 4.5f, " "), Key("\u2026"), Key("<", Kind.LEFT, 0.8f), Key(">", Kind.RIGHT, 0.8f), Key(actionLabel, Kind.ACTION, 1.9f)),
        )
        Page.NUM -> listOf(
            listOf(Key("1"), Key("2"), Key("3"), Key("-", w = 0.8f)),
            listOf(Key("4"), Key("5"), Key("6"), Key("+", w = 0.8f)),
            listOf(Key("7"), Key("8"), Key("9"), Key("del", Kind.DEL, 0.8f)),
            listOf(Key("."), Key("0"), Key("<", Kind.LEFT, 0.5f), Key(">", Kind.RIGHT, 0.5f), Key(actionLabel, Kind.ACTION, 0.8f)),
        )
    }

    /** Long-press options: the UK accents (and a few symbols) in the order people reach for them. */
    private val accents = mapOf(
        "a" to "\u00e0\u00e1\u00e2\u00e4\u00e6\u00e3\u00e5", "e" to "\u00e8\u00e9\u00ea\u00eb\u0113", "i" to "\u00ec\u00ed\u00ee\u00ef",
        "o" to "\u00f2\u00f3\u00f4\u00f6\u00f5\u00f8\u0153", "u" to "\u00f9\u00fa\u00fb\u00fc", "c" to "\u00e7", "n" to "\u00f1", "s" to "\u00df",
        "y" to "\u00fd\u00ff", "." to "\u2026?!", "," to "'\";:", "-" to "\u2013\u2014", "0" to "\u00b0",
    )

    private data class Placed(val key: Key, val r: RectF)
    private var placed = listOf<Placed>()
    private var down: Placed? = null
    private var downX = 0f
    private var spaceDrag = 0f
    private var popup: List<Pair<String, RectF>> = emptyList()
    private var popupSel = -1
    private val h = Handler(Looper.getMainLooper())
    private val repeat = object : Runnable { override fun run() { listener?.delete(); listener?.feedback(); h.postDelayed(this, 55) } }
    private val longPress = Runnable { openAccents() }

    fun setup(info: EditorInfo?) {
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) Page.NUM else Page.LETTERS
        val act = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multi = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        actionLabel = if (multi) "return" else when (act) {
            EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "Search"; EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"; EditorInfo.IME_ACTION_DONE -> "Done"; EditorInfo.IME_ACTION_PREVIOUS -> "Back"
            else -> "return"
        }
        // sentence case for ordinary text fields, nothing for passwords, addresses and the like
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        shift = page == Page.LETTERS && (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 &&
            variation != InputType.TYPE_TEXT_VARIATION_PASSWORD && (info?.initialSelStart ?: 0) == 0
        caps = false
        placed = emptyList(); requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val rowH = (if (landscape) 52f else 58f) * dens
        setMeasuredDimension(w, (rowH * 4 + 10 * dens * 5).toInt())
    }

    private fun layoutKeys() {
        val gap = 8f * dens; val pad = 10f * dens
        val rs = rows()
        val rowH = (height - pad * 2 - gap * (rs.size - 1)) / rs.size
        // in landscape, and on the number pad, the keys keep a hand's width instead of stretching across the tablet
        val maxW = if (page == Page.NUM) min(width.toFloat(), 520f * dens) else min(width.toFloat(), 1100f * dens)
        val left0 = (width - maxW) / 2f
        val unit = (maxW - pad * 2 - gap * 9) / 10f
        val out = mutableListOf<Placed>()
        rs.forEachIndexed { ri, row ->
            val total = row.sumOf { it.w.toDouble() }.toFloat()
            val u = if (page == Page.NUM) (maxW - pad * 2 - gap * (row.size - 1)) / total else unit
            val rowW = row.sumOf { (it.w * u).toDouble() }.toFloat() + gap * (row.size - 1) - (if (page == Page.NUM) 0f else row.count { it.label.isEmpty() && it.kind == Kind.CHAR } * gap)
            var x = left0 + (maxW - rowW) / 2f
            val y = pad + ri * (rowH + gap)
            row.forEach { k ->
                val kw = k.w * u + (if (k.w > 1f && page != Page.NUM) (k.w - 1f) * gap else 0f)
                if (k.label.isEmpty() && k.kind == Kind.CHAR) { x += kw; return@forEach }
                out += Placed(k, RectF(x, y, x + kw, y + rowH))
                x += kw + gap
            }
        }
        placed = out
    }

    private fun chamfer(r: RectF, c: Float) = Path().apply {
        moveTo(r.left + c, r.top); lineTo(r.right - c, r.top); lineTo(r.right, r.top + c); lineTo(r.right, r.bottom - c)
        lineTo(r.right - c, r.bottom); lineTo(r.left + c, r.bottom); lineTo(r.left, r.bottom - c); lineTo(r.left, r.top + c); close()
    }

    override fun onDraw(canvas: Canvas) {
        if (placed.isEmpty()) layoutKeys()
        bgPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF0E1A2E.toInt(), navy, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // a hairline of light along the top edge, like the glass panels
        rimPaint.shader = null; rimPaint.color = 0x22FFFFFF
        canvas.drawLine(0f, 0.5f, width.toFloat(), 0.5f, rimPaint)
        val cut = 7f * dens
        for (p in placed) {
            val k = p.key; val pressed = down === p
            val special = k.kind != Kind.CHAR && k.kind != Kind.SPACE
            val action = k.kind == Kind.ACTION
            keyPaint.shader = null
            keyPaint.color = when {
                action -> if (pressed) 0xFF7FE2E8.toInt() else teal
                pressed -> 0x40FFFFFF
                (k.kind == Kind.SHIFT && (shift || caps)) -> 0x38FFFFFF
                special -> 0x14FFFFFF
                else -> 0x1FFFFFFF
            }
            val path = chamfer(p.r, cut)
            canvas.drawPath(path, keyPaint)
            rimPaint.shader = LinearGradient(0f, p.r.top, 0f, p.r.bottom, 0x40FFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(path, rimPaint)
            val cx = p.r.centerX(); val cy = p.r.centerY()
            when (k.kind) {
                Kind.SHIFT -> drawShift(canvas, cx, cy, caps)
                Kind.DEL -> drawDelete(canvas, cx, cy)
                Kind.LEFT, Kind.RIGHT -> drawChevron(canvas, cx, cy, k.kind == Kind.LEFT)
                Kind.SPACE -> { hintPaint.typeface = semi; hintPaint.textSize = 11f * dens; hintPaint.letterSpacing = 0.16f; canvas.drawText("VOID", cx, cy + 4f * dens, hintPaint) }
                else -> {
                    val upper = page == Page.LETTERS && k.kind == Kind.CHAR && (shift || caps)
                    val label = if (upper) k.label.uppercase() else k.label
                    labelPaint.typeface = if (special || action) semi else sans
                    labelPaint.textSize = (if (k.kind == Kind.CHAR) 22f else 15f) * dens
                    labelPaint.color = if (action) navy else text
                    canvas.drawText(label, cx, cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
                    if (k.kind == Kind.CHAR && accents.containsKey(k.label) && page == Page.LETTERS) {
                        canvas.drawCircle(p.r.right - 8f * dens, p.r.top + 8f * dens, 1.6f * dens, hintPaint.apply { shader = null })
                    }
                }
            }
        }
        if (popup.isNotEmpty()) drawPopup(canvas, cut)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private fun drawShift(c: Canvas, x: Float, y: Float, locked: Boolean) {
        iconPaint.color = if (shift || caps) teal else text; iconPaint.strokeWidth = 1.8f * dens
        val s = 8f * dens
        val p = Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x + s * 0.45f, y); lineTo(x + s * 0.45f, y + s * 0.8f); lineTo(x - s * 0.45f, y + s * 0.8f); lineTo(x - s * 0.45f, y); lineTo(x - s, y); close() }
        c.drawPath(p, iconPaint)
        if (locked) c.drawLine(x - s * 0.45f, y + s * 1.2f, x + s * 0.45f, y + s * 1.2f, iconPaint)
    }
    private fun drawDelete(c: Canvas, x: Float, y: Float) {
        iconPaint.color = text; iconPaint.strokeWidth = 1.8f * dens
        val s = 8f * dens
        val p = Path().apply { moveTo(x - s * 1.3f, y); lineTo(x - s * 0.5f, y - s * 0.8f); lineTo(x + s * 1.2f, y - s * 0.8f); lineTo(x + s * 1.2f, y + s * 0.8f); lineTo(x - s * 0.5f, y + s * 0.8f); close() }
        c.drawPath(p, iconPaint)
        c.drawLine(x - s * 0.05f, y - s * 0.35f, x + s * 0.65f, y + s * 0.35f, iconPaint)
        c.drawLine(x + s * 0.65f, y - s * 0.35f, x - s * 0.05f, y + s * 0.35f, iconPaint)
    }
    private fun drawChevron(c: Canvas, x: Float, y: Float, left: Boolean) {
        iconPaint.color = muted; iconPaint.strokeWidth = 1.8f * dens
        val s = 5f * dens; val d = if (left) 1 else -1
        c.drawLine(x + s * 0.5f * d, y - s, x - s * 0.5f * d, y, iconPaint)
        c.drawLine(x - s * 0.5f * d, y, x + s * 0.5f * d, y + s, iconPaint)
    }
    private fun drawPopup(c: Canvas, cut: Float) {
        val all = RectF(popup.first().second.left - 6 * dens, popup.first().second.top - 6 * dens, popup.last().second.right + 6 * dens, popup.first().second.bottom + 6 * dens)
        keyPaint.shader = null; keyPaint.color = 0xF0152338.toInt()
        c.drawPath(chamfer(all, cut * 1.3f), keyPaint)
        rimPaint.shader = null; rimPaint.color = 0x55FFFFFF
        c.drawPath(chamfer(all, cut * 1.3f), rimPaint)
        popup.forEachIndexed { i, (s, r) ->
            if (i == popupSel) { keyPaint.color = teal; c.drawPath(chamfer(r, cut), keyPaint) }
            labelPaint.typeface = sans; labelPaint.textSize = 22f * dens; labelPaint.color = if (i == popupSel) navy else text
            c.drawText(if (shift || caps) s.uppercase() else s, r.centerX(), r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        }
    }

    private fun openAccents() {
        val p = down ?: return
        val opts = accents[p.key.label] ?: return
        val kw = p.r.width(); val gap = 4f * dens
        val total = opts.length * kw + (opts.length - 1) * gap
        var x = (p.r.centerX() - kw / 2f).coerceAtMost(width - total - 8 * dens).coerceAtLeast(8 * dens)
        val y = max(4f * dens, p.r.top - p.r.height() - 10 * dens)
        popup = opts.map { ch -> (ch.toString() to RectF(x, y, x + kw, y + p.r.height())).also { x += kw + gap } }
        popupSel = 0
        listener?.feedback()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { placed = emptyList() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (placed.isEmpty()) layoutKeys()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = placed.firstOrNull { it.r.contains(e.x, e.y) } ?: placed.minByOrNull { dist(it.r, e.x, e.y) } ?: return true
                down = hit; downX = e.x; spaceDrag = 0f
                listener?.feedback()
                when (hit.key.kind) {
                    Kind.DEL -> { listener?.delete(); h.postDelayed(repeat, 420) }
                    Kind.CHAR -> if (accents.containsKey(hit.key.label)) h.postDelayed(longPress, 380)
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val d = down ?: return true
                if (popup.isNotEmpty()) {
                    val i = popup.indexOfFirst { e.x >= it.second.left - 2 * dens && e.x <= it.second.right + 2 * dens }
                    if (i >= 0 && i != popupSel) { popupSel = i; invalidate() }
                } else if (d.key.kind == Kind.SPACE) {
                    // drag along the space bar to move the cursor, a step per 14 dp
                    val step = 14f * dens
                    val moved = e.x - downX - spaceDrag
                    if (abs(moved) >= step) { val n = (moved / step).toInt(); repeat(abs(n)) { listener?.cursor(n) }; spaceDrag += n * step }
                } else if (!d.r.contains(e.x, e.y) && d.key.kind != Kind.DEL) {
                    h.removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(repeat); h.removeCallbacks(longPress)
                val d = down; down = null
                if (d != null) {
                    if (popup.isNotEmpty()) {
                        popup.getOrNull(popupSel)?.let { emit(it.first) }
                        popup = emptyList(); popupSel = -1
                    } else if (!(d.key.kind == Kind.SPACE && abs(spaceDrag) > 0f)) press(d.key)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(repeat); h.removeCallbacks(longPress); down = null; popup = emptyList(); invalidate() }
        }
        return true
    }

    private fun dist(r: RectF, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right)); val dy = max(0f, max(r.top - y, y - r.bottom)); return dx * dx + dy * dy
    }

    private fun emit(s: String) {
        val upper = page == Page.LETTERS && (shift || caps)
        listener?.text(if (upper) s.uppercase() else s)
        if (shift && !caps) { shift = false }
    }

    private fun press(k: Key) {
        when (k.kind) {
            Kind.CHAR -> emit(k.out)
            Kind.SPACE -> {
                listener?.text(" ")
                if (page != Page.LETTERS && page != Page.NUM) { page = Page.LETTERS; placed = emptyList() }
            }
            Kind.SHIFT -> {
                val now = System.currentTimeMillis()
                when {
                    caps -> { caps = false; shift = false }
                    shift && now - lastShiftTap < 350 -> { caps = true }
                    else -> shift = !shift
                }
                lastShiftTap = now
            }
            Kind.DEL -> {}
            Kind.PAGE -> { page = k.to ?: Page.LETTERS; placed = emptyList() }
            Kind.ACTION -> listener?.action()
            Kind.LEFT -> listener?.cursor(-1)
            Kind.RIGHT -> listener?.cursor(1)
            Kind.HIDE -> listener?.hide()
        }
        if (k.kind == Kind.CHAR && page == Page.LETTERS && (k.out == "." || k.out == "?" || k.out == "!")) { /* sentence case comes back after the space */ }
        invalidate()
    }

    /** For the screenshot tests: show a page, with shift on or off. */
    internal fun preview(p: Page, shifted: Boolean = false, action: String = "return") {
        page = p; shift = shifted; actionLabel = action; placed = emptyList(); invalidate()
    }
}
