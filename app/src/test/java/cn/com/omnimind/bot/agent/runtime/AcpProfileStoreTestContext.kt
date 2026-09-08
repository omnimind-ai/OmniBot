package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/** Real catalog and profile serialization, with only Android IO substituted.
 * Each preferences read opens the temporary file again; a new Context/store
 * cannot pass by retaining the previous profile objects in memory.
 */
internal fun acpProfileStoreTestContext(directory: File): Context {
    val context = mock(Context::class.java)
    val assets = mock(AssetManager::class.java)
    `when`(assets.open(anyString())).thenAnswer { call ->
        File("src/main/assets", call.getArgument<String>(0)).inputStream()
    }
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.assets).thenReturn(assets)
    `when`(context.filesDir).thenReturn(directory)
    `when`(context.applicationInfo).thenReturn(ApplicationInfo().apply {
        dataDir = directory.absolutePath
    })
    `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer { call ->
        filePreferences(File(directory, "${call.getArgument<String>(0)}.json"))
    }
    return context
}

private fun filePreferences(file: File): SharedPreferences {
    val gson = Gson()
    fun read(): MutableMap<String, String> = if (file.exists()) {
        gson.fromJson(file.readText(), object : TypeToken<MutableMap<String, String>>() {}.type)
    } else {
        linkedMapOf()
    }
    fun editor(): SharedPreferences.Editor {
        val changes = linkedMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = mock(SharedPreferences.Editor::class.java) { call ->
            when (call.method.name) {
                "putString" -> {
                    changes[call.getArgument(0)] = call.getArgument(1)
                    editor
                }
                "remove" -> {
                    changes[call.getArgument(0)] = null
                    editor
                }
                "apply", "commit" -> {
                    val values = read()
                    changes.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    file.writeText(gson.toJson(values))
                    if (call.method.name == "commit") true else null
                }
                else -> error("Unexpected preferences editor call: ${call.method.name}")
            }
        }
        return editor
    }
    return mock(SharedPreferences::class.java) { call ->
        when (call.method.name) {
            "getString" -> read()[call.getArgument<String>(0)] ?: call.getArgument<String?>(1)
            "edit" -> editor()
            else -> error("Unexpected preferences call: ${call.method.name}")
        }
    }
}
