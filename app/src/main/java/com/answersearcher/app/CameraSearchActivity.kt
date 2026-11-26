package com.answersearcher.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 拍照搜题（新增通道）
 *
 * 原 App 只有「悬浮窗 + 截屏」，只能搜手机自己屏幕上显示的内容。
 * 这个页面补上「相机拍电脑屏幕」的场景：拍一张 → 本地 OCR → 题库匹配 → 直接出答案。
 *
 * 界面全部用代码构建，不新增 layout 文件，改完只需替换 5 个文件。
 * 题库复用 AnswerApplication.excelData（和悬浮窗同一个题库，不用重复导入）。
 */
class CameraSearchActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var pb: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var llResult: LinearLayout
    private lateinit var tvAnswer: TextView
    private lateinit var tvConf: TextView
    private lateinit var tvStem: TextView
    private lateinit var etText: EditText
    private lateinit var tvCandsTitle: TextView
    private lateinit var llCands: LinearLayout

    private var photoFile: File? = null

    // ---------------- 相机 / 相册回调 ----------------
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = photoFile
            if (ok && f != null && f.length() > 0) {
                processFile(f)
            } else {
                showBusy(false, "")
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            showBusy(true, "正在读取图片…")
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) { copyToCache(uri) }
                if (f == null) showBusy(false, "读取图片失败") else processFile(f)
            }
        }

    // ---------------- 生命周期 ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (AnswerApplication.excelData == null) {
            toast("还没有选择题库，请先回主页点「选择题库」")
        }
    }

    // ---------------- 流程 ----------------
    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            photoFile = f
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("打不开相机：${e.message}")
        }
    }

    private fun copyToCache(uri: Uri): File? = try {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "pick_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { output -> input.copyTo(output) }
        }
        if (f.length() > 0) f else null
    } catch (e: Exception) {
        null
    }

    private fun processFile(file: File) {
        showBusy(true, "正在识别…")
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.Default) {
                    decodeSampled(file, 1600)?.let { orient(file, it) }
                }
                if (bmp == null) {
                    showBusy(false, "图片打不开")
                    return@launch
                }
                ivPreview.setImageBitmap(bmp)
                ivPreview.visibility = View.VISIBLE

                val text = OCRManager.recognizeText(bmp)
                if (text.isBlank()) {
                    showBusy(false, "没识别出文字：靠近屏幕、横屏、别开闪光灯再试")
                    return@launch
                }
                runSearch(text)
            } catch (e: Exception) {
                showBusy(false, "识别失败：${e.message}")
            }
        }
    }

    private fun runSearch(raw: String) {
        val data = AnswerApplication.excelData
        if (data == null) {
            showBusy(false, "还没有选择题库")
            return
        }
        showBusy(true, "正在匹配…")
        lifecycleScope.launch {
            val hits = withContext(Dispatchers.Default) {
                try {
                    SmartMatcher.search(raw, data, 4)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            render(raw, hits)
        }
    }

    // ---------------- 渲染 ----------------
    private fun render(raw: String, hits: List<SmartMatcher.Hit>) {
        pb.visibility = View.GONE
        tvStatus.visibility = View.GONE
        llResult.visibility = View.VISIBLE
        etText.setText(raw)

        val best = hits.firstOrNull()
        if (best == null) {
            tvAnswer.text = "没找到"
            tvAnswer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            tvAnswer.setTextColor(Color.parseColor("#DC2626"))
            tvConf.text = "题库里没有相似的题目，检查一下题库是否选对"
            tvStem.text = ""
            llCands.removeAllViews()
            tvCandsTitle.visibility = View.GONE
            return
        }

        val pct = (best.score * 100).toInt()
        tvAnswer.text = best.qa.answer.ifBlank { "—" }
        tvAnswer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        tvAnswer.setTextColor(
            Color.parseColor(if (best.tier == "high") "#2563EB" else "#D97706")
        )
        tvConf.text = when (best.tier) {
            "high" -> "匹配度 $pct%"
            "medium" -> "匹配度 $pct% · 和第二名接近，核对下面的候选"
            else -> "匹配度 $pct% · 题库里可能没有这道题"
        }
        tvStem.text = best.qa.question

        llCands.removeAllViews()
        val others = hits.drop(1)
        if (others.isEmpty()) {
            tvCandsTitle.visibility = View.GONE
        } else {
            tvCandsTitle.visibility = View.VISIBLE
            for (h in others) llCands.addView(candidateView(h))
        }
    }

    private fun candidateView(h: SmartMatcher.Hit): TextView {
        val pct = (h.score * 100).toInt()
        val tv = TextView(this)
        tv.text = "${h.qa.question.take(40)}\n答案：${h.qa.answer}　($pct%)"
        tv.textSize = 13f
        tv.setTextColor(Color.parseColor("#374151"))
        tv.setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        tv.background = roundedBg(Color.WHITE, Color.parseColor("#E0E0E0"))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = 8.dp()
        tv.layoutParams = lp
        tv.setOnClickListener { render(h.qa.question, listOf(h)) }
        return tv
    }

    private fun showBusy(on: Boolean, text: String) {
        pb.visibility = if (on) View.VISIBLE else View.GONE
        tvStatus.visibility = if (on) View.VISIBLE else View.GONE
        if (on) tvStatus.text = text
    }

    // ---------------- 图片处理 ----------------
    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** 相机照片的旋转写在 EXIF 里，不转的话喂给 OCR 是歪的 */
    @Suppress("DEPRECATION")
    private fun orient(file: File, bmp: Bitmap): Bitmap {
        val degrees = try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
        if (degrees == 0) return bmp
        return try {
            Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true
            )
        } catch (e: Exception) {
            bmp
        }
    }

    // ---------------- 界面 ----------------
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#F5F5F5"))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(20.dp(), 24.dp(), 20.dp(), 40.dp())
        scroll.addView(root)

        root.addView(title("拍照搜题"))
        root.addView(subtitle("对着电脑屏幕拍一张，自动从题库里找答案"))

        val btnShoot = Button(this)
        btnShoot.text = "📷 拍题"
        btnShoot.textSize = 18f
        btnShoot.setBackgroundColor(Color.parseColor("#2563EB"))
        btnShoot.setTextColor(Color.WHITE)
        btnShoot.setOnClickListener { launchCamera() }
        root.addView(btnShoot, lp(56, 14))

        val btnGallery = Button(this)
        btnGallery.text = "🖼 从相册选"
        btnGallery.textSize = 15f
        btnGallery.setOnClickListener { pickImage.launch("image/*") }
        root.addView(btnGallery, lp(46, 10))

        pb = ProgressBar(this)
        pb.visibility = View.GONE
        val plp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        plp.topMargin = 12.dp()
        root.addView(pb, plp)

        tvStatus = TextView(this)
        tvStatus.textSize = 13f
        tvStatus.setTextColor(Color.parseColor("#888888"))
        tvStatus.gravity = android.view.Gravity.CENTER
        tvStatus.visibility = View.GONE
        root.addView(tvStatus, lpWrap(6))

        ivPreview = ImageView(this)
        ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
        ivPreview.visibility = View.GONE
        val ilp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 160.dp()
        )
        ilp.topMargin = 10.dp()
        root.addView(ivPreview, ilp)

        llResult = LinearLayout(this)
        llResult.orientation = LinearLayout.VERTICAL
        llResult.visibility = View.GONE
        llResult.setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        llResult.background = roundedBg(Color.WHITE, Color.parseColor("#E8E8E8"))
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rlp.topMargin = 16.dp()
        root.addView(llResult, rlp)

        tvAnswer = TextView(this)
        tvAnswer.textSize = 44f
        tvAnswer.setTypeface(null, android.graphics.Typeface.BOLD)
        tvAnswer.gravity = android.view.Gravity.CENTER
        tvAnswer.setTextColor(Color.parseColor("#2563EB"))
        llResult.addView(tvAnswer)

        tvConf = TextView(this)
        tvConf.textSize = 13f
        tvConf.gravity = android.view.Gravity.CENTER
        tvConf.setTextColor(Color.parseColor("#888888"))
        llResult.addView(tvConf)

        tvStem = TextView(this)
        tvStem.textSize = 15f
        tvStem.setTextColor(Color.parseColor("#333333"))
        tvStem.setPadding(0, 12.dp(), 0, 6.dp())
        llResult.addView(tvStem)

        tvCandsTitle = TextView(this)
        tvCandsTitle.text = "不是这道题？点其他候选"
        tvCandsTitle.textSize = 13f
        tvCandsTitle.setTextColor(Color.parseColor("#888888"))
        tvCandsTitle.visibility = View.GONE
        llResult.addView(tvCandsTitle, lpWrap(6))

        llCands = LinearLayout(this)
        llCands.orientation = LinearLayout.VERTICAL
        llResult.addView(llCands)

        val label = TextView(this)
        label.text = "识别到的文字（不准可以直接改）"
        label.textSize = 13f
        label.setTextColor(Color.parseColor("#888888"))
        llResult.addView(label, lpWrap(14))

        etText = EditText(this)
        etText.minLines = 3
        etText.textSize = 14f
        etText.setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        etText.setBackgroundColor(Color.parseColor("#FAFAFA"))
        llResult.addView(etText, lpWrap(6))

        val btnRe = Button(this)
        btnRe.text = "按改后的文字重新搜"
        btnRe.textSize = 14f
        btnRe.setOnClickListener {
            val t = etText.text.toString().trim()
            if (t.isNotEmpty()) runSearch(t)
        }
        llResult.addView(btnRe, lp(46, 8))

        return scroll
    }

    // ---------------- 小工具 ----------------
    private fun title(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 26f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.parseColor("#333333"))
        gravity = android.view.Gravity.CENTER
    }

    private fun subtitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#888888"))
        gravity = android.view.Gravity.CENTER
        setPadding(0, 4.dp(), 0, 18.dp())
    }

    private fun lp(heightDp: Int, topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightDp.dp()
        ).apply { topMargin = topMarginDp.dp() }

    private fun lpWrap(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = topMarginDp.dp() }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(fill)
            setStroke(dp1(), stroke)
        }

    private fun dp1(): Int = (1 * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
