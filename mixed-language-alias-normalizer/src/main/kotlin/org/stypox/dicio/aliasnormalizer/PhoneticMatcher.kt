package org.stypox.dicio.aliasnormalizer

class PhoneticMatcher {
    fun score(candidate: String, entity: ProviderEntity): Double {
        val candTokens = tokens(candidate)
        val canonTokens = tokens(splitCamelName(entity.canonicalName))
        val compact = StringSimilarity.normalized(
            PhoneticNormalizer.encodeCompact(candidate),
            PhoneticNormalizer.encodeCompact(entity.canonicalName + " " + splitCamelName(entity.canonicalName)),
        )
        val againstSpaced = StringSimilarity.normalized(
            PhoneticNormalizer.encode(candidate),
            PhoneticNormalizer.encode(splitCamelName(entity.canonicalName)),
        )
        val againstCompactName = StringSimilarity.normalized(
            PhoneticNormalizer.encodeCompact(candidate),
            PhoneticNormalizer.encodeCompact(splitCamelName(entity.canonicalName)),
        )
        val aligned = alignedTokenScore(candTokens, canonTokens)
        val aliasBest = entity.provenAliases.maxOfOrNull { alias ->
            StringSimilarity.normalized(
                PhoneticNormalizer.encodeCompact(candidate),
                PhoneticNormalizer.encodeCompact(alias),
            )
        } ?: 0.0
        return maxOf(compact, againstSpaced, againstCompactName, aligned, aliasBest * 0.92)
    }

    private fun alignedTokenScore(candidate: List<String>, canonical: List<String>): Double {
        if (canonical.isEmpty() || candidate.isEmpty()) return 0.0
        if (candidate.size == 1 && canonical.size > 1) {
            return StringSimilarity.normalized(
                PhoneticNormalizer.encodeCompact(candidate.joinToString(" ")),
                PhoneticNormalizer.encodeCompact(canonical.joinToString(" ")),
            )
        }
        if (candidate.size != canonical.size) {
            return StringSimilarity.normalized(
                PhoneticNormalizer.encode(candidate.joinToString(" ")),
                PhoneticNormalizer.encode(canonical.joinToString(" ")),
            ) * 0.9
        }
        var sum = 0.0
        for (i in candidate.indices) {
            val a = candidate[i]
            val b = canonical[i]
            val direct = StringSimilarity.normalized(
                PhoneticNormalizer.encodeToken(a),
                PhoneticNormalizer.encodeToken(b),
            )
            val dropS = StringSimilarity.normalized(
                PhoneticNormalizer.encodeTokenDropLeadingS(a),
                PhoneticNormalizer.encodeToken(b),
            )
            val dropSRev = StringSimilarity.normalized(
                PhoneticNormalizer.encodeToken(a),
                PhoneticNormalizer.encodeTokenDropLeadingS(b),
            )
            sum += maxOf(direct, dropS, dropSRev)
        }
        return sum / candidate.size
    }

    private fun tokens(text: String): List<String> =
        TranscriptFolder.fold(text).split(' ').filter { it.isNotEmpty() }
}
