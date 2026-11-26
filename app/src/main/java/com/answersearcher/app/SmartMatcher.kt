package com.answersearcher.app

import com.answersearcher.app.model.ExcelData
import com.answersearcher.app.model.QAPair
import java.text.Normalizer
import kotlin.math.ln

/**
 * 匹配引擎（相机通道专用，不动原来的 SearchEngine，悬浮窗功能完全不受影响）
 *
 * 为什么不用原来的 LCS：
 *   1. 原实现按「公共子序列的绝对长度」排序，题库里的长题目天然占优——
 *      一道 60 字的题命中 20 个字，会压过一道 20 字的题命中 18 个字（占 90%）。
 *   2. 没有 IDF，「解体调车计划」和「编组调车计划」只差两个字，LCS 几乎一样长，
 *      谁先遍历到就算谁。
 *
 * 这里换成：字符 2/3-gram + IDF 加权 Dice + 包含度，再做两道保险：
 *   ① 数字实体惩罚：题干只差一个数字时，靠 n-gram 拉不开差距
 *   ② 否定语义单向惩罚：query 有「不/禁止」，库里没有 → 大概率是反义题
 */
object SmartMatcher {

    /** 一条命中 */
    data class Hit(val qa: QAPair, val score: Double, val tier: String)

    private val spaceRe = Regex("""\s+""")

    // 这些符号在 OCR 结果里没有区分度，直接去掉
    private val punctRe = Regex(
        """[，。、；：？！（）【】\[\]《》〈〉“”‘’"'·—…~`!@#%^&*_+=|\\/<>?,.;:()\-－　]"""
    )

    // 数字实体：「40km/h」「4年」「6000元」往往是两道题唯一的分歧点
    private val numRe = Regex(
        """\d+(?:\.\d+)?\s*(?:元|年|个月|月|周|天|日|小时|分钟|分|秒|米|千米|公里|km|kg|吨|%|‰|人|名|次|处|辆|列|组|台|道|项)"""
    )

    // 否定词：一删就出事。「属于」和「不属于」是两道不同的题
    private val negMarkers = listOf("不", "未", "禁止", "严禁", "不得", "不许", "不能", "无需", "无须")

    // ---------------- 索引缓存：题库不变就不重建 ----------------
    private var cachedData: ExcelData? = null
    private var cachedIndex: Index? = null

    fun search(text: String, data: ExcelData, topK: Int = 4): List<Hit> {
        val idx = cachedIndex.takeIf { cachedData === data } ?: Index(data).also {
            cachedIndex = it
            cachedData = data
        }
        return idx.search(text, topK)
    }

    // ---------------- 文本工具 ----------------
    fun normalize(src: String?): String {
        if (src.isNullOrEmpty()) return ""
        var s = Normalizer.normalize(src, Normalizer.Form.NFKC).lowercase()
        s = spaceRe.replace(s, "")
        s = punctRe.replace(s, "")
        return s
    }

    fun ngrams(s: String, n: Int): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length <= n) return setOf(s)
        val out = HashSet<String>(s.length * 2)
        for (i in 0..s.length - n) out.add(s.substring(i, i + n))
        return out
    }

    fun numbers(text: String?): Set<String> {
        if (text.isNullOrEmpty()) return emptySet()
        return numRe.findAll(text).map { it.value.replace(" ", "").lowercase() }.toSet()
    }

    fun hasNegation(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return negMarkers.any { text.contains(it) }
    }

    /** 置信度：high 可直接信；medium 要看候选；low 基本没匹配上 */
    fun tierOf(score: Double, gap: Double): String = when {
        score >= 0.75 && gap >= 0.08 -> "high"
        gap >= 0.30 && score >= 0.45 -> "high"
        score >= 0.55 -> "medium"
        else -> "low"
    }

    // ---------------- 索引 ----------------
    class Index(private val data: ExcelData) {

        private val grams = ArrayList<Set<String>>(data.pairs.size)
        private val nums = ArrayList<Set<String>>(data.pairs.size)
        private val negs = ArrayList<Boolean>(data.pairs.size)
        private val idf = HashMap<String, Double>()

        init {
            val df = HashMap<String, Int>()
            for (qa in data.pairs) {
                val n = normalize(qa.question)
                val g = ngrams(n, 2) + ngrams(n, 3)
                grams.add(g)
                nums.add(numbers(qa.question))
                negs.add(hasNegation(n))
                for (x in g) df[x] = (df[x] ?: 0) + 1
            }
            val total = maxOf(data.pairs.size, 1).toDouble()
            for ((g, c) in df) idf[g] = ln((total + 1.0) / (c + 1.0)) + 1.0
        }

        private fun dice(q: Set<String>, d: Set<String>): Double {
            if (q.isEmpty() || d.isEmpty()) return 0.0
            var inter = 0.0
            var wq = 0.0
            var wd = 0.0
            for (g in q) {
                val w = idf[g] ?: 1.0
                wq += w
                if (d.contains(g)) inter += w
            }
            for (g in d) wd += (idf[g] ?: 1.0)
            return if (wq + wd == 0.0) 0.0 else 2.0 * inter / (wq + wd)
        }

        /** 拍半道题时 query 是 doc 的子集，Dice 会偏低，包含度仍然高 */
        private fun containment(q: Set<String>, d: Set<String>): Double {
            if (q.isEmpty()) return 0.0
            var inter = 0.0
            var wq = 0.0
            for (g in q) {
                val w = idf[g] ?: 1.0
                wq += w
                if (d.contains(g)) inter += w
            }
            return if (wq == 0.0) 0.0 else inter / wq
        }

        fun search(text: String, topK: Int): List<Hit> {
            val qNorm = normalize(text)
            if (qNorm.length < 2 || data.pairs.isEmpty()) return emptyList()

            val qg = ngrams(qNorm, 2) + ngrams(qNorm, 3)
            val qNums = numbers(text)
            val qNeg = hasNegation(qNorm)

            val scored = ArrayList<Pair<Int, Double>>(data.pairs.size)
            for (i in data.pairs.indices) {
                var s = 0.6 * dice(qg, grams[i]) + 0.4 * containment(qg, grams[i])

                if (qNums.isNotEmpty()) {
                    val hit = qNums.count { nums[i].contains(it) }
                    s *= 0.82 + 0.18 * (hit.toDouble() / qNums.size)
                }
                // 只做单向惩罚：反方向不罚，因为 OCR 漏掉「不」很常见
                if (qNeg && !negs[i]) s *= 0.75

                if (s > 0.0) scored.add(i to s)
            }
            if (scored.isEmpty()) return emptyList()

            scored.sortByDescending { it.second }
            val gap = if (scored.size > 1) scored[0].second - scored[1].second else scored[0].second

            val out = ArrayList<Hit>(minOf(topK, scored.size))
            for (rank in 0 until minOf(topK, scored.size)) {
                val (i, s) = scored[rank]
                out.add(Hit(data.pairs[i], s, tierOf(s, if (rank == 0) gap else 0.0)))
            }
            return out
        }
    }
}
