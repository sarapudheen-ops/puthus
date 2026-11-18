package com.bod3.app.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    @Test fun searchFindsEveryHierarchyLevelCaseInsensitively() {
        val tree = CatalogTree(fixture())
        assertTrue(tree.search("toyota").any { it.level == CatalogLevel.MAKE })
        assertTrue(tree.search("INNOVA").any { it.level == CatalogLevel.MODEL })
        assertTrue(tree.search("AN40").any { it.level == CatalogLevel.GENERATION })
        assertEquals("variant", tree.search("Toyota Innova AN40 diesel manual").single().id)
    }
    @Test fun searchMatchesYearsInsideRangesAndRejectsYearsOutsideThem() {
        val tree = CatalogTree(fixture())
        assertEquals(setOf("generation", "variant"), tree.search("Innova 2010").map { it.id }.toSet())
        assertTrue(tree.search("Innova 2004").isNotEmpty())
        assertTrue(tree.search("Innova 2015").isNotEmpty())
        assertTrue(tree.search("Innova 2016").isEmpty())
    }
    @Test fun searchNormalizesAccentsWhitespaceAndManufacturerAliases() {
        val tree = CatalogTree(CatalogCodec.decodeOrThrow(asset("catalog/starter.json").readText()))
        assertTrue(tree.search("  skoda   octavia ").isNotEmpty())
        assertTrue(tree.search("citroen c3").isNotEmpty())
        assertTrue(tree.search("VW Golf").isNotEmpty())
    }
    @Test fun searchIncludesVariantEngineFuelAndTransmissionIndependently() {
        val tree = CatalogTree(fixture(variantNode.copy(engine = "Test 2.4", fuel = "Petrol", transmission = "Automatic")))
        assertEquals("variant", tree.search("2.4 Petrol").single().id)
        assertEquals("variant", tree.search("automatic").single().id)
        assertTrue(tree.search("diesel").isEmpty())
    }
    @Test fun blankSearchShowsOnlyMakesAndSearchingNeverChangesSelection() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        assertEquals(listOf(makeNode), repo.tree().search(""))
        assertEquals(4, repo.tree().search("Toyota").size)
        assertNull(repo.selected())
    }
    @Test fun removingAndRenamingAreReflectedWithoutAnOutdatedSearchIndex() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        repo.saveNode(modelNode.copy(name = "My manual model"))
        assertTrue(repo.tree().search("Innova").isEmpty())
        assertTrue(repo.tree().search("manual model").isNotEmpty())
        repo.deleteNode("model")
        assertTrue(repo.tree().search("manual model").isEmpty())
    }
}
