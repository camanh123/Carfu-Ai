package org.stypox.dicio.skills.carfu

/**
 * FYT-first dialer. Never silently hands a call to Zalo.
 * Prefers `com.syu.bt` / `PhoneActivity`, then ACTION_DIAL.
 */
object CarfuDialer {
    const val FYT_BT_PACKAGE = "com.syu.bt"
    const val FYT_PHONE_ACTIVITY = "com.syu.bt.PhoneActivity"
    const val ZALO_PACKAGE = "com.zing.zalo"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"

    fun openPhoneCandidates(): List<CarfuLaunchSpec> = listOf(
        CarfuLaunchSpec(packageName = FYT_BT_PACKAGE, className = FYT_PHONE_ACTIVITY),
        CarfuLaunchSpec(action = ACTION_DIAL, packageName = FYT_BT_PACKAGE),
        CarfuLaunchSpec(action = ACTION_DIAL),
    )

    fun dialCandidates(number: String): List<CarfuLaunchSpec> {
        val tel = "tel:$number"
        return listOf(
            CarfuLaunchSpec(
                action = ACTION_DIAL,
                packageName = FYT_BT_PACKAGE,
                className = FYT_PHONE_ACTIVITY,
                data = tel,
            ),
            CarfuLaunchSpec(
                action = ACTION_VIEW,
                packageName = FYT_BT_PACKAGE,
                className = FYT_PHONE_ACTIVITY,
                data = tel,
            ),
            CarfuLaunchSpec(action = ACTION_DIAL, packageName = FYT_BT_PACKAGE, data = tel),
            CarfuLaunchSpec(action = ACTION_DIAL, data = tel),
        )
    }

    fun isBlockedPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName == ZALO_PACKAGE || packageName.startsWith("$ZALO_PACKAGE.")
    }
}

data class ContactResolve(
    val unique: CarfuContact? = null,
    val ambiguous: List<CarfuContact> = emptyList(),
    val none: Boolean = false,
)

object CarfuTelephoneLookup {
    fun resolve(foldedQuery: String, contacts: List<CarfuContact>): ContactResolve {
        val q = foldedQuery.trim()
        if (q.isEmpty() || contacts.isEmpty()) {
            return ContactResolve(none = true)
        }
        val exact = contacts.filter { foldName(it.name) == q && it.numbers.isNotEmpty() }
        if (exact.size == 1) return ContactResolve(unique = exact[0])
        if (exact.size > 1) return ContactResolve(ambiguous = exact)
        val contains = contacts.filter { contact ->
            contact.numbers.isNotEmpty() && namesOverlap(foldName(contact.name), q)
        }
        if (contains.size == 1) return ContactResolve(unique = contains[0])
        if (contains.size > 1) return ContactResolve(ambiguous = contains.take(5))
        return ContactResolve(none = true)
    }

    private fun namesOverlap(name: String, query: String): Boolean {
        if (name.isEmpty() || query.isEmpty()) return false
        return name == query || name.contains(query) || query.contains(name)
    }

    private fun foldName(name: String): String {
        return org.stypox.dicio.io.session.VietnameseTranscript.foldForMatch(name)
    }
}
