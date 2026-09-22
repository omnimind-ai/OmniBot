package cn.com.omnimind.baselib.util

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream

/** Reads the existing shared_preferences list encoding; this is not another settings store. */
object LegacyFlutterPreferences {
    private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"

    fun readStringList(preferences: SharedPreferences, key: String): List<String> = runCatching {
        when (val raw = preferences.all[key]) {
            is Set<*> -> raw.filterIsInstance<String>()
            is String -> when {
                raw.startsWith(JSON_LIST_PREFIX) -> {
                    val array = JSONArray(raw.removePrefix(JSON_LIST_PREFIX))
                    List(array.length()) { array.getString(it) }
                }
                raw.startsWith(LIST_PREFIX) -> ObjectInputStream(
                    ByteArrayInputStream(Base64.decode(raw.removePrefix(LIST_PREFIX), Base64.DEFAULT)),
                ).use { (it.readObject() as? List<*>)?.filterIsInstance<String>().orEmpty() }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }.getOrElse { emptyList() }

    fun encodeStringList(values: List<String>): String = JSON_LIST_PREFIX + JSONArray(values).toString()
}
