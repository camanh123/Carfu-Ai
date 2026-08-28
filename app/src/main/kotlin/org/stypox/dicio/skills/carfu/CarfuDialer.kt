package org.stypox.dicio.skills.carfu

import android.content.Intent
import android.net.Uri

/**
 * FYT-first dialer. Never silently hands a call to Zalo.
 * Prefers `com.syu.bt` / `PhoneActivity`, then [Intent.ACTION_DIAL].
 */
object CarfuDialer {
    const val FYT_BT_PACKAGE = "com.syu.bt"
    const val FYT_PHONE_ACTIVITY = "com.syu.bt.PhoneActivity"
    const val ZALO_PACKAGE = "com.zing.zalo"

    fun openPhoneCandidates(): List<Intent> = listOf(
        Intent().apply {
            setClassName(FYT_BT_PACKAGE, FYT_PHONE_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
        Intent(Intent.ACTION_DIAL).apply {
            setPackage(FYT_BT_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
        Intent(Intent.ACTION_DIAL).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )

    fun dialCandidates(number: String): List<Intent> {
        val tel = Uri.parse("tel:$number")
        return listOf(
            Intent(Intent.ACTION_DIAL, tel).apply {
                setClassName(FYT_BT_PACKAGE, FYT_PHONE_ACTIVITY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Intent.ACTION_VIEW, tel).apply {
                setClassName(FYT_BT_PACKAGE, FYT_PHONE_ACTIVITY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Intent.ACTION_DIAL, tel).apply {
                setPackage(FYT_BT_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Intent.ACTION_DIAL, tel).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
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
