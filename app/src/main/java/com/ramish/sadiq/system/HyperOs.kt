package com.ramish.sadiq.system

/**
 * What the ROM is, as far as Sadiq is allowed to know.
 *
 * Two questions matter. First, whether the OEM steps apply at all: they exist only on Xiaomi, Redmi
 * and POCO phones, so a Pixel must never be shown a page about Autostart. Second, which HyperOS
 * generation is running, because the menu names and some activity names changed between HyperOS 2
 * and 3 (§4.7).
 *
 * Everything is a pure function of strings the caller passes in, so the rules can be tested off
 * device with the two incremental versions the plan names. `Build` is read by
 * [PermissionStatusProviderImpl], not here.
 */
object HyperOs {

    /**
     * `^OS(\d+)\.(\d+)`: the major and the minor, anchored at the start.
     *
     * Anchoring matters — a build string that merely contains "OS" somewhere (a carrier tag, a
     * region code) must not be read as a HyperOS version.
     */
    private val VERSION_PATTERN: Regex = Regex("^OS(\\d+)\\.(\\d+)")

    /** The manufacturers that ship HyperOS or MIUI. Brand is checked too: a POCO reports Xiaomi. */
    private val XIAOMI_NAMES: Set<String> = setOf("xiaomi", "redmi", "poco")

    /**
     * Whether this is a Xiaomi-family ROM.
     *
     * Either field is enough. POCO phones report `Build.MANUFACTURER = "Xiaomi"` with
     * `Build.BRAND = "POCO"`, while some regional builds do the opposite; asking both avoids
     * hiding the OEM steps on the exact phone this app is built for.
     *
     * @param manufacturer `Build.MANUFACTURER`, or null when unknown.
     * @param brand `Build.BRAND`, or null when unknown.
     */
    fun isXiaomiFamily(manufacturer: String?, brand: String?): Boolean =
        isXiaomiName(manufacturer) || isXiaomiName(brand)

    /**
     * The HyperOS major version, or null when this is not a Xiaomi-family device or the version is
     * not parseable.
     *
     * `"OS2.0.104.0.VOJINXM"` is 2 and `"OS3.0.5.0.WOJINXM"` is 3. A Xiaomi phone still on MIUI
     * reports `"V14.0.6.0.TKHMIXM"`, which yields null: MIUI has no HyperOS major, and pretending
     * it had one would put the wrong menu names in front of the user.
     *
     * @param manufacturer `Build.MANUFACTURER`.
     * @param brand `Build.BRAND`.
     * @param incremental `Build.VERSION.INCREMENTAL`.
     */
    fun major(manufacturer: String?, brand: String?, incremental: String?): Int? {
        if (!isXiaomiFamily(manufacturer, brand)) return null
        val match = VERSION_PATTERN.find(incremental?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * Whether [incremental] is a HyperOS build string, regardless of the device it came from.
     *
     * Used by the Health check to print "HyperOS 3.0.5.0.WOJINXM" only when that is really what the
     * phone runs.
     */
    fun looksLikeHyperOs(incremental: String?): Boolean =
        VERSION_PATTERN.containsMatchIn(incremental?.trim().orEmpty())

    /**
     * The HyperOS version string as the user would see it in Settings, or null for MIUI and for
     * anything that is not HyperOS.
     */
    fun versionName(incremental: String?): String? {
        val trimmed = incremental?.trim().orEmpty()
        return trimmed.takeIf { looksLikeHyperOs(it) }
    }

    /** Case-insensitive membership, with a null-safe empty check. */
    private fun isXiaomiName(value: String?): Boolean =
        value?.trim()?.lowercase()?.let { it.isNotEmpty() && it in XIAOMI_NAMES } == true
}
