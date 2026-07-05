package com.ams.wallverse

import java.text.Normalizer

data class ModerationResult(
    val allowed: Boolean,
    val reason: String? = null,    // short human message to show user/logs
    val category: String? = null   // "child_safety", "sexual", "violence", "drugs", "hate"
)

object PromptModerator {

    private val leet = mapOf(
        '0' to 'o', '1' to 'i', '2' to 'z', '3' to 'e', '4' to 'a', '5' to 's',
        '6' to 'g', '7' to 't', '8' to 'b', '9' to 'g', '@' to 'a', '$' to 's'
    )

    // Stems, not full words (catch variations)
    private val stemsChild = setOf("teen","minor","underage","child","kid","schoolg","schoolb","loli","shota","cp","prete","young")
    private val stemsSex   = setOf("sex","nude","nud","porn","erot","nsfw","breast","incest","fetish","explicit")
    private val stemsViol  = setOf("gore","behead","snuff","murder","kill","rape","assault","genocide")
    private val stemsDrug  = setOf("drug","cocain","heroin","meth","weed","marijuana","sell drug")
    private val stemsHate  = setOf("genocid","supremac","dehuman","slur") // keep generic; add server-side specifics

    // Pairs that are especially problematic if they co-occur within a short window
    private val proximityRules: List<Triple<Set<String>, Set<String>, String>> = listOf(
        Triple(stemsChild, stemsSex,   "child_safety"),
        Triple(stemsChild, stemsViol,  "child_safety"),
        Triple(stemsSex,   stemsViol,  "sexual_violence")
    )

    // Phrase-like regex (after normalization; dots match any char)
    private val badPhrases = listOf(
        Regex("""\b(child|minor|under\s*age)\s+\w{0,3}\s+(sex|sexual|nude|porn)\b"""),
        Regex("""\b(sexual)\s+\w{0,2}\s+(violence|assault|rape)\b"""),
        Regex("""\b(sell|buy|distribut(e|ion))\s+\w{0,3}\s+(drug|cocaine|heroin|meth|weed)\b""")
    )

    // Small allow-list to reduce false positives (applied post-detection)
    private val allowPhrases = listOf(
        Regex("""\bbreast\s+cancer\b"""),
        Regex("""\bsex\s+education\b"""),
        Regex("""\banti[-\s]*violence\b"""),
    )

    // Edit distance 1 for critical stems (catch “tєen”, “tean”, etc.)
    private fun nearMatch(word: String, stems: Set<String>): Boolean {
        for (stem in stems) {
            if (word.contains(stem)) return true
            if (editDistanceLeq1(word, stem)) return true
        }
        return false
    }

    private fun editDistanceLeq1(a: String, b: String): Boolean {
        val la = a.length; val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (edits == 1) return false
            edits++
            when {
                la > lb -> i++          // deletion in a
                lb > la -> j++          // insertion in a
                else -> { i++; j++ }    // substitution
            }
        }
        // account for trailing char
        if (i < la || j < lb) edits++
        return edits <= 1
    }

    /** Normalize: lowercase, NFKD, remove accents, map leet, remove punctuation, collapse repeats */
    private fun normalize(input: String): String {
        val lower = input.lowercase()
        val decomp = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomp.length)
        var last = '\u0000'
        for (ch in decomp) {
            val mapped = when {
                ch in leet -> leet[ch]!!
                ch.isLetter() -> ch
                ch.isDigit() -> continue        // drop digits (often obfuscation)
                ch.isWhitespace() -> ' '
                else -> ' '                     // strip punctuation/symbols
            }
            // collapse repeats like "seeeexxx" -> "sex"
            if (mapped == last && mapped != ' ') continue
            sb.append(mapped)
            last = mapped
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** Split normalized text into tokens */
    private fun tokensOf(norm: String): List<String> =
        norm.split(' ').filter { it.isNotBlank() }

    /** Windowed proximity scan: any word from A near any word from B within k tokens */
    private fun hasProximity(tokens: List<String>, a: Set<String>, b: Set<String>, k: Int = 4): Boolean {
        val idxA = tokens.withIndex().filter { (_, w) -> nearMatch(w, a) }.map { it.index }
        val idxB = tokens.withIndex().filter { (_, w) -> nearMatch(w, b) }.map { it.index }
        for (i in idxA) for (j in idxB) if (kotlin.math.abs(i - j) <= k) return true
        return false
    }

    private fun allowedByAllowList(norm: String): Boolean {
        return allowPhrases.any { it.containsMatchIn(norm) }
    }

    fun check(prompt: String): ModerationResult {
        if (prompt.isBlank()) return ModerationResult(allowed = false, reason = "Empty prompt")
        val norm = normalize(prompt)
        val toks = tokensOf(norm)

        // Phrase checks first
        for (rx in badPhrases) {
            if (rx.containsMatchIn(norm)) {
                if (allowedByAllowList(norm)) continue
                return ModerationResult(false, "This prompt may produce restricted content.", "phrase")
            }
        }

        // Single-category checks (with fuzzy stem matching)
        if (toks.any { nearMatch(it, stemsChild) })  return ModerationResult(false, "Child safety risk.", "child_safety")
        if (toks.any { nearMatch(it, stemsSex) })    return ModerationResult(false, "Sexual content is not allowed.", "sexual")
        if (toks.any { nearMatch(it, stemsViol) })   return ModerationResult(false, "Graphic violence is not allowed.", "violence")
        if (toks.any { nearMatch(it, stemsDrug) })   return ModerationResult(false, "Illegal drugs content is not allowed.", "drugs")
        if (toks.any { nearMatch(it, stemsHate) })   return ModerationResult(false, "Hate/harassment is not allowed.", "hate")

        // Proximity rules (e.g., minor near sex)
        for ((a, b, cat) in proximityRules) {
            if (hasProximity(toks, a, b)) {
                if (allowedByAllowList(norm)) continue
                val msg = when (cat) {
                    "child_safety"    -> "Content involving minors + adult themes is not allowed."
                    "sexual_violence" -> "Sexual violence is not allowed."
                    else -> "This prompt may produce restricted content."
                }
                return ModerationResult(false, msg, cat)
            }
        }

        return ModerationResult(true)
    }
}
