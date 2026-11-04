package app.sada.poc

/**
 * Reads and checks sherpa-onnx keyword lines, e.g.  "▁HE Y ▁SA DA :2.0 #0.25 @HEY_SADA".
 * Used on the phone (to show which keywords are registered) and in unit tests. No Android classes.
 */
data class KeywordEntry(val tokens: List<String>, val score: Double?, val threshold: Double?, val label: String?)

object KeywordFile {
    private val space = Regex("\\s+")

    fun parseLine(line: String): KeywordEntry? {
        val parts = line.trim().split(space).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val tokens = parts.takeWhile { !it.startsWith(":") && !it.startsWith("#") && !it.startsWith("@") }
        val score = parts.firstOrNull { it.startsWith(":") }?.drop(1)?.toDoubleOrNull()
        val threshold = parts.firstOrNull { it.startsWith("#") }?.drop(1)?.toDoubleOrNull()
        val at = parts.indexOfFirst { it.startsWith("@") }
        val label = if (at >= 0) parts.subList(at, parts.size).joinToString(" ").removePrefix("@").ifBlank { null } else null
        return KeywordEntry(tokens, score, threshold, label)
    }

    /** Token vocabulary from tokens.txt ("<token> <id>" per line). */
    fun vocabulary(tokensTxt: List<String>): Set<String> =
        tokensTxt.mapNotNull { l -> l.trim().split(space).firstOrNull()?.takeIf { it.isNotEmpty() } }.toSet()

    /** Returns problems found; empty means every line is a valid entry and all required labels exist. */
    fun validate(lines: List<String>, vocab: Set<String>, requiredLabels: List<String>): List<String> {
        val issues = ArrayList<String>()
        val labels = HashSet<String>()
        lines.forEachIndexed { i, raw ->
            val e = parseLine(raw) ?: return@forEachIndexed
            val where = "line ${i + 1}"
            if (e.tokens.isEmpty()) issues.add("$where: no tokens")
            val unknown = e.tokens.filter { it !in vocab }
            if (unknown.isNotEmpty()) issues.add("$where: tokens not in the model vocabulary: ${unknown.joinToString(" ")}")
            if (e.tokens.any { t -> t.any { it == ':' || it == '#' || it == '@' } }) issues.add("$where: options were turned into tokens (bad conversion)")
            if (e.label == null) issues.add("$where: no @label")
            if (e.threshold != null && (e.threshold <= 0.0 || e.threshold >= 1.0)) issues.add("$where: threshold ${e.threshold} outside 0..1")
            if (e.score != null && e.score <= 0.0) issues.add("$where: score ${e.score} must be positive")
            e.label?.let { labels.add(it) }
        }
        for (r in requiredLabels) if (labels.none { it.startsWith(r) }) issues.add("required keyword $r is missing")
        return issues
    }

    /** Lowers every threshold to [threshold] (used for the diagnostic near-miss listener only). */
    fun withThreshold(lines: List<String>, threshold: Double): List<String> = lines.filter { it.isNotBlank() }.map { line ->
        val m = Regex("#([0-9.]+)").find(line)
        val t = "#" + String.format(java.util.Locale.US, "%.2f", threshold)
        if (m != null) line.replace(m.value, t) else {
            val at = line.indexOf(" @")
            if (at >= 0) line.substring(0, at) + " " + t + line.substring(at) else "$line $t"
        }
    }
}
