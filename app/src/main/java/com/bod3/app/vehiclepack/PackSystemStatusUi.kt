package com.bod3.app.vehiclepack

/** Feature actions the system-detail view may enable. An unproven link never offers one. */
enum class PackSystemAction { LIVE, FAULTS, TESTS, CODING, ECU_INFO }

/** Pure presentation/gating facts for one pinned vehicle system. No Android or Compose types. */
object PackSystemStatusUi {
    const val PROVEN_LINK_BADGE = "Link natively consistent, not vehicle-tested"
    const val DTC_ONLY_CLEAR_LINE = "Reads DTCs only; clearing is not available"
    const val DTC_ONLY_SESSION_LINE = "ECU may reject the read without a session; not vehicle-tested"
    private val CATEGORY_ORDER = listOf("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")

    fun linkBadge(linkStatus: String, linkReasons: List<String>): String = when (linkStatus) {
        "proven" -> PROVEN_LINK_BADGE
        else -> "Link not verified: " + if (linkReasons.isEmpty()) "no reason recorded" else linkReasons.joinToString(", ")
    }

    fun categoryLines(categoryStatus: Map<String, String>): List<String> =
        CATEGORY_ORDER.map { category -> "$category: ${categoryStatus[category] ?: "unknown"}" }

    fun isDtcOnly(categoryStatus: Map<String, String>): Boolean =
        categoryStatus["dtcs"] == "executable" && CATEGORY_ORDER.all { it == "dtcs" || categoryStatus[it] != "executable" }

    fun dtcOnlyLines(categoryStatus: Map<String, String>): List<String> =
        if (isDtcOnly(categoryStatus)) listOf(DTC_ONLY_CLEAR_LINE, DTC_ONLY_SESSION_LINE) else emptyList()

    /** Link-verified filter plus case-insensitive name search over the already-selected vehicle's
     *  pinned system descriptors (validator bound: 1,024 loaded with that vehicle). The 287,030-row
     *  source corpus is never loaded or indexed here; the source library keeps its paginated query path. */
    fun filter(systems: List<PackSystemChoice>, nameQuery: String, linkVerifiedOnly: Boolean): List<PackSystemChoice> {
        val needle = nameQuery.trim()
        return systems.filter { system ->
            (!linkVerifiedOnly || system.linkStatus == "proven") &&
                (needle.isEmpty() || system.name.contains(needle, ignoreCase = true))
        }
    }

    const val SYSTEM_PAGE_SIZE = 100

    /** Slice only after filtering the full selected-vehicle list. */
    fun page(systems: List<PackSystemChoice>, page: Int): List<PackSystemChoice> {
        val start = (page.coerceAtLeast(0).toLong() * SYSTEM_PAGE_SIZE).coerceAtMost(systems.size.toLong()).toInt()
        return systems.subList(start, (start + SYSTEM_PAGE_SIZE).coerceAtMost(systems.size))
    }

    /** Only categories with a source count or an executable definition are presented as tabs. */
    fun presentCategories(choice: PackSystemChoice): List<String> = CATEGORY_ORDER.filter { category ->
        val count = choice.counts[if (category == "actuators") "tests" else category]
        choice.categoryStatus[category] == "executable" || (count?.converted ?: 0) > 0 || (count?.unresolved ?: 0) > 0
    }

    /** Executable actions this pinned descriptor may ever offer. Link status is checked first: an
     *  unproven link yields an empty set even when a category claims executable, so the detail view
     *  can never enable an executable action for it. */
    fun executableActions(choice: PackSystemChoice?): Set<PackSystemAction> {
        if (choice == null || choice.linkStatus != "proven") return emptySet()
        return buildSet {
            if (choice.categoryStatus["pids"] == "executable") add(PackSystemAction.LIVE)
            if (choice.categoryStatus["dtcs"] == "executable") add(PackSystemAction.FAULTS)
            if (choice.categoryStatus["ecuInfo"] == "executable") add(PackSystemAction.ECU_INFO)
            if (choice.categoryStatus["actuators"] == "executable") add(PackSystemAction.TESTS)
            if (choice.categoryStatus["coding"] == "executable") add(PackSystemAction.CODING)
        }
    }
}
