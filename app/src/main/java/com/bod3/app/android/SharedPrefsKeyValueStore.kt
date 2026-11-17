package com.bod3.app.android

import android.content.Context
import com.bod3.app.obd.connection.KeyValueStore

/** [KeyValueStore] backed by SharedPreferences, used for connection profiles and settings. */
class SharedPrefsKeyValueStore(context: Context, name: String = "bod3.settings") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
