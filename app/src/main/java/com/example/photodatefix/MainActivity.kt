package com.example.photodatefix

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class Doc(val uri: Uri, val docId: String, val name: String, val lastModified: Long)

class MainActivity : AppCompatActivity() {

    private var originalUri: Uri? = null
    private var editedUri: Uri? = null
    private var outUri: Uri? = null

    private lateinit var tvOrig: TextView
    private lateinit var tvEdit: TextView
    private lateinit var tvOut: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etOff: EditText
    private lateinit var btnRun: Button
    private lateinit var cbSub: CheckBox
    private lateinit var rbOverwrite: RadioButton
    private lateinit var rbCopy: RadioButton
    private lateinit var outBox: LinearLayout
    private lateinit var permCard: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView

    private val executor = Executors.newSingleThreadExecutor()
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val suffixRegex = Regex("([-_ ]?(edited|edit|copy|수정|편집|보정)|\\s*\\(\\d+\\))$")
    private var sortedKeys: List<String> = emptyList()

    private val ORIG = ExifInterface.TAG_DATETIME_ORIGINAL
    private val DIG = ExifInterface.TAG_DATETIME_DIGITIZED
    private val DT = ExifInterface.TAG_DATETIME
    private val OFF_T = ExifInterface.TAG_OFFSET_TIME
    private val OFF_ORIG = ExifInterface.TAG_OFFSET_TIME_ORIGINAL
    private val OFF_DIG = ExifInterface.TAG_OFFSET_TIME_DIGITIZED
    private val SUB_T = ExifInterface.TAG_SUBSEC_TIME
    private val SUB_ORIG = ExifInterface.TAG_SUBSEC_TIME_ORIGINAL
    private val SUB_DIG = ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    private val XHDR = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val dateTags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    // ---- 색상 ----
    private val cAccent = 0xFF3B6CF6.toInt()
    private val cBg = 0xFFF3F4F8.toInt()
    private val cCard = 0xFFFFFFFF.toInt()
    private val cText = 0xFF1B1B1F.toInt()
    private val cMuted = 0xFF6B6F7B.toInt()
    private val cSoft = 0xFFE7ECFF.toInt()

    private val pickOriginal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { originalUri = it; tvOrig.text = label(it); updateButton() }
    }
    private val pickEdited = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { editedUri = it; tvEdit.text = label(it); updateButton() }
    }
    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { outUri = it; tvOut.text = label(it); updateButton() }
    }

    // ================= UI =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun btn(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else cAccent)
        setTypeface(typeface, Typeface.BOLD)
        background = RippleDrawable(
            ColorStateList.valueOf(0x22000000), rounded(if (primary) cAccent else cSoft, 12), null
        )
        stateListAnimator = null
        minHeight = dp(if (primary) 56 else 48)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun stepHeader(num: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val badge = tv(num, 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cAccent) }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        addView(badge)
        addView(tv(title, 17f, cText, true).apply { setPadding(dp(10), 0, 0, 0) })
    }

    private fun folderCard(step: String, title: String, desc: String, path: TextView, onPick: () -> Unit) =
        card().apply {
            addView(stepHeader(step, title))
            addView(tv(desc, 13f, cMuted, false).apply { setPadding(0, dp(8), 0, 0) })
            addView(path.apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(cBg, 10)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            })
            addView(btn("폴더 선택", false, onPick))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply { setBackgroundColor(cBg); addView(page) }

        page.addView(tv("📷 사진 날짜 복사", 24f, cText, true))
        page.addView(tv("원본 사진의 촬영 날짜를 수정된 사진에 복사합니다.", 14f, cMuted, false)
            .apply { setPadding(0, dp(4), 0, dp(20)) })

        // 모든 파일 접근 안내 (선택)
        permCard = card().apply {
            background = rounded(0xFFFFF4DB.toInt(), 16)
            addView(tv("파일 '수정한 날짜'까지 맞추려면", 15f, cText, true))
            addView(tv("'모든 파일 접근'을 허용하면 사진의 파일 시간도 촬영 날짜로 바뀌어 갤러리 정렬이 더 정확해집니다. (선택 사항)", 13f, cMuted, false)
                .apply { setPadding(0, dp(6), 0, 0) })
            addView(btn("모든 파일 접근 허용", false) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            })
        }
        page.addView(permCard)

        tvOrig = tv("선택된 폴더 없음", 14f, cText, false)
        tvEdit = tv("선택된 폴더 없음", 14f, cText, false)
        tvOut = tv("선택된 폴더 없음", 14f, cText, false)

        page.addView(folderCard("1", "원본 사진 폴더", "촬영 날짜가 정확한 원본 사진이 있는 폴더", tvOrig) { pickOriginal.launch(null) })
        page.addView(folderCard("2", "수정된 사진 폴더", "날짜를 고칠 수정본 사진이 있는 폴더", tvEdit) { pickEdited.launch(null) })

        // 3. 시간대 변환
        val tzCard = card()
        tzCard.addView(stepHeader("3", "변환할 시간대 (오프셋)"))
        tzCard.addView(tv("원본의 실제 촬영 순간(UTC)을 구한 뒤, 이 시간대의 현지 시각으로 바꿔 EXIF·XMP 전체에 통일해 기록합니다.\n예) 한국 +09:00 · 런던 9월 +01:00 · 파리 9월 +02:00", 13f, cMuted, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        etOff = EditText(this).apply {
            setText("+09:00"); setTextColor(cText); textSize = 16f; setSingleLine(true)
            background = rounded(cBg, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        tzCard.addView(etOff)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        listOf("+09:00", "+01:00", "+00:00", "+02:00").forEach { o ->
            chips.addView(Button(this).apply {
                text = o; isAllCaps = false; textSize = 13f
                setTextColor(cAccent)
                background = rounded(cSoft, 10)
                stateListAnimator = null
                minHeight = dp(40)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
                setOnClickListener { etOff.setText(o) }
            })
        }
        tzCard.addView(chips)
        page.addView(tzCard)

        // 4. 저장 방식
        val modeCard = card()
        modeCard.addView(stepHeader("4", "저장 방식"))
        val rg = RadioGroup(this).apply { setPadding(0, dp(8), 0, 0) }
        rbOverwrite = RadioButton(this).apply {
            id = View.generateViewId(); text = "수정본 폴더에 같은 이름으로 덮어쓰기"
            setTextColor(cText); textSize = 15f
        }
        rbCopy = RadioButton(this).apply {
            id = View.generateViewId(); text = "다른 폴더에 저장"
            setTextColor(cText); textSize = 15f
        }
        rg.addView(rbOverwrite); rg.addView(rbCopy)
        modeCard.addView(rg)
        modeCard.addView(tv("덮어쓰기: 날짜가 다른 사진만 수정합니다.\n다른 폴더: 수정본 전체를 복사합니다. 날짜가 같은 사진과 원본에 없는 사진도 함께 복사하며, 같은 이름의 파일은 덮어씁니다.", 12f, cMuted, false)
            .apply { setPadding(dp(4), dp(4), 0, 0) })
        outBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        outBox.addView(tvOut.apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(cBg, 10)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        })
        outBox.addView(btn("저장 폴더 선택", false) { pickOut.launch(null) })
        modeCard.addView(outBox)
        page.addView(modeCard)
        rbOverwrite.isChecked = true
        rg.setOnCheckedChangeListener { _, _ ->
            outBox.visibility = if (rbCopy.isChecked) View.VISIBLE else View.GONE
            updateButton()
        }

        // 옵션
        val optCard = card()
        cbSub = CheckBox(this).apply { text = "하위 폴더도 포함"; isChecked = true; setTextColor(cText); textSize = 15f }
        optCard.addView(cbSub)
        page.addView(optCard)

        // 실행
        btnRun = btn("날짜 적용 시작", true) { start() }
        page.addView(btnRun)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16) }
        }
        page.addView(progress)
        tvStatus = tv("", 13f, cMuted, false).apply { setPadding(0, dp(6), 0, dp(10)) }
        page.addView(tvStatus)

        // 로그
        val logCard = card().apply { background = rounded(0xFF1E2027.toInt(), 16) }
        tvLog = tv("여기에 진행 상황이 표시됩니다.", 12f, 0xFFD7DAE0.toInt(), false).apply {
            typeface = Typeface.MONOSPACE
            minHeight = dp(160)
        }
        logCard.addView(tvLog)
        page.addView(logCard)

        setContentView(scroll)
        updateButton()
    }

    override fun onResume() {
        super.onResume()
        val need = Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
        permCard.visibility = if (need) View.VISIBLE else View.GONE
    }

    private fun updateButton() {
        val ready = originalUri != null && editedUri != null && (!rbCopy.isChecked || outUri != null)
        btnRun.isEnabled = ready
        btnRun.alpha = if (ready) 1f else 0.4f
    }

    private fun label(uri: Uri) =
        "📁 " + Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfter(":").ifEmpty { "/" }

    private fun log(msg: String) = runOnUiThread {
        tvLog.append(msg + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ================= 파일 목록 / 매칭 =================
    private fun listImages(tree: Uri, recursive: Boolean): List<Doc> {
        val result = ArrayList<Doc>()
        val stack = ArrayList<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty()) {
            val id = stack.removeAt(stack.size - 1)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getString(0); val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (recursive) stack.add(cid)
                    } else if (name.substringAfterLast('.', "").lowercase() in imageExt) {
                        result.add(Doc(DocumentsContract.buildDocumentUriUsingTree(tree, cid), cid, name, c.getLong(3)))
                    }
                }
            }
        }
        return result
    }

    private fun baseKey(name: String) = name.substringBeforeLast('.').lowercase()

    private fun findOriginal(editedName: String, map: Map<String, Doc>): Doc? {
        val edited = baseKey(editedName)
        map[edited]?.let { return it }
        var b = edited
        repeat(3) {
            val s = suffixRegex.replace(b, "")
            if (s == b || s.isEmpty()) return@repeat
            b = s
            map[b]?.let { return it }
        }
        for (key in sortedKeys) {
            if (key.length >= edited.length || !edited.startsWith(key)) continue
            if (key.last().isDigit() && edited[key.length].isDigit()) continue
            return map[key]
        }
        return null
    }

    // ================= EXIF =================
    private fun clean(s: String?): String? =
        s?.trim { it <= ' ' || it == '\u0000' }?.takeIf { it.isNotEmpty() }

    private fun readExif(doc: Doc): Map<String, String?> {
        val m = HashMap<String, String?>()
        try {
            contentResolver.openInputStream(doc.uri)?.use { s ->
                val ex = ExifInterface(s)
                for (t in dateTags) m[t] = clean(ex.getAttribute(t))
            }
        } catch (_: Exception) { }
        return m
    }

    private class Plan(val utc: Long, val dto: String, val off: String, val sub: String?, val iso: String, val fallback: Boolean)

    private fun offMin(s: String?): Int? {
        val t = s?.trim() ?: return null
        val m = Regex("^([+-])(\\d{2}):?(\\d{2})$").find(t) ?: return null
        val v = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        return if (m.groupValues[1] == "-") -v else v
    }

    private fun offStr(min: Int): String {
        val a = Math.abs(min)
        return (if (min < 0) "-" else "+") + String.format(Locale.US, "%02d:%02d", a / 60, a % 60)
    }

    private fun utcFmt(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** 원본의 (촬영시각 − 오프셋)으로 실제 순간(UTC)을 구하고, 목표 오프셋의 현지 시각으로 변환 */
    private fun buildPlan(orig: Doc, tgtOff: String): Plan? {
        val tgt = offMin(tgtOff) ?: return null
        val ex = readExif(orig)
        val dto = ex[ORIG] ?: ex[DIG] ?: ex[DT]
        var fallback = false
        val utc: Long
        if (dto == null) {
            if (orig.lastModified <= 0) return null
            utc = orig.lastModified; fallback = true
        } else {
            val l = utcFmt("yyyy:MM:dd HH:mm:ss").parse(dto.take(19))?.time ?: return null
            val om = offMin(ex[OFF_ORIG] ?: ex[OFF_T] ?: ex[OFF_DIG])
            if (om != null) {
                utc = l - om * 60000L
            } else {
                utc = l - tgt * 60000L; fallback = true
            }
        }
        val local = Date(utc + tgt * 60000L)
        val dtoOut = utcFmt("yyyy:MM:dd HH:mm:ss").format(local)
        val sub = ex[SUB_ORIG] ?: ex[SUB_T]
        val off = offStr(tgt)
        val iso = utcFmt("yyyy-MM-dd'T'HH:mm:ss").format(local) + (if (sub != null) ".$sub" else "") + off
        return Plan(utc, dtoOut, off, sub, iso, fallback)
    }

    private fun datesOf(p: Plan): Map<String, String?> = mapOf(
        ORIG to p.dto, DIG to p.dto, DT to p.dto,
        OFF_T to p.off, OFF_ORIG to p.off, OFF_DIG to p.off,
        SUB_T to p.sub, SUB_ORIG to p.sub, SUB_DIG to p.sub
    )

    private fun readHead(uri: Uri, max: Int): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0
            while (total < max) {
                val n = s.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n); total += n
            }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    // ---- XMP ----
    private class Xmp(val start: Int, val total: Int, val xml: String)

    private fun findXmp(b: ByteArray): Xmp? {
        var p = 2
        while (p + 4 <= b.size && (b[p].toInt() and 0xFF) == 0xFF) {
            val mk = b[p + 1].toInt() and 0xFF
            if (mk == 0xFF) { p++; continue }
            if (mk == 0xD9 || mk == 0xDA) break
            if (mk == 0x01 || mk in 0xD0..0xD7) { p += 2; continue }
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (mk == 0xE1 && p + 4 + XHDR.size <= b.size) {
                var ok = true
                for (i in XHDR.indices) { if (b[p + 4 + i] != XHDR[i]) { ok = false; break } }
                if (ok) {
                    val from = p + 4 + XHDR.size
                    val end = minOf(p + 2 + len, b.size)
                    return Xmp(p, 2 + len, String(b, from, end - from, Charsets.UTF_8))
                }
            }
            p += 2 + len
        }
        return null
    }

    private fun xmpCreate(xml: String): String? =
        Regex("xmp:CreateDate\\s*=\\s*\"([^\"]*)\"").find(xml)?.groupValues?.get(1)
            ?: Regex("<xmp:CreateDate>([^<]*)<").find(xml)?.groupValues?.get(1)

    private fun setXmpDates(b: ByteArray, iso: String): ByteArray {
        val s = findXmp(b) ?: return b
        var xml = s.xml
        for (n in listOf("xmp:CreateDate", "photoshop:DateCreated", "exif:DateTimeOriginal", "exif:DateTimeDigitized")) {
            val q = Regex.escape(n)
            xml = Regex("(" + q + "\\s*=\\s*\")[^\"]*(\")").replace(xml) { it.groupValues[1] + iso + it.groupValues[2] }
            xml = Regex("(<" + q + ">)[^<]*(</" + q + ">)").replace(xml) { it.groupValues[1] + iso + it.groupValues[2] }
        }
        val body = xml.toByteArray(Charsets.UTF_8)
        val len = 2 + XHDR.size + body.size
        if (len > 65535) return b
        val out = ByteArrayOutputStream(b.size + 1024)
        out.write(b, 0, s.start)
        out.write(0xFF); out.write(0xE1); out.write(len shr 8); out.write(len and 0xFF)
        out.write(XHDR); out.write(body)
        val restFrom = s.start + s.total
        if (restFrom < b.size) out.write(b, restFrom, b.size - restFrom)
        return out.toByteArray()
    }

    /** 수정본이 이미 목표 시간(EXIF+XMP)과 같은지 */
    private fun isSame(ed: Doc, p: Plan): Boolean {
        val cur = readExif(ed)
        if (cur[ORIG] != p.dto || cur[OFF_ORIG] != p.off) return false
        val head = readHead(ed.uri, 1 shl 20) ?: return true
        val x = if (head.size > 2) findXmp(head) else null
        val c = if (x != null) xmpCreate(x.xml) else null
        return c == null || c == p.iso
    }

    /** 임시 파일에서 EXIF·XMP를 고친 뒤 대상에 통째로 덮어씀(잘라쓰기) */
    private fun writeFixed(src: Doc, dst: Uri, p: Plan) {
        val tmp = File.createTempFile("pdf", ".img", cacheDir)
        try {
            contentResolver.openInputStream(src.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val ex = ExifInterface(tmp.absolutePath)
            for ((k, v) in datesOf(p)) ex.setAttribute(k, v)
            ex.saveAttributes()
            var bytes = tmp.readBytes()
            if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) bytes = setXmpDates(bytes, p.iso)
            contentResolver.openOutputStream(dst, "wt")!!.use { it.write(bytes) }
        } finally { tmp.delete() }
    }

    private fun copyPlain(src: Doc, dst: Uri) {
        contentResolver.openInputStream(src.uri)!!.use { i ->
            contentResolver.openOutputStream(dst, "wt")!!.use { i.copyTo(it) }
        }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "heif" -> "image/heif"; else -> "image/jpeg"
    }

    private fun pathOf(docId: String): String? {
        val vol = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "")
        return when {
            vol == "primary" -> File(Environment.getExternalStorageDirectory(), rel).path
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(vol) -> File("/storage/$vol", rel).path
            else -> null
        }
    }

    // ================= 실행 =================
    private fun start() {
        val o = originalUri ?: return
        val e = editedUri ?: return
        val copyMode = rbCopy.isChecked
        val out = outUri
        if (copyMode && out == null) { Toast.makeText(this, "저장 폴더를 선택하세요", Toast.LENGTH_SHORT).show(); return }
        val overwrite = !copyMode || out == e
        val recursive = cbSub.isChecked
        val tgtOff = etOff.text.toString().trim()
        if (offMin(tgtOff) == null) { Toast.makeText(this, "오프셋 형식이 올바르지 않습니다. 예: +01:00", Toast.LENGTH_LONG).show(); return }
        btnRun.isEnabled = false; btnRun.alpha = 0.4f
        tvLog.text = ""
        executor.execute {
            try {
                log("폴더 읽는 중...")
                val origs = listImages(o, recursive)
                val edits = listImages(e, recursive)
                val map = HashMap<String, Doc>()
                origs.forEach { map.putIfAbsent(baseKey(it.name), it) }
                sortedKeys = map.keys.sortedByDescending { it.length }
                log("원본 ${origs.size}장 / 수정본 ${edits.size}장\n")
                runOnUiThread { progress.max = edits.size; progress.progress = 0; tvStatus.text = "0 / ${edits.size}" }

                val outParent: Uri? =
                    if (!overwrite) DocumentsContract.buildDocumentUriUsingTree(out!!, DocumentsContract.getTreeDocumentId(out)) else null
                val existing = HashMap<String, Doc>()
                if (!overwrite) listImages(out!!, false).forEach { existing[it.name.lowercase()] = it }
                val used = HashSet<String>()
                val scan = ArrayList<String>()
                var changed = 0; var copied = 0; var same = 0; var noMatch = 0; var fail = 0

                fun target(ed: Doc): Uri {
                    if (overwrite) return ed.uri
                    var name = ed.name; var n = 2
                    while (!used.add(name.lowercase())) {
                        name = ed.name.substringBeforeLast('.') + "_" + n + "." + ed.name.substringAfterLast('.', "")
                        n++
                    }
                    existing[name.lowercase()]?.let { return it.uri }
                    return DocumentsContract.createDocument(contentResolver, outParent!!, mimeOf(name), name)
                        ?: throw IllegalStateException("저장 파일 생성 실패")
                }

                fun finish(dst: Uri, millis: Long?) {
                    val p = pathOf(DocumentsContract.getDocumentId(dst)) ?: return
                    if (millis != null) try { File(p).setLastModified(millis) } catch (_: Exception) { }
                    scan.add(p)
                }

                edits.forEachIndexed { i, ed ->
                    try {
                        val orig = findOriginal(ed.name, map)
                        val plan = if (orig != null) buildPlan(orig, tgtOff) else null
                        val needFix = plan != null && !isSame(ed, plan)
                        if (needFix) {
                            val p = plan!!
                            val cur = readExif(ed)
                            val dst = target(ed)
                            try {
                                writeFixed(ed, dst, p)
                                changed++
                                log("✔ ${ed.name} ← ${orig!!.name}: ${cur[ORIG] ?: "없음"}${cur[OFF_ORIG] ?: ""} → ${p.dto}${p.off}" +
                                    (if (p.fallback) " ⚠원본 오프셋 없음" else ""))
                            } catch (fe: Exception) {
                                if (overwrite) throw fe
                                copyPlain(ed, dst); copied++
                                log("⚠ 날짜 쓰기 실패(${fe.message}) → 그대로 복사: ${ed.name}")
                            }
                            finish(dst, p.utc)
                        } else if (overwrite) {
                            when {
                                orig == null -> { noMatch++; log("⚠ 매칭 없음: ${ed.name}") }
                                plan == null -> { fail++; log("✖ 원본 날짜 없음: ${orig.name}") }
                                else -> { same++; log("＝ 이미 같은 시간(건너뜀): ${ed.name}") }
                            }
                        } else {
                            val dst = target(ed)
                            copyPlain(ed, dst)
                            finish(dst, plan?.utc ?: ed.lastModified.takeIf { it > 0 })
                            copied++
                            log(when {
                                orig == null -> "➕ 원본에 없음 → 그대로 복사: ${ed.name}"
                                plan == null -> "➕ 원본 날짜 없음 → 그대로 복사: ${ed.name}"
                                else -> "＝ 같은 시간 → 복사: ${ed.name}"
                            })
                        }
                    } catch (ex: Exception) {
                        fail++; log("✖ 실패: ${ed.name} (${ex.message})")
                    }
                    runOnUiThread { progress.progress = i + 1; tvStatus.text = "${i + 1} / ${edits.size}" }
                }
                if (scan.isNotEmpty()) MediaScannerConnection.scanFile(this, scan.toTypedArray(), null, null)
                log("\n완료: 날짜 수정 $changed, 그대로 복사 $copied, 이미 동일 $same, 매칭 없음 $noMatch, 실패 $fail")
            } catch (ex: Exception) {
                log("오류: ${ex.message}")
            } finally {
                runOnUiThread { updateButton() }
            }
        }
    }
}
