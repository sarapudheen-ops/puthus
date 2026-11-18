package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorEntry
import com.bod3.app.coding.CodingEntry
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.DtcDescriptions
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.ModuleNameEntry

/** Views for existing diagnostics/controllers. Only manual selection supplies ECU/connection data. */
class CatalogAccess(private val catalog: CatalogRepository) {
    fun customPids(): List<CustomPid> = effective().pids

    /** Profile links and the legacy library NEVER choose probe targets. Enhanced KWP is not UDS. */
    fun scanEcus(manual: List<EcuDefinition> = emptyList()): List<EcuDefinition> =
        (manual + catalog.selected()?.data?.ecus.orEmpty()).filter { it.protocol != EcuProtocol.KWP }.distinctBy { it.key }

    /** Global legacy PIDs and explicit templates are not a fallback vehicle or adapter configuration. */
    fun effective(): CatalogData = catalog.pack().library.overlay(catalog.selected()?.data ?: CatalogData())
    fun local(): CatalogData = catalog.selected()?.node?.data ?: catalog.pack().library
    fun edit(transform: (CatalogData) -> CatalogData) {
        if (catalog.selected() == null) catalog.editLibrary(transform) else catalog.editSelected(transform)
    }

    fun actuators(): List<ActuatorEntry> {
        val selected = catalog.selected(); val user = catalog.state().user
        val local = if (selected == null) user.library else user.nodes.firstOrNull { it.id == selected.node.id }?.data ?: CatalogData()
        val localIds = local.actuators.map { it.id }.toSet()
        return effective().actuators.map { recipe -> ActuatorEntry(recipe,
            selected?.origins?.get("actuators:${recipe.id}") ?: "Unscoped legacy library", recipe.id in localIds) }
            .sortedWith(compareBy({ it.recipe.module.name.lowercase() }, { it.recipe.name.lowercase() }))
    }
    fun coding(): List<CodingEntry> {
        val selected = catalog.selected(); val user = catalog.state().user
        val local = if (selected == null) user.library else user.nodes.firstOrNull { it.id == selected.node.id }?.data ?: CatalogData()
        val localIds = local.coding.map { it.id }.toSet()
        return effective().coding.map { recipe -> CodingEntry(recipe,
            selected?.origins?.get("coding:${recipe.id}") ?: "Unscoped legacy library", recipe.id in localIds) }
            .sortedWith(compareBy({ it.recipe.module.name.lowercase() }, { it.recipe.name.lowercase() }))
    }

    fun moduleNames(scope: ResolvedCatalog? = catalog.selected()): List<Pair<ModuleNameEntry, String>> {
        val selected = scope
        return selected?.data?.moduleNames.orEmpty().map { it to (selected?.origins?.get("moduleNames:${CatalogData.moduleKey(it)}") ?: "Catalog") } +
            catalog.pack().library.moduleNames.map { it to "Legacy module names" }
    }

    /** Known profile/link evidence wins for passive text, but NEVER activates that make's configuration. */
    fun descriptions(observedMake: String? = null): DtcDescriptions {
        val selected = catalog.selected()
        val make = com.bod3.app.datapack.DtcReferenceContext.make(observedMake, selected?.make)?.let(CatalogIds::makeName)
        val tree = catalog.tree()
        val matchesSelection = selected != null && make != null &&
            CatalogIds.normalize(CatalogIds.makeName(selected.make)) == CatalogIds.normalize(make)
        // A stale manual selection must not lend Toyota reference meanings to a known unrelated VIN.
        // Looking up the observed make's PASSIVE text does not select its nodes, ECUs, recipes or settings.
        val reference = if (matchesSelection) selected!!.data else tree.children(null).firstOrNull {
            make != null && CatalogIds.normalize(CatalogIds.makeName(it.name)) == CatalogIds.normalize(make)
        }?.let { tree.resolve(it.id)?.data } ?: CatalogData()
        val library = catalog.pack().library
        val groups = (reference.dtcGroups + library.dtcGroups).distinctBy { it.id }
        val scoped = groups.filter { g -> make != null && g.makes.any { CatalogIds.normalize(CatalogIds.makeName(it)) == CatalogIds.normalize(make) } }
            .map { (it.source ?: it.id) to it.dtcs }
        val baseTexts = catalog.base.library.dtcs
        val userTexts = library.dtcs.filter { (code, text) -> baseTexts[code] != text }
        return DtcDescriptions(user = reference.dtcs + userTexts,
            packs = scoped + listOf("Bundled legacy DTC texts" to baseTexts.filterKeys { it !in library.exclude.dtcs }))
    }
}
