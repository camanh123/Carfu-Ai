package org.stypox.dicio.aliasnormalizer

data class ExactProviderHit(
    val entity: ProviderEntity,
    val canonical: Boolean,
    val alias: String?,
)

class ExactProviderMatcher(
    private val catalog: ProviderEntityCatalog = ProviderEntityCatalog(),
) {
    fun match(candidate: String): ExactProviderHit? {
        val folded = TranscriptFolder.fold(candidate)
        if (folded.isEmpty()) return null
        for (entity in catalog.all()) {
            if (folded == entity.foldedCanonical || folded == entity.spacedCanonical) {
                return ExactProviderHit(entity, canonical = true, alias = null)
            }
            val observed = entity.provenAliases.firstOrNull { TranscriptFolder.fold(it) == folded }
            if (observed != null) {
                return ExactProviderHit(entity, canonical = false, alias = observed)
            }
        }
        return null
    }
}
