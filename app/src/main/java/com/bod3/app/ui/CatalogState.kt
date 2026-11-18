package com.bod3.app.ui

import com.bod3.app.catalog.CatalogSection
import com.bod3.app.catalog.CatalogNode
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.catalog.CatalogTree
import com.bod3.app.catalog.ResolvedCatalog

/** Browsing and editing never change the active manual selection. */
data class CatalogState(
    val pack: CatalogPack = CatalogPack("catalog", "Manual vehicle catalog"),
    val selectedId: String? = null,
    val browseId: String? = null,
    val favorites: Set<String> = emptySet(),
    val recents: List<String> = emptyList(),
    val editingNode: CatalogNode? = null,
    val editingIsNew: Boolean = false,
    val editingData: CatalogDataTarget? = null,
    val message: String? = null,
    val errors: List<String> = emptyList(),
    /** A saved profile may remember a link, but activating it still requires a tap in the catalog. */
    val linkingProfile: Boolean = false,
    /** This explicit manual selection is backed by an on-demand pack, not global templates. */
    val downloadedSelection: Boolean = false,
) {
    val tree: CatalogTree get() = CatalogTree(pack)
    val selected: ResolvedCatalog? get() = tree.resolve(selectedId)
}

data class CatalogDataTarget(val nodeId: String?, val section: CatalogSection)
