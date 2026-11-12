package com.aly.sahlquran

import android.content.Context

/**
 * The Quran text (Tanzil Uthmani, verbatim) and its metadata.
 * Assets are added by the cloud build, which downloads them from tanzil.net and checks
 * that exactly 6,236 ayahs are present (see .github/workflows/build.yml).
 */
class QuranData private constructor(
    /** ayahs[s-1][a-1] = text of ayah a of surah s */
    val ayahs: List<List<String>>,
    /** Madinah Mushaf page starts (page 1 = index 0) as (surah, ayah), or empty when unavailable */
    val pageStarts: List<Pair<Int, Int>>,
    val juzStarts: List<Pair<Int, Int>>
) {
    companion object {
        const val TOTAL_AYAHS = 6236

        // Juz' starts of the Madinah Mushaf (used when the metadata file isn't available)
        private val JUZ = listOf(1 to 1, 2 to 142, 2 to 253, 3 to 93, 4 to 24, 4 to 148, 5 to 82, 6 to 111, 7 to 88, 8 to 41,
            9 to 93, 11 to 6, 12 to 53, 15 to 1, 17 to 1, 18 to 75, 21 to 1, 23 to 1, 25 to 21, 27 to 56,
            29 to 46, 33 to 31, 36 to 28, 39 to 32, 41 to 47, 46 to 1, 51 to 31, 58 to 1, 67 to 1, 78 to 1)

        val NAMES_AR = listOf("الفاتحة", "البقرة", "آل عمران", "النساء", "المائدة", "الأنعام", "الأعراف", "الأنفال", "التوبة", "يونس",
            "هود", "يوسف", "الرعد", "إبراهيم", "الحجر", "النحل", "الإسراء", "الكهف", "مريم", "طه",
            "الأنبياء", "الحج", "المؤمنون", "النور", "الفرقان", "الشعراء", "النمل", "القصص", "العنكبوت", "الروم",
            "لقمان", "السجدة", "الأحزاب", "سبأ", "فاطر", "يس", "الصافات", "ص", "الزمر", "غافر",
            "فصلت", "الشورى", "الزخرف", "الدخان", "الجاثية", "الأحقاف", "محمد", "الفتح", "الحجرات", "ق",
            "الذاريات", "الطور", "النجم", "القمر", "الرحمن", "الواقعة", "الحديد", "المجادلة", "الحشر", "الممتحنة",
            "الصف", "الجمعة", "المنافقون", "التغابن", "الطلاق", "التحريم", "الملك", "القلم", "الحاقة", "المعارج",
            "نوح", "الجن", "المزمل", "المدثر", "القيامة", "الإنسان", "المرسلات", "النبأ", "النازعات", "عبس",
            "التكوير", "الانفطار", "المطففين", "الانشقاق", "البروج", "الطارق", "الأعلى", "الغاشية", "الفجر", "البلد",
            "الشمس", "الليل", "الضحى", "الشرح", "التين", "العلق", "القدر", "البينة", "الزلزلة", "العاديات",
            "القارعة", "التكاثر", "العصر", "الهمزة", "الفيل", "قريش", "الماعون", "الكوثر", "الكافرون", "النصر",
            "المسد", "الإخلاص", "الفلق", "الناس")

        val NAMES_EN = listOf("Al-Fatihah", "Al-Baqarah", "Aal-Imran", "An-Nisa", "Al-Ma'idah", "Al-An'am", "Al-A'raf", "Al-Anfal", "At-Tawbah", "Yunus",
            "Hud", "Yusuf", "Ar-Ra'd", "Ibrahim", "Al-Hijr", "An-Nahl", "Al-Isra", "Al-Kahf", "Maryam", "Ta-Ha",
            "Al-Anbiya", "Al-Hajj", "Al-Mu'minun", "An-Nur", "Al-Furqan", "Ash-Shu'ara", "An-Naml", "Al-Qasas", "Al-Ankabut", "Ar-Rum",
            "Luqman", "As-Sajdah", "Al-Ahzab", "Saba", "Fatir", "Ya-Sin", "As-Saffat", "Sad", "Az-Zumar", "Ghafir",
            "Fussilat", "Ash-Shura", "Az-Zukhruf", "Ad-Dukhan", "Al-Jathiyah", "Al-Ahqaf", "Muhammad", "Al-Fath", "Al-Hujurat", "Qaf",
            "Adh-Dhariyat", "At-Tur", "An-Najm", "Al-Qamar", "Ar-Rahman", "Al-Waqi'ah", "Al-Hadid", "Al-Mujadilah", "Al-Hashr", "Al-Mumtahanah",
            "As-Saff", "Al-Jumu'ah", "Al-Munafiqun", "At-Taghabun", "At-Talaq", "At-Tahrim", "Al-Mulk", "Al-Qalam", "Al-Haqqah", "Al-Ma'arij",
            "Nuh", "Al-Jinn", "Al-Muzzammil", "Al-Muddaththir", "Al-Qiyamah", "Al-Insan", "Al-Mursalat", "An-Naba", "An-Nazi'at", "Abasa",
            "At-Takwir", "Al-Infitar", "Al-Mutaffifin", "Al-Inshiqaq", "Al-Buruj", "At-Tariq", "Al-A'la", "Al-Ghashiyah", "Al-Fajr", "Al-Balad",
            "Ash-Shams", "Al-Layl", "Ad-Duha", "Ash-Sharh", "At-Tin", "Al-Alaq", "Al-Qadr", "Al-Bayyinah", "Az-Zalzalah", "Al-Adiyat",
            "Al-Qari'ah", "At-Takathur", "Al-Asr", "Al-Humazah", "Al-Fil", "Quraysh", "Al-Ma'un", "Al-Kawthar", "Al-Kafirun", "An-Nasr",
            "Al-Masad", "Al-Ikhlas", "Al-Falaq", "An-Nas")

        /** Parses Tanzil's "txt-2" format: one ayah per line as  surah|ayah|text ; '#' lines are the licence notice. */
        fun parseText(lines: Sequence<String>): List<List<String>>? {
            val out = List(114) { ArrayList<String>() }
            var n = 0
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('|', limit = 3)
                if (p.size != 3) return null
                val s = p[0].toIntOrNull() ?: return null
                val a = p[1].toIntOrNull() ?: return null
                if (s !in 1..114 || a != out[s - 1].size + 1) return null      // must be in order, no gaps
                out[s - 1].add(p[2]); n++
            }
            return if (n == TOTAL_AYAHS) out else null
        }

        /** Reads "QuranData.Page = [ [], [1, 1], [2, 1], ... ];" (and the same for Juz) from Tanzil's quran-data.js. */
        fun parseStarts(js: String, name: String): List<Pair<Int, Int>> {
            val start = js.indexOf("QuranData.$name")
            if (start < 0) return emptyList()
            val end = js.indexOf("];", start)
            if (end < 0) return emptyList()
            val re = Regex("\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
            return re.findAll(js.substring(start, end)).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        }

        fun load(ctx: Context): QuranData? {
            val ayahs = try {
                ctx.assets.open("quran-uthmani.txt").bufferedReader(Charsets.UTF_8).useLines { parseText(it) }
            } catch (_: Exception) { null } ?: return null
            var pages = emptyList<Pair<Int, Int>>()
            var juz = JUZ
            try {
                val js = ctx.assets.open("quran-data.js").bufferedReader(Charsets.UTF_8).readText()
                // the lists end with a [115, 1] "end" marker: drop it
                val p = parseStarts(js, "Page").filter { it.first in 1..114 }
                if (p.size == 604) pages = p
                val j = parseStarts(js, "Juz").filter { it.first in 1..114 }
                if (j.size == 30) juz = j
            } catch (_: Exception) {}
            return QuranData(ayahs, pages, juz)
        }
    }

    fun ayahCount(s: Int) = ayahs[s - 1].size

    private fun ordinal(s: Int, a: Int) = s * 1000 + a

    /** 1-based juz' that contains (s, a) */
    fun juzOf(s: Int, a: Int): Int {
        val o = ordinal(s, a)
        var j = 1
        for ((i, st) in juzStarts.withIndex()) if (ordinal(st.first, st.second) <= o) j = i + 1
        return j
    }

    private val pageCache = HashMap<Int, List<Pair<Int, Int>>>()

    /** Every (surah, ayah) printed on Madinah Mushaf page [p] (1..604). */
    fun pageAyahs(p: Int): List<Pair<Int, Int>> {
        if (pageStarts.size != 604 || p !in 1..604) return emptyList()
        pageCache[p]?.let { return it }
        val out = ArrayList<Pair<Int, Int>>()
        var (s, a) = pageStarts[p - 1]
        val end = if (p < 604) pageStarts[p] else 115 to 1
        while (s < 115 && (s < end.first || (s == end.first && a < end.second))) {
            out.add(s to a)
            a++
            if (a > ayahCount(s)) { s++; a = 1 }
        }
        pageCache[p] = out
        return out
    }

    /** 1-based Mushaf page of (s, a), or 0 when page data isn't available */
    fun pageOf(s: Int, a: Int): Int {
        if (pageStarts.isEmpty()) return 0
        val o = ordinal(s, a)
        var lo = 0; var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ordinal(pageStarts[mid].first, pageStarts[mid].second) <= o) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}
