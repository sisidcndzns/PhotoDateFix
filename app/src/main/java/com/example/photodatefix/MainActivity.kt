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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val OFF_ORIG = ExifInterface.TAG_OFFSET_TIME_ORIGINAL
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

        // 3. 저장 방식
        val modeCard = card()
        modeCard.addView(stepHeader("3", "저장 방식"))
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

    /** 원본에서 수정본에 쓸 날짜 정보. 원본에 없는 오프셋/밀리초 태그는 null(=수정본에서 삭제) */
    private fun readDates(doc: Doc): Map<String, String?>? {
        val m = HashMap<String, String?>(readExif(doc))
        val base: String = m[ExifInterface.TAG_DATETIME_ORIGINAL]
            ?: m[ExifInterface.TAG_DATETIME_DIGITIZED]
            ?: m[ExifInterface.TAG_DATETIME]
            ?: run {
                if (doc.lastModified <= 0) return null
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(doc.lastModified))
            }
        m[ExifInterface.TAG_DATETIME_ORIGINAL] = m[ExifInterface.TAG_DATETIME_ORIGINAL] ?: base
        m[ExifInterface.TAG_DATETIME_DIGITIZED] = m[ExifInterface.TAG_DATETIME_DIGITIZED] ?: base
        m[ExifInterface.TAG_DATETIME] = m[ExifInterface.TAG_DATETIME] ?: base
        return m
    }

    private fun isSame(cur: Map<String, String?>, want: Map<String, String?>): Boolean =
        cur[ORIG] == want[ORIG] && cur[OFF_ORIG] == want[OFF_ORIG]

    private fun parseMillis(d: Map<String, String?>): Long? {
        val date = d[ORIG] ?: return null
        val off = d[OFF_ORIG] ?: d[ExifInterface.TAG_OFFSET_TIME]
        return try {
            if (off != null && Regex("[+-]\\d{2}:\\d{2}").matches(off))
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss XXX", Locale.US).parse("$date $off")?.time
            else
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(date)?.time
        } catch (_: Exception) { null }
    }

    /** 임시 파일에서 EXIF를 고친 뒤 대상에 통째로 덮어씀(잘라쓰기) */
    private fun writeFixed(src: Doc, dst: Uri, dates: Map<String, String?>) {
        val tmp = File.createTempFile("pdf", ".img", cacheDir)
        try {
            contentResolver.openInputStream(src.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val ex = ExifInterface(tmp.absolutePath)
            for ((k, v) in dates) ex.setAttribute(k, v)
            ex.saveAttributes()
            contentResolver.openOutputStream(dst, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
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
                        val dates = if (orig != null) readDates(orig) else null
                        val cur = readExif(ed)
                        val millis = if (dates != null) parseMillis(dates) else null
                        val needFix = dates != null && !isSame(cur, dates)
                        if (needFix) {
                            val dst = target(ed)
                            try {
                                writeFixed(ed, dst, dates!!)
                                changed++
                                log("✔ ${ed.name} ← ${orig!!.name}: ${cur[ORIG] ?: "날짜없음"} → ${dates[ORIG]}")
                            } catch (fe: Exception) {
                                if (overwrite) throw fe
                                copyPlain(ed, dst); copied++
                                log("⚠ 날짜 쓰기 실패(${fe.message}) → 그대로 복사: ${ed.name}")
                            }
                            finish(dst, millis)
                        } else if (overwrite) {
                            when {
                                orig == null -> { noMatch++; log("⚠ 매칭 없음: ${ed.name}") }
                                dates == null -> { fail++; log("✖ 원본 날짜 없음: ${orig.name}") }
                                else -> { same++; log("＝ 이미 같은 날짜(건너뜀): ${ed.name}") }
                            }
                        } else {
                            val dst = target(ed)
                            copyPlain(ed, dst)
                            finish(dst, millis ?: ed.lastModified.takeIf { it > 0 })
                            copied++
                            log(when {
                                orig == null -> "➕ 원본에 없음 → 그대로 복사: ${ed.name}"
                                dates == null -> "➕ 원본 날짜 없음 → 그대로 복사: ${ed.name}"
                                else -> "＝ 같은 날짜 → 복사: ${ed.name}"
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
