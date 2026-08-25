package org.stypox.dicio.probe

/**
 * Packages commonly found on UIS7862 / FYT (syu) head units, plus the media/maps
 * apps used on CARFU devices. Used by the Phase 0 package inspector.
 */
object FytPackages {
    val bluetoothDialer = listOf(
        "com.syu.bt",
        "com.bt.chip",
        "com.android.ecar",
    )

    val mediaRadio = listOf(
        "com.syu.radio",
        "com.syu.music",
        "com.zing.mp3",
        "com.google.android.youtube",
    )

    val mapsCamera = listOf(
        "com.google.android.apps.maps",
        "com.syu.dvr",
        "com.syu.camera",
    )

    val allWatched: List<Pair<String, List<String>>> = listOf(
        "Bluetooth/Dialer" to bluetoothDialer,
        "Media/Radio" to mediaRadio,
        "Maps/Camera" to mapsCamera,
    )

    val allWatchedPackages: Set<String> =
        (bluetoothDialer + mediaRadio + mapsCamera).toSet()
}
