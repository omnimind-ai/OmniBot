package com.rk.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.rk.libcommons.application
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.ui.screens.settings.InputMode

object Settings {
    //Boolean
    var seccomp
        get() = Preference.getBoolean(key = "seccomp", default = false)
        set(value) = Preference.setBoolean(key = "seccomp",value)
    var amoled
        get() = Preference.getBoolean(key = "oled", default = false)
        set(value) = Preference.setBoolean(key = "oled",value)
    var monet
        get() = Preference.getBoolean(
            key = "monet",
            // Dynamic color is off by default to match the fixed brand
            // palette of the main OmnibotApp.
            default = false
        )
        set(value) = Preference.setBoolean(key = "monet",value)
    var ignore_storage_permission
        get() = Preference.getBoolean(key = "ignore_storage_permission",default = false)
        set(value) = Preference.setBoolean(key = "ignore_storage_permission",value)
    var github
        get() = Preference.getBoolean(key = "github", default = true)
        set(value) = Preference.setBoolean(key = "github",value)


   var default_night_mode
        get() = Preference.getInt(key = "default_night_mode", default = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = Preference.setInt(key = "default_night_mode",value)

    var terminal_font_size
        get() = Preference.getInt(key = "terminal_font_size", default = 13)
        set(value) = Preference.setInt(key = "terminal_font_size",value)

    var wallTransparency
        get() = Preference.getFloat(key = "wallTransparency", default = 0f)
        set(value) = Preference.setFloat(key = "wallTransparency",value)

    var working_Mode
        get() = Preference.getInt(key = "workingMode", default = WorkingMode.ALPINE)
        set(value) = Preference.setInt(key = "workingMode",value)

    var terminal_distribution
        get() = when (
            Preference.getInt(
                key = "terminal_distribution",
                default = WorkingMode.ALPINE
            )
        ) {
            WorkingMode.UBUNTU -> WorkingMode.UBUNTU
            else -> WorkingMode.ALPINE
        }
        set(value) = Preference.setInt(
            key = "terminal_distribution",
            value = if (value == WorkingMode.UBUNTU) WorkingMode.UBUNTU else WorkingMode.ALPINE
        )

    var input_mode
        get() = Preference.getInt(key = "input_mode", default = InputMode.DEFAULT)
        set(value) = Preference.setInt(key = "input_mode", value)

    var alpine_package_mirror
        get() = Preference.getInt(
            key = "alpine_package_mirror",
            default = AlpinePackageMirror.TSINGHUA
        )
        set(value) = Preference.setInt(key = "alpine_package_mirror", value)

    var ubuntu_package_mirror
        get() = Preference.getInt(
            key = "ubuntu_package_mirror",
            default = UbuntuPackageMirror.TSINGHUA
        )
        set(value) = Preference.setInt(key = "ubuntu_package_mirror", value)

    var custom_background_name
        get() = Preference.getString(key = "custom_bg_name", default = "No Image Selected")
        set(value) = Preference.setString(key = "custom_bg_name",value)
    var custom_font_name
        get() = Preference.getString(key = "custom_ttf_name", default = "No Font Selected")
        set(value) = Preference.setString(key = "custom_ttf_name",value)

    var blackTextColor
        get() = Preference.getBoolean(key = "blackText", default = false)
        set(value) = Preference.setBoolean(key = "blackText",value)

    var bell
        get() = Preference.getBoolean(key = "bell", default = false)
        set(value) = Preference.setBoolean(key = "bell",value)

    var vibrate
        get() = Preference.getBoolean(key = "vibrate", default = true)
        set(value) = Preference.setBoolean(key = "vibrate",value)

    var toolbar
        get() = Preference.getBoolean(key = "toolbar", default = true)
        set(value) = Preference.setBoolean(key = "toolbar",value)

    var statusBar
        get() = Preference.getBoolean(key = "statusBar", default = true)
        set(value) = Preference.setBoolean(key = "statusBar",value)

    var horizontal_statusBar
        get() = Preference.getBoolean(key = "horizontal_statusBar", default = true)
        set(value) = Preference.setBoolean(key = "horizontal_statusBar",value)

    var toolbar_in_horizontal
        get() = Preference.getBoolean(key = "toolbar_h", default = true)
        set(value) = Preference.setBoolean(key = "toolbar_h",value)

    var virtualKeys
        get() = Preference.getBoolean(key = "virtualKeys", default = true)
        set(value) = Preference.setBoolean(key = "virtualKeys",value)

    var hide_soft_keyboard_if_hwd
        get() = Preference.getBoolean(key = "force_soft_keyboard", default = true)
        set(value) = Preference.setBoolean(key = "force_soft_keyboard",value)

    var shortcuts_enabled
        get() = Preference.getBoolean(key = "shortcuts_enabled", default = true)
        set(value) = Preference.setBoolean(key = "shortcuts_enabled", value)

    fun getShortcutBinding(action: com.rk.terminal.ui.screens.terminal.ShortcutAction): com.rk.terminal.ui.screens.terminal.ShortcutBinding {
        val raw = Preference.getString(key = action.prefKey, default = action.default.serialize())
        return com.rk.terminal.ui.screens.terminal.ShortcutBinding.deserialize(raw)
    }

    fun setShortcutBinding(action: com.rk.terminal.ui.screens.terminal.ShortcutAction, binding: com.rk.terminal.ui.screens.terminal.ShortcutBinding) {
        Preference.setString(key = action.prefKey, value = binding.serialize())
    }



}

object AlpinePackageMirror {
    const val OFFICIAL = 0
    const val TSINGHUA = 1
}

object UbuntuPackageMirror {
    const val OFFICIAL = 0
    const val TSINGHUA = 1
}

object Preference {
    private var sharedPreferences: SharedPreferences = application!!.getSharedPreferences("Settings", Context.MODE_PRIVATE)

    //store the result into memory for faster access
    private val stringCache = hashMapOf<String, String?>()
    private val boolCache = hashMapOf<String, Boolean>()
    private val intCache = hashMapOf<String, Int>()
    private val longCache = hashMapOf<String, Long>()
    private val floatCache = hashMapOf<String, Float>()

    @SuppressLint("ApplySharedPref")
    fun clearData(){
        sharedPreferences.edit().clear().commit()
    }

    fun removeKey(key: String){
        if (sharedPreferences.contains(key).not()){
            return
        }

        sharedPreferences.edit().remove(key).apply()

        if (stringCache.containsKey(key)){
            stringCache.remove(key)
            return
        }

        if (boolCache.containsKey(key)){
            boolCache.remove(key)
            return
        }

        if (intCache.containsKey(key)){
            intCache.remove(key)
            return
        }

        if (longCache.containsKey(key)){
            longCache.remove(key)
            return
        }

        if (floatCache.containsKey(key)){
            floatCache.remove(key)
            return
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        runCatching {
            return boolCache[key] ?: sharedPreferences.getBoolean(key, default)
                .also { boolCache[key] = it }
        }.onFailure {
            it.printStackTrace()
            setBoolean(key, default)
        }
        return default
    }

    fun setBoolean(key: String, value: Boolean) {
        boolCache[key] = value
        runCatching {
            val editor = sharedPreferences.edit()
            editor.putBoolean(key, value)
            editor.apply()
        }.onFailure { it.printStackTrace() }
    }



    fun getString(key: String, default: String): String {
        runCatching {
            return stringCache[key] ?: sharedPreferences.getString(key, default)!!
                .also { stringCache[key] = it }
        }.onFailure {
            it.printStackTrace()
            setString(key, default)
        }
        return default
    }
    fun setString(key: String, value: String?) {
        stringCache[key] = value
        runCatching {
            val editor = sharedPreferences.edit()
            editor.putString(key, value)
            editor.apply()
        }.onFailure {
            it.printStackTrace()
        }

    }

    fun getInt(key: String, default: Int): Int {
        runCatching {
            return intCache[key] ?: sharedPreferences.getInt(key, default)
                .also { intCache[key] = it }
        }.onFailure {
            it.printStackTrace()
            setInt(key, default)
        }
        return default
    }

    fun setInt(key: String, value: Int) {
        intCache[key] = value
        runCatching {
            val editor = sharedPreferences.edit()
            editor.putInt(key, value)
            editor.apply()
        }.onFailure {
            it.printStackTrace()
        }

    }

    fun getLong(key: String, default: Long): Long {
        runCatching {
            return longCache[key] ?: sharedPreferences.getLong(key, default)
                .also { longCache[key] = it }
        }.onFailure {
            it.printStackTrace()
            setLong(key, default)
        }
        return default
    }

    fun setLong(key: String, value: Long) {
        longCache[key] = value
        runCatching {
            val editor = sharedPreferences.edit()
            editor.putLong(key,value)
            editor.apply()
        }.onFailure {
            it.printStackTrace()
        }
    }

    fun getFloat(key: String, default: Float): Float {
        runCatching {
            return floatCache[key] ?: sharedPreferences.getFloat(key, default)
                .also { floatCache[key] = it }
        }.onFailure {
            it.printStackTrace()
            setFloat(key, default)
        }
        return default
    }

    fun setFloat(key: String, value: Float) {
        floatCache[key] = value
        runCatching {
            val editor = sharedPreferences.edit()
            editor.putFloat(key,value)
            editor.apply()
        }.onFailure {
            it.printStackTrace()
        }
    }

}
