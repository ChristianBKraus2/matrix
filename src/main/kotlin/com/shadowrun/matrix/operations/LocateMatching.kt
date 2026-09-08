package com.shadowrun.matrix.operations

/**
 * Regex-based query matching and vagueness derivation for Locate operations (ticket 06).
 *
 * The decker supplies a query that may use regex syntax. The *system* — not the decker — decides
 * how vague the query is from its shape: a complete literal name is the most specific (lowest TN
 * modifier), a short fragment wrapped in wildcards (`*frag*`) is the most vague (highest TN modifier).
 */

/** Regex metacharacters that count as "wildcard" content when judging query vagueness. */
private val META_CHARS = ".*+?[](){}^$|\\".toSet()

/**
 * Derives [QueryPrecision] from the shape of [query].
 *
 * - No metacharacters at all (a full literal name) → [QueryPrecision.VERY_SPECIFIC].
 * - A fragment wrapped in leading *and* trailing wildcards (`*frag*`, `.*frag.*`) → [QueryPrecision.VERY_VAGUE].
 * - A single leading or trailing wildcard → [QueryPrecision.VAGUE] (or VERY_VAGUE if mostly wildcards).
 * - Otherwise graded by how much of the query is literal text.
 */
fun queryPrecisionFromRegex(query: String): QueryPrecision {
    val q = query.trim()
    if (q.isEmpty()) return QueryPrecision.VERY_VAGUE

    val metaCount = q.count { it in META_CHARS }
    if (metaCount == 0) return QueryPrecision.VERY_SPECIFIC

    val literalRatio = (q.length - metaCount).toDouble() / q.length
    val hasLeadingWildcard = q.startsWith("*") || q.startsWith(".*")
    val hasTrailingWildcard = q.endsWith("*") || q.endsWith(".*")

    return when {
        hasLeadingWildcard && hasTrailingWildcard -> QueryPrecision.VERY_VAGUE
        hasLeadingWildcard || hasTrailingWildcard -> if (literalRatio >= 0.5) QueryPrecision.VAGUE else QueryPrecision.VERY_VAGUE
        literalRatio >= 0.75                      -> QueryPrecision.SPECIFIC
        else                                      -> QueryPrecision.NORMAL
    }
}

/**
 * Returns up to 5 of [candidates] whose name matches [query], ranked most-specific first
 * (exact full-string match, then shorter names, then alphabetically). Matching is case-insensitive.
 *
 * [query] is first interpreted as a regex; if that fails to compile it is retried as a shell-style
 * glob (`*` → `.*`, `?` → `.`) so the ticket's `*fragment*` form works. An uncompilable query
 * yields no matches.
 */
fun rankMatches(query: String, candidates: List<String>): List<String> {
    val regex = compileQuery(query) ?: return emptyList()
    return candidates.asSequence()
        .distinct()
        .filter { regex.containsMatchIn(it) }
        .sortedWith(
            compareByDescending<String> { regex.matchEntire(it) != null }
                .thenBy { it.length }
                .thenBy { it }
        )
        .take(5)
        .toList()
}

private fun compileQuery(query: String): Regex? {
    val q = query.trim()
    if (q.isEmpty()) return null
    runCatching { return Regex(q, RegexOption.IGNORE_CASE) }
    // Fall back to glob semantics when the raw query is not a valid regex (e.g. "*frag*").
    val globbed = buildString {
        for (c in q) when (c) {
            '*'  -> append(".*")
            '?'  -> append('.')
            in META_CHARS -> { append('\\'); append(c) }
            else -> append(c)
        }
    }
    return runCatching { Regex(globbed, RegexOption.IGNORE_CASE) }.getOrNull()
}
