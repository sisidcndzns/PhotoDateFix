package com.example.photodatefix

import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
    private lateinit var btnRun: Button
    private lateinit var cbSub: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView

    private val executor = Executors.newSingleThreadExecutor()
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val suffixRegex = Regex("([-_ ]?(edited|edit|copy|수정|편집|보정)|\\s*\\(\\d+\\))$")
    private var sortedKeys: List<String> = emptyList()

    private val dateTags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    private val pickOriginal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { originalUri = it; tvOrig.text = "원본: " + label(it); updateButton() }
    }
    private val pickEdited = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { editedUri = it; tvEdit.text = "수정본: " + label(it); updateButton() }
    }
    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) { outUri = it; tvOut.text = "저장 폴더: " + label(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        tvOrig = TextView(this).apply { text = "원본: (선택 안 됨)" }
        tvEdit = TextView(this).apply { text = "수정본: (선택 안 됨)" }
        tvOut = TextView(this).apply { text = "저장 폴더: 선택 안 함 → 수정본을 직접 덮어씀" }
        cbSub = CheckBox(this).apply { text = "하위 폴더도 포함"; isChecked = true }
        btnRun = btn("4. 날짜 적용 시작") { start() }.apply { isEnabled = false }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        tvLog = TextView(this).apply { textSize = 12f }
        scroll = ScrollView(this).apply { addView(tvLog) }

        root.addView(btn("1. 원본 사진 폴더 선택") { pickOriginal.launch(null) }); root.addView(tvOrig)
        root.addView(btn("2. 수정된 사진 폴더 선택") { pickEdited.launch(null) }); root.addView(tvEdit)
        root.addView(btn("3. (선택) 결과 저장 폴더") { pickOut.launch(null) }); root.addView(tvOut)
        root.addView(btn("저장 폴더 선택 해제 (덮어쓰기)") { outUri = null; tvOut.text = "저장 폴더: 선택 안 함 → 수정본을 직접 덮어씀" })
        root.addView(cbSub); root.addView(btnRun); root.addView(progress)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun updateButton() { btnRun.isEnabled = originalUri != null && editedUri != null }
    private fun label(uri: Uri) = Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfter(":").ifEmpty { "/" }

    private fun log(msg: String) = runOnUiThread {
        tvLog.append(msg + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

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

    private fun readDates(doc: Doc): Map<String, String>? {
        val m = HashMap<String, String>()
        try {
            contentResolver.openInputStream(doc.uri)?.use { s ->
                val ex = ExifInterface(s)
                for (t in dateTags) ex.getAttribute(t)?.let { m[t] = it }
            }
        } catch (_: Exception) { }
        var base = m[ExifInterface.TAG_DATETIME_ORIGINAL] ?: m[ExifInterface.TAG_DATETIME_DIGITIZED] ?: m[ExifInterface.TAG_DATETIME]
        if (base == null) {
            if (doc.lastModified <= 0) return null
            base = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(doc.lastModified))
        }
        m[ExifInterface.TAG_DATETIME_ORIGINAL] = m[ExifInterface.TAG_DATETIME_ORIGINAL] ?: base
        m[ExifInterface.TAG_DATETIME_DIGITIZED] = m[ExifInterface.TAG_DATETIME_DIGITIZED] ?: base
        m[ExifInterface.TAG_DATETIME] = m[ExifInterface.TAG_DATETIME] ?: base
        return m
    }

    private fun currentDate(doc: Doc): String? = try {
        contentResolver.openInputStream(doc.uri)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }
    } catch (_: Exception) { null }

    private fun writeDates(uri: Uri, dates: Map<String, String>) {
        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val ex = ExifInterface(pfd.fileDescriptor)
            for ((k, v) in dates) ex.setAttribute(k, v)
            ex.saveAttributes()
        } ?: throw IllegalStateException("파일을 열 수 없음")
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "heif" -> "image/heif"; else -> "image/jpeg"
    }

    private fun pathOf(docId: String): String? =
        if (docId.startsWith("primary:")) File(Environment.getExternalStorageDirectory(), docId.removePrefix("primary:")).path else null

    private fun start() {
        val o = originalUri ?: return
        val e = editedUri ?: return
        val out = outUri
        val recursive = cbSub.isChecked
        btnRun.isEnabled = false
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
                runOnUiThread { progress.max = edits.size; progress.progress = 0 }

                val outParent = out?.let {
                    DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
                }
                var ok = 0; var same = 0; var noMatch = 0; var fail = 0
                val scanPaths = ArrayList<String>()

                edits.forEachIndexed { i, ed ->
                    val orig = findOriginal(ed.name, map)
                    if (orig == null) {
                        noMatch++; log("⚠ 매칭 없음: ${ed.name}")
                    } else try {
                        val dates = readDates(orig)
                        if (dates == null) { fail++; log("✖ 원본 날짜 없음: ${orig.name}") }
                        else {
                            val want = dates[ExifInterface.TAG_DATETIME_ORIGINAL]!!
                            val have = currentDate(ed)?.trim { it <= ' ' || it == '\u0000' }
                            if (have == want) { same++; log("＝ 이미 같은 날짜(건너뜀): ${ed.name}") }
                            else {
                                if (outParent != null) {
                                    val newUri = DocumentsContract.createDocument(contentResolver, outParent, mimeOf(ed.name), ed.name)
                                        ?: throw IllegalStateException("저장 파일 생성 실패")
                                    contentResolver.openInputStream(ed.uri)!!.use { input ->
                                        contentResolver.openOutputStream(newUri)!!.use { input.copyTo(it) }
                                    }
                                    writeDates(newUri, dates)
                                    pathOf(DocumentsContract.getDocumentId(newUri))?.let { scanPaths.add(it) }
                                } else {
                                    writeDates(ed.uri, dates)
                                    pathOf(ed.docId)?.let { scanPaths.add(it) }
                                }
                                ok++
                                log("✔ ${ed.name} ← ${orig.name}: ${have ?: "날짜없음"} → $want")
                            }
                        }
                    } catch (ex: Exception) {
                        fail++; log("✖ 실패: ${ed.name} (${ex.message})")
                    }
                    runOnUiThread { progress.progress = i + 1 }
                }
                if (scanPaths.isNotEmpty())
                    MediaScannerConnection.scanFile(this, scanPaths.toTypedArray(), null, null)
                log("\n완료: 변경 $ok, 이미 동일 $same, 매칭 없음 $noMatch, 실패 $fail")
            } catch (ex: Exception) {
                log("오류: ${ex.message}")
            } finally {
                runOnUiThread { updateButton() }
            }
        }
    }
}
