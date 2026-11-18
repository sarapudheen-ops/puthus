package com.bod3.app.profile

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import org.junit.Assert.*
import org.junit.Test

class ProfileExtrasStoreTest {
    private val vin = "WVWZZZ1KZAW000001"
    private val key = "profile.extras.$vin"

    @Test fun manualDefinitionsAndMetadataRoundTripWithoutChangingLegacyOrCatalogLinks() {
        val store = InMemoryKeyValueStore()
        val extras = ProfileExtras(manualEcus = listOf(EcuDefinition(name = "Documented ECU", requestId = "7E0", responseId = "7E8", protocol = EcuProtocol.KWP)),
            moduleNames = mapOf("7E0" to "My ECU"), vehicleKey = "Legacy|Car", notes = "Keep note", catalogNodeId = "my-catalog-level")
        val repository = ProfileExtrasStore(store)
        repository.save(vin, extras)
        assertEquals(extras, repository.get(vin.lowercase()))
        val saved = store.get(key)
        assertEquals(extras, repository.get(vin))
        assertEquals(saved, store.get(key))
    }

    @Test fun malformedExtrasAreBackedUpAndCannotBeOverwrittenByEditingAnEmptyUiFallback() {
        for (raw in listOf("{broken", "null", "[]", "{\"notes\":42}", "{\"manualEcus\":[{\"name\":\"ECU\",\"requestId\":\"7E0\",\"responseId\":\"7E8\"},{\"name\":\"bad\"}]}")) {
            val store = InMemoryKeyValueStore()
            store.put(key, raw)
            val repository = ProfileExtrasStore(store)
            assertThrows(IllegalArgumentException::class.java) { repository.get(vin) }
            assertEquals(raw, store.get(key))
            assertEquals(raw, store.get("$key.unreadable"))
            assertThrows(IllegalArgumentException::class.java) { repository.update(vin) { ProfileExtras(notes = "New note") } }
            assertEquals(raw, store.get(key))
        }
    }

    @Test fun failedLinkMigrationLeavesTheOriginalManualEcusAndNotesUntouched() {
        val store = InMemoryKeyValueStore()
        val original = JsonWriter.write(ProfileExtrasCodec.encode(ProfileExtras(vehicleKey = "Legacy|Car", notes = "Original note")))
        store.put(key, original)
        val repository = ProfileExtrasStore(store) { throw IllegalArgumentException("Catalog needs recovery") }
        assertThrows(IllegalArgumentException::class.java) { repository.update(vin) { ProfileExtras() } }
        assertEquals(original, store.get(key))
    }

    @Test fun invalidManualDefinitionNeverReplacesAnOtherwiseHealthyProfile() {
        val store = InMemoryKeyValueStore()
        val repository = ProfileExtrasStore(store)
        repository.save(vin, ProfileExtras(notes = "Original"))
        val raw = store.get(key)
        assertThrows(IllegalArgumentException::class.java) { repository.update(vin) {
            it.copy(manualEcus = listOf(EcuDefinition(name = "bad", requestId = "FFF", responseId = "FFE")))
        } }
        assertEquals(raw, store.get(key))
    }
}
