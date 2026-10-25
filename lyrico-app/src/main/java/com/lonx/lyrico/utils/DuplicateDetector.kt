package com.lonx.lyrico.utils

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 文件名查重引擎 —— UnionFind + bigram 倒排 + 归一 + 保优。
 *
 * 防误删体系（缺一不可，全是踩过坑补出来的）：
 *  - 艺人前缀归一：「艺人-歌名 / 歌名」按歌名比，艺人名剥掉另存；
 *  - 时长复核：名字证据再强也要过时长（差 >max(3s, 2%) 就是不同版本）——
 *    「歌名 (1)」完全可能是前后各切一段的剪辑版，光看名字分不出来；
 *  - 标签艺人区分翻唱：歌名相同但两边艺人都明确且不同 → 翻唱/重名，不并组；
 *  - 确证/疑似分档：相似度 <0.95、包含度兜底、读不到时长/艺人的一律标「疑似」，
 *    界面默认不勾选 —— 查重是提示，误删是不可逆的；
 *  - 疑似组内容采样核验：比一下「大小+头尾采样」的指纹，内容一致升确证、
 *    不同则明示「只是名字像」。
 *
 * 纯函数、无文件 IO（时长/艺人来自曲库表，内容指纹由调用方注入），可离线单测。
 */
object DuplicateDetector {

    /** 「确证」档门槛：相似度 ≥ 0.95 且不依赖包含度兜底的组。 */
    const val HIGH_CONFIDENCE = 0.95

    private const val SIMILARITY_THRESHOLD = 0.90
    private const val MIN_LENGTH_RATIO = 0.58

    /** 两个艺人名算「同一个人」的门槛：低于它视为不同歌手（含翻唱）。 */
    private const val ARTIST_SAME = 0.80

    /** 内容尚未核验（没有采样来源，或读取失败）。 */
    const val CONTENT_UNKNOWN = 0

    /** 采样指纹一致：内容确实是同一份。 */
    const val CONTENT_SAME = 1

    /** 采样指纹不同：名字像，内容不是同一份。 */
    const val CONTENT_DIFF = 2

    /** 可多次匹配的复制后缀（(1) / -副本 / copy / _2 …）。 */
    private val COPY_SUFFIX = Regex(
        """(?:[\s（(]\s*\d+\s*[)）]|[\s\-_]\s*(?:副本|拷贝|copy|複製|复制)\s*\d*)*$""",
        RegexOption.IGNORE_CASE
    )

    /** 判断「是否带复制痕迹」用（不能复用 COPY_SUFFIX，它会匹配空串）。 */
    private val COPY_MARK = Regex(
        """[（(]\s*\d+\s*[)）]|[\-_]\s*(?:副本|拷贝|copy|複製|复制)\s*\d*\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 去掉末尾数字尾巴（如「晴天 2」「晴天(1)」→「晴天」）。 */
    fun stripTrailingNumericSuffix(s: String): String {
        return s.replace(Regex("""\s*[（(]?\s*\d+\s*[)）]?\s*$"""), "").trim()
    }

    /** 归一成查重键：去复制后缀 + 繁转简 + 小写 + 去标点空格，只保留字母数字。 */
    fun normalizeDuplicateKey(stem: String?): String {
        var text = stripTrailingNumericSuffix(stem ?: "")
        text = COPY_SUFFIX.replace(text, "")
        text = ChineseText.toSimplified(text).lowercase()
        text = text.replace(Regex("""\s+"""), "")
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 只留歌名的归一键：「陈奕迅-十年」→「十年」；解析不出艺人时等价于整串归一。
     * 查重真正要比的是歌名，艺人只是同一首歌的额外信息。
     */
    fun titleKeyOf(stem: String?): String {
        val s = stem ?: ""
        val split = FileNameParser.splitArtistTitle(s)
        val title = split?.right ?: FileNameParser.stripNameNoise(s)
        return normalizeDuplicateKey(title)
    }

    /** 只留艺人的归一键；解析不出艺人时返回空串。 */
    fun artistKeyOf(stem: String?): String {
        val split = FileNameParser.splitArtistTitle(stem ?: "")
        return if (split == null) "" else normalizeDuplicateKey(split.left)
    }

    /** 整串比较前的长度比门槛（标题比较不走这道门，标题本来就短）。 */
    private fun lengthGateOk(a: String, b: String): Boolean {
        val minLen = min(a.length, b.length)
        val maxLen = max(a.length, b.length)
        return minLen > 0 && minLen.toDouble() / maxLen >= MIN_LENGTH_RATIO
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val out = HashSet<String>(text.length - 1)
        for (i in 0 until text.length - 1) {
            out.add(text.substring(i, i + 2))
        }
        return out
    }

    /** 建议保留组内哪一个；-1 表示没有明显更优的。规则：无复制痕迹 > 体积更大 > 路径更短。 */
    fun recommendKeep(group: DuplicateGroup): Int {
        if (group.files.isEmpty()) return -1
        var best = 0
        var bestScore = -1.0
        val maxSize = maxOf(1L, group.files.maxOfOrNull { it.size } ?: 1L)
        group.files.forEachIndexed { i, it ->
            var score = 0.0
            if (!COPY_MARK.containsMatchIn(it.stem())) score += 1.0
            score += 0.40 * (it.size.toDouble() / maxSize)
            score -= 0.20 * minOf(1.0, it.relativePath.length / 120.0)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** 摘要文案：「3 组（其中 1 组疑似）· 5 个多余文件 · 约 12.3 MB · 1 组已核验内容一致」。 */
    fun summary(groups: List<DuplicateGroup>): String {
        if (groups.isEmpty()) return "没有发现相似文件"
        var extra = 0
        var wasted = 0L
        var suspect = 0
        var verified = 0
        var differs = 0
        for (g in groups) {
            extra += maxOf(0, g.files.size - 1)
            wasted += g.wastedBytes
            if (g.suspect) suspect++
            when (g.content) {
                CONTENT_SAME -> verified++
                CONTENT_DIFF -> differs++
            }
        }
        val head = if (suspect > 0) "${groups.size} 组（其中 $suspect 组疑似）" else "${groups.size} 组"
        var tail = ""
        if (verified > 0) tail += " · $verified 组已核验内容一致"
        if (differs > 0) tail += " · $differs 组名字像但内容不同"
        return "$head · $extra 个多余文件 · 约 ${formatSize(wasted)}$tail"
    }

    /** 组内时长概览：「4:35 / 1:55 · 相差 2:40（部分未知）」；全都读不到时返回空串。 */
    fun durationSummary(group: DuplicateGroup): String {
        if (group.files.isEmpty()) return ""
        val text = StringBuilder()
        var minD = Double.MAX_VALUE
        var maxD = -1.0
        var known = 0
        for (it in group.files) {
            val d = it.durationMs / 1000.0
            if (d <= 0) continue
            val one = formatDuration(d)
            if (one.isEmpty()) continue
            if (text.isNotEmpty()) text.append(" / ")
            text.append(one)
            known++
            minD = min(minD, d)
            maxD = max(maxD, d)
        }
        if (text.isEmpty()) return ""
        if (maxD > minD) {
            val diff = round(maxD - minD).toLong()
            if (diff >= 1) text.append(" · 相差 ").append(formatDuration(diff.toDouble()))
        }
        if (known < group.files.size) text.append("（部分未知）")
        return text.toString()
    }

    /** 内容核验结果的一句话说明；未核验返回空串。 */
    fun contentLabel(group: DuplicateGroup): String {
        return when (group.content) {
            CONTENT_SAME -> "内容一致（首尾采样相同）"
            CONTENT_DIFF -> "内容不同（只是名字像）"
            else -> ""
        }
    }

    /**
     * 在 [items] 中找相似文件名分组（完全相同或相似度 ≥ 0.90）。
     *
     * [contentSampler] 为疑似组提供内容采样指纹（大小 + 头尾采样哈希，取不到返回空串）；
     * 传 null 时疑似组不做内容核验。
     */
    fun detect(
        items: List<DuplicateItem>,
        contentSampler: ((DuplicateItem) -> String)? = null
    ): List<DuplicateGroup> {
        val total = items.size
        if (total < 2) return emptyList()

        val keys = items.map { normalizeDuplicateKey(it.stem()) }
        val titleKeys = items.map { titleKeyOf(it.stem()) }
        val artistKeys = items.map { artistKeyOf(it.stem()) }
        val rawStems = items.map { it.stem() }

        val uf = UnionFind(total)

        // ① 归一键完全相同的先并上；是否「疑似」由下面的成对判定统一给结论
        val byKey = HashMap<String, MutableList<Int>>()
        for (i in 0 until total) {
            val key = keys[i]
            if (key.isNotEmpty()) byKey.getOrPut(key) { mutableListOf() }.add(i)
        }
        for (list in byKey.values) {
            for (j in 1 until list.size) uf.union(list[0], list[j])
        }

        // ② bigram 倒排 + 成对判定：综合相似度 + 歌名键，艺人/时长逐对把关
        val inverted = HashMap<String, MutableList<Int>>()
        for (i in 0 until total) {
            val key = keys[i]
            if (key.length < 2) continue
            for (gram in bigrams(key)) inverted.getOrPut(gram) { mutableListOf() }.add(i)
        }

        val compared = HashSet<Long>()
        // 靠「包含度」命中但拿不到时长/艺人佐证的文件：成组后整组标「疑似」
        val unknownDuration = BooleanArray(total)
        for (i in 0 until total) {
            val key = keys[i]
            if (key.length < 2) continue
            val candidates = HashSet<Int>()
            for (gram in bigrams(key)) {
                inverted[gram]?.let { list ->
                    val limit = minOf(list.size, 400)
                    for (j in 0 until limit) candidates.add(list[j])
                }
            }
            var count = 0
            for (other in candidates.sorted()) {
                if (other == i) continue
                val pair = if (i < other) (i.toLong() shl 32) or other.toLong() else (other.toLong() shl 32) or i.toLong()
                if (!compared.add(pair)) continue
                val otherKey = keys[other]
                if (otherKey.isEmpty()) continue
                val t1 = titleKeys[i]
                val t2 = titleKeys[other]
                val bothTitles = t1.isNotEmpty() && t2.isNotEmpty()

                var matched = false
                var containsDriven = false
                // 名字证据「强」：归一后完全相同（复制尾巴）或歌名一致（艺人前缀）。
                // 这类即使时长对不上也仍然列出来，但标疑似 —— 可能是同一首的剪辑版。
                var strongName = false
                // 艺人信息不全（文件名没写、库表也没有）时，名字不足以定论，必须靠时长
                var needDuration = false
                when {
                    keys[i] == keys[other] -> {
                        matched = true
                        strongName = true
                    }
                    bothTitles && t1 == t2 -> {
                        // 歌名一致：先看是谁唱的。同名不同歌手（翻唱）不是重复。
                        val a1 = artistOf(items[i], artistKeys[i])
                        val a2 = artistOf(items[other], artistKeys[other])
                        val bothArtists = a1.isNotEmpty() && a2.isNotEmpty()
                        if (bothArtists) {
                            matched = MusicMatchUtils.smartSimilarity(a1, a2) >= ARTIST_SAME
                            strongName = matched
                        } else {
                            // 有一边不知道是谁：名字不作数，交给时长；读不到时长则列出标疑似
                            matched = true
                            strongName = true
                            needDuration = true
                        }
                    }
                    else -> {
                        var fullScore = 0.0
                        var fullContains = 0.0
                        if (lengthGateOk(key, otherKey)) {
                            fullScore = MusicMatchUtils.smartSimilarity(key, otherKey)
                            fullContains = MusicMatchUtils.containsSimilarity(key, otherKey)
                        }
                        var titleScore = 0.0
                        var titleContains = 0.0
                        if (bothTitles) {
                            titleScore = MusicMatchUtils.smartSimilarity(t1, t2)
                            titleContains = MusicMatchUtils.containsSimilarity(t1, t2)
                        }
                        if (max(fullScore, titleScore) < SIMILARITY_THRESHOLD) {
                            matched = false
                        } else if (titleScore >= fullScore) {
                            matched = true
                            containsDriven = titleContains >= SIMILARITY_THRESHOLD
                        } else {
                            matched = true
                            containsDriven = fullContains >= SIMILARITY_THRESHOLD
                        }
                    }
                }

                if (matched && (strongName || containsDriven || needDuration)) {
                    // 同名同大小的基本就是一份副本，不做复核；其余都要拿时长把「剪辑版」挡下来
                    val identicalCopy = strongName && !needDuration &&
                        rawStems[i] == rawStems[other] && items[i].size == items[other].size
                    if (!identicalCopy) {
                        val d1 = items[i].durationMs / 1000.0
                        val d2 = items[other].durationMs / 1000.0
                        if (d1 > 0 && d2 > 0) {
                            val tol = max(3.0, max(d1, d2) * 0.02)
                            if (abs(d1 - d2) > tol) {
                                when {
                                    needDuration -> matched = false
                                    strongName -> {
                                        unknownDuration[i] = true
                                        unknownDuration[other] = true
                                    }
                                    else -> matched = false
                                }
                            }
                        } else {
                            unknownDuration[i] = true
                            unknownDuration[other] = true
                        }
                    }
                }
                if (matched) uf.union(i, other)
                count++
                if (count >= 500) break
            }
        }

        // ③ 收拢成组、补疑似与内容核验，只保留 ≥2 个文件的组
        val clusters = HashMap<Int, DuplicateGroup>()
        for (i in 0 until total) {
            val root = uf.find(i)
            val group = clusters.getOrPut(root) { DuplicateGroup(key = keys[i]) }
            group.files.add(items[i])
            if (unknownDuration[i]) group.suspect = true
        }
        val out = clusters.values.filter { it.files.size >= 2 }.toMutableList()
        for (group in out) {
            group.score = groupScore(group)
            // 相似度没到「确证」档的也标疑似：0.90~0.95 之间确实可能是不同版本
            group.suspect = group.suspect || group.score < HIGH_CONFIDENCE
            if (group.suspect && contentSampler != null) {
                // 疑似组再比一次首尾采样：能定性的就别让用户猜
                group.content = verifyContent(group, contentSampler)
                if (group.content == CONTENT_SAME) {
                    group.suspect = false
                }
            }
        }
        out.sortWith(
            compareByDescending<DuplicateGroup> { it.files.size }
                .thenByDescending { it.wastedBytes }
                .thenBy { it.key }
        )
        return out
    }

    /** 组内最高相似度（两两比较，组一般只有两三个文件，代价可忽略）。 */
    private fun groupScore(group: DuplicateGroup): Double {
        var best = 0.0
        for (i in group.files.indices) {
            val a = normalizeDuplicateKey(group.files[i].stem())
            val ta = titleKeyOf(group.files[i].stem())
            for (j in i + 1 until group.files.size) {
                val b = normalizeDuplicateKey(group.files[j].stem())
                val tb = titleKeyOf(group.files[j].stem())
                if (a == b) return 1.0
                // 标题一致（只差艺人前缀）按确证算 —— 这正是「艺人-歌名 / 歌名」的形态；
                // 但两边艺人明确不同（翻唱/重名）不按确证，交给下面的相似度打分
                if (ta.isNotEmpty() && ta == tb) {
                    val aa = artistKeyOf(group.files[i].stem())
                    val ab = artistKeyOf(group.files[j].stem())
                    val both = aa.isNotEmpty() && ab.isNotEmpty()
                    if (!both || MusicMatchUtils.smartSimilarity(aa, ab) >= ARTIST_SAME) {
                        return 1.0
                    }
                }
                // 必须与成组判定用同一个函数，否则界面显示 60% 却已经成组
                val s = max(MusicMatchUtils.smartSimilarity(a, b), MusicMatchUtils.smartSimilarity(ta, tb))
                if (s > best) best = s
            }
        }
        return best
    }

    /**
     * 取「这首歌是谁唱的」：文件名里的艺人优先，没有就用曲库表里的标签艺人。
     * 返回已归一的键；空串表示确实不知道。
     */
    private fun artistOf(item: DuplicateItem, nameArtist: String): String {
        if (nameArtist.isNotEmpty()) return nameArtist
        val tag = normalizeDuplicateKey(item.tagArtist)
        return if (isUnknownArtist(tag)) "" else tag
    }

    /** 「群星 / Various Artists / 未知」这类占位艺人等于没写，不能当成一个人去比对。 */
    private fun isUnknownArtist(normalized: String): Boolean {
        if (normalized.isEmpty()) return true
        val placeholders = arrayOf(
            "群星", "合辑", "未知", "未知艺人", "佚名",
            "various", "variousartists", "unknown", "unknownartist", "va", "ost"
        )
        return placeholders.any { it == normalized }
    }

    /** 疑似组的采样指纹核对：全部一致才算「内容一致」，有读不到的就当未知。 */
    private fun verifyContent(group: DuplicateGroup, sampler: (DuplicateItem) -> String): Int {
        var first: String? = null
        var anyMissing = false
        for (item in group.files) {
            val hash = try {
                sampler(item)
            } catch (ignored: Exception) {
                ""
            }
            if (hash.isNullOrEmpty()) {
                anyMissing = true
                continue
            }
            if (first == null) {
                first = hash
            } else if (first != hash) {
                return CONTENT_DIFF
            }
        }
        return if (first == null || anyMissing) CONTENT_UNKNOWN else CONTENT_SAME
    }

    /** 秒数 →「m:ss」/「h:mm:ss」；非正数返回空串。 */
    fun formatDuration(seconds: Double): String {
        if (seconds <= 0) return ""
        val total = round(seconds).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra
            } else {
                parent[rb] = ra
                rank[ra]++
            }
        }
    }
}

/** 查重扫描的一个文件条目（时长/标签艺人来自曲库表，进引擎时已在内存）。 */
data class DuplicateItem(
    val id: String,
    val fileName: String,
    val size: Long,
    val relativePath: String = "",
    val durationMs: Long = 0,
    val tagArtist: String = ""
) {
    fun stem(): String = fileName.substringBeforeLast(".", fileName)
}

/** 一组相似文件。 */
data class DuplicateGroup(
    val files: MutableList<DuplicateItem> = mutableListOf(),
    val key: String,
    var score: Double = 1.0
) {
    /**
     * 疑似：相似度落在确证档之下、靠包含度/缺失信息达成命中、或时长/艺人拿不到。
     * 界面据此默认不勾选，让用户先确认再删。
     */
    var suspect: Boolean = false

    /** 内容采样核验结果：[DuplicateDetector.CONTENT_UNKNOWN] / [DuplicateDetector.CONTENT_SAME] / [DuplicateDetector.CONTENT_DIFF]。 */
    var content: Int = DuplicateDetector.CONTENT_UNKNOWN

    /** 这一组最多能省多少空间（全删只留一个）。 */
    val wastedBytes: Long
        get() {
            val total = files.sumOf { it.size }
            val keep = DuplicateDetector.recommendKeep(this)
            val keepSize = if (keep >= 0) files[keep].size else 0L
            return maxOf(0L, total - keepSize)
        }

    val keepIndex: Int get() = DuplicateDetector.recommendKeep(this)
}
