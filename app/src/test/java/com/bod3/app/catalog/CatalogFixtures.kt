package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.ActuatorRecipe
import com.bod3.app.coding.CodingMethod
import com.bod3.app.coding.CodingOption
import com.bod3.app.coding.CodingRecipe
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import java.io.File

/** Synthetic identifiers below exist only in JVM tests, never in the names/years seed catalog. */
internal val engine = EcuDefinition(name = "Test engine", requestId = "7E0", responseId = "7E8")
internal val brakes = EcuDefinition(name = "Test brakes", requestId = "713", responseId = "77D")
internal fun pid(id: String = "test-rpm") = CustomPid(id, "Test RPM", "010C", "(A*256+B)/4", "rpm", max = 8_000.0)
internal fun actuator(id: String = "test-output") = ActuatorRecipe(id, "Test output", "Simulation only",
    module = ActuatorModule(name = "Test module", requestId = "70E", responseId = "778"), start = listOf(0x2F, 0x12, 0x34, 3, 1), stop = listOf(0x2F, 0x12, 0x34, 0),
    safetyNotes = listOf("Simulated test only"), verified = true)
internal fun coding(id: String = "test-coding") = CodingRecipe(id, "Test coding", module = ActuatorModule(name = "Test module", requestId = "70E", responseId = "778"),
    method = CodingMethod.DID_VALUE, did = 0x1234, byteIndex = 0, options = listOf(CodingOption(0, "Off"), CodingOption(1, "On")), verified = true)
internal fun security() = CatalogSecurity("test-security", "Test access", SecurityAccessSpec(1, SecurityAlgorithm.STATIC_KEY, listOf(0x12)), true)
internal val makeNode = CatalogNode("make", null, CatalogLevel.MAKE, "Toyota")
internal val modelNode = CatalogNode("model", "make", CatalogLevel.MODEL, "Innova")
internal val generationNode = CatalogNode("generation", "model", CatalogLevel.GENERATION, "AN40", 2004, 2015)
internal val variantNode = CatalogNode("variant", "generation", CatalogLevel.VARIANT, "Test variant", engine = "Test engine", fuel = "Diesel", transmission = "Manual")
internal fun fixture(vararg changes: CatalogNode): CatalogPack {
    val nodes = listOf(makeNode, modelNode, generationNode, variantNode).associateBy { it.id } + changes.associateBy { it.id }
    return CatalogPack("test", "Test catalog", nodes = nodes.values.toList())
}
internal class CatalogMemoryStore : KeyValueStore {
    val values = linkedMapOf<String, String>()
    val writes = mutableListOf<String>()
    var fail = false
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (fail) throw IllegalArgumentException("Simulated storage failure")
        writes += key
        if (value == null) values.remove(key) else values[key] = value
    }
}
internal fun asset(name: String): File = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
    .firstOrNull { it.isFile } ?: error("Missing test asset $name")
internal fun rejected(block: () -> Unit): String {
    try { block() } catch (e: IllegalArgumentException) { return e.message.orEmpty() }
    throw AssertionError("Expected a validation failure")
}
