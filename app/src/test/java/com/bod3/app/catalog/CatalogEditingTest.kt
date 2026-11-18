package com.bod3.app.catalog

import com.bod3.app.profile.JsonWriter
import org.junit.Assert.*
import org.junit.Test

class CatalogEditingTest {
    @Test fun editingASectionKeepsOtherDefinitionsAndExclusions() {
        val original = CatalogData(ecus = listOf(engine), pids = listOf(pid()), dtcs = mapOf("P1234" to "Existing note"),
            exclude = CatalogExclusions(ecus = setOf(brakes.key), pids = setOf("old")))
        val edited = CatalogEditing.applySection(original, CatalogSection.PIDS, JsonWriter.write(mapOf(
            "pids" to emptyList<Any>(), "exclude" to mapOf("pids" to listOf("parent-pid")))))
        assertTrue(edited.pids.isEmpty())
        assertEquals(original.ecus, edited.ecus)
        assertEquals(original.dtcs, edited.dtcs)
        assertEquals(original.exclude.ecus, edited.exclude.ecus)
        assertEquals(setOf("parent-pid"), edited.exclude.pids)
    }

    @Test fun sectionEditsRejectTyposOrChangesOutsideTheirScope() {
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.CODING, "{\"coding\":[],\"ecus\":[]}") }
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.PIDS, "{\"pids\":[],\"exclude\":{\"ecus\":[]}}") }
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.PIDS, "{\"exclude\":{}}") }
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.CONNECTION, "{\"connection\":{},\"exclude\":{\"connection\":[]}}") }
    }

    @Test fun blankDefinitionsContainNoGuessedCommandsAndAreNotSaveable() {
        for (section in listOf(CatalogSection.ACTUATORS, CatalogSection.CODING, CatalogSection.SECURITY, CatalogSection.PIDS, CatalogSection.MODULE_NAMES)) {
            val blank = CatalogEditing.blank(section)
            assertEquals("", blank["id"] ?: blank["name"])
            if ("module" in blank) {
                assertEquals("", (blank["module"] as Map<*, *>)["requestId"])
                assertEquals("", (blank["module"] as Map<*, *>)["responseId"])
            }
            rejected { CatalogEditing.applySection(CatalogData(), section, JsonWriter.write(mapOf(section.key to listOf(blank)))) }
        }
    }

    @Test fun codingTemplateCanBeCompletedWithoutFixingItsSchema() {
        val template = CatalogEditing.blank(CatalogSection.CODING) + mapOf(
            "id" to "documented-test", "name" to "Fixture only", "did" to "1234",
            "module" to mapOf("name" to "Test module", "requestId" to "70E", "responseId" to "778"),
            "values" to listOf(mapOf("value" to 1, "label" to "Fixture option")), "verified" to true)
        val data = CatalogEditing.applySection(CatalogData(), CatalogSection.CODING, JsonWriter.write(mapOf("coding" to listOf(template))))
        val recipe = data.coding.single()
        assertEquals(0, recipe.byteIndex)
        assertEquals(1L, recipe.options.single().value)
        assertFalse(recipe.verified)
        assertTrue(recipe.notes!!.contains("Unverified"))
    }

    @Test fun anEditorSaveCannotWeakenReadOnlyOrWriteRecipeValidation() {
        val raw = CatalogCodec.dataMap(CatalogData(pids = listOf(pid().copy(request = "2701"))))
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.PIDS, JsonWriter.write(mapOf("pids" to raw["pids"]))) }
        val coding = CatalogCodec.dataMap(CatalogData(coding = listOf(coding().copy(did = 0xF190))))
        rejected { CatalogEditing.applySection(CatalogData(), CatalogSection.CODING, JsonWriter.write(mapOf("coding" to coding["coding"]))) }
    }
}
