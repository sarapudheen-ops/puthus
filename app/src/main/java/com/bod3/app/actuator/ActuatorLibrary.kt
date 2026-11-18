package com.bod3.app.actuator

import com.bod3.app.obd.connection.KeyValueStore

/** A recipe with where it came from. */
data class ActuatorEntry(val recipe: ActuatorRecipe, val packName: String, val isUser: Boolean)

/** Summary of an import. */
data class ActuatorImportResult(val added: List<String>, val replaced: List<String>)

/**
 * Bundled actuator packs plus the user's own recipes (stored in the key-value store). A user recipe
 * with the same id as a bundled one replaces it.
 */
class ActuatorLibrary(private val bundled: List<ActuatorPack>, private val store: KeyValueStore) {

    val bundledPacks: List<ActuatorPack> get() = bundled

    fun userPack(): ActuatorPack {
        val json = store.get(KEY_USER) ?: return emptyUserPack()
        return ActuatorRecipeCodec.decode(json).pack ?: emptyUserPack().also { store.put(KEY_USER_BROKEN, json) }
    }

    fun entries(): List<ActuatorEntry> {
        val user = userPack().recipes
        val userIds = user.map { it.id }.toSet()
        val fromBundles = bundled.flatMap { pack -> pack.recipes.filterNot { it.id in userIds }.map { ActuatorEntry(it, pack.name, false) } }
            .distinctBy { it.recipe.id }
        return (fromBundles + user.map { ActuatorEntry(it, MY_RECIPES, true) })
            .sortedWith(compareBy({ it.recipe.module.name.lowercase() }, { it.recipe.name.lowercase() }))
    }

    fun entry(id: String): ActuatorEntry? = entries().firstOrNull { it.recipe.id == id }

    /** Adds every recipe of a validated pack to the user's recipes, replacing those with the same id. */
    fun import(pack: ActuatorPack): ActuatorImportResult {
        val problems = pack.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val current = userPack().recipes
        val existingIds = (current.map { it.id } + bundled.flatMap { p -> p.recipes.map { it.id } }).toSet()
        val incomingIds = pack.recipes.map { it.id }.toSet()
        save(current.filterNot { it.id in incomingIds } + pack.recipes)
        return ActuatorImportResult(
            added = pack.recipes.map { it.id }.filterNot { it in existingIds },
            replaced = pack.recipes.map { it.id }.filter { it in existingIds },
        )
    }

    fun importJson(json: String): ActuatorImportResult = import(ActuatorRecipeCodec.decodeOrThrow(json))

    fun deleteUserRecipe(id: String) = save(userPack().recipes.filterNot { it.id == id })

    /** A pack file with the given recipes (all user recipes by default), ready to share. */
    fun export(recipes: List<ActuatorRecipe> = userPack().recipes, name: String = MY_RECIPES): String =
        ActuatorRecipeCodec.encode(ActuatorPack(ActuatorPack.USER_PACK_ID, name, recipes = recipes))

    private fun save(recipes: List<ActuatorRecipe>) {
        if (recipes.isEmpty()) {
            store.put(KEY_USER, null)
            return
        }
        val pack = emptyUserPack().copy(recipes = recipes)
        val problems = pack.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        store.put(KEY_USER, ActuatorRecipeCodec.encode(pack))
    }

    private fun emptyUserPack() = ActuatorPack(ActuatorPack.USER_PACK_ID, MY_RECIPES, recipes = emptyList())

    companion object {
        const val KEY_USER = "actuators.user"
        const val KEY_USER_BROKEN = "actuators.user.unreadable"
        const val MY_RECIPES = "My recipes"
    }
}
