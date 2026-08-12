package cn.com.omnimind.baselib.util

import com.tencent.mmkv.MMKV

/**
 * MMKV 工具类
 * 提供按前缀批量操作的便捷方法
 */
object MMKVUtil {
    private const val TAG = "MMKVUtil"

    /**
     * 获取所有带指定前缀的 key
     * @param prefix 前缀
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return 匹配前缀的 key 列表
     */
    fun getKeysWithPrefix(prefix: String, mmkv: MMKV = MMKV.defaultMMKV()): List<String> {
        val allKeys = mmkv.allKeys()
        return if (allKeys != null) {
            allKeys.filter { it.startsWith(prefix) }
        } else {
            emptyList()
        }
    }

    /**
     * 批量删除带指定前缀的 key
     * @param prefix 前缀
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return 删除的 key 数量
     */
    fun removeKeysWithPrefix(prefix: String, mmkv: MMKV = MMKV.defaultMMKV()): Int {
        val keysToRemove = getKeysWithPrefix(prefix, mmkv)
        if (keysToRemove.isEmpty()) {
            return 0
        }
        
        // 使用批量删除方法（如果可用）
        return try {
            // MMKV 提供了 removeValuesForKeys 方法用于批量删除
            mmkv.removeValuesForKeys(keysToRemove.toTypedArray())
            OmniLog.d(TAG, "Removed ${keysToRemove.size} stored values")
            keysToRemove.size
        } catch (e: Exception) {
            // 如果批量删除失败，逐个删除
            OmniLog.w(TAG, "Bulk removal failed; using fallback type=${e.javaClass.simpleName}")
            var count = 0
            keysToRemove.forEach { key ->
                mmkv.removeValueForKey(key)
            }
            count
        }
    }



    /**
     * 批量设置带指定前缀的 key-value
     * @param prefix 前缀
     * @param values Map<key, value>，key 会自动添加前缀
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return 成功设置的数量
     */
    fun setValuesWithPrefix(
        prefix: String,
        values: Map<String, String>,
        mmkv: MMKV = MMKV.defaultMMKV()
    ): Int {
        var count = 0
        values.forEach { (key, value) ->
            val fullKey = "$prefix$key"
            if (mmkv.encode(fullKey, value)) {
                count++
            }
        }
        OmniLog.d(TAG, "Stored $count/${values.size} values")
        return count
    }

    /**
     * 批量设置带指定前缀的 key-value（支持多种类型）
     * @param prefix 前缀
     * @param stringValues Map<String, String>
     * @param intValues Map<String, Int>
     * @param boolValues Map<String, Boolean>
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return 成功设置的总数量
     */
    fun setValuesWithPrefix(
        prefix: String,
        stringValues: Map<String, String> = emptyMap(),
        intValues: Map<String, Int> = emptyMap(),
        boolValues: Map<String, Boolean> = emptyMap(),
        mmkv: MMKV = MMKV.defaultMMKV()
    ): Int {
        var count = 0
        
        // 批量设置 String 值
        stringValues.forEach { (key, value) ->
            val fullKey = "$prefix$key"
            if (mmkv.encode(fullKey, value)) {
                count++
            }
        }
        
        // 批量设置 Int 值
        intValues.forEach { (key, value) ->
            val fullKey = "$prefix$key"
            if (mmkv.encode(fullKey, value)) {
                count++
            }
        }
        
        // 批量设置 Boolean 值
        boolValues.forEach { (key, value) ->
            val fullKey = "$prefix$key"
            if (mmkv.encode(fullKey, value)) {
                count++
            }
        }
        
        OmniLog.d(TAG, "Stored $count values")
        return count
    }

    /**
     * 检查是否存在带指定前缀的 key
     * @param prefix 前缀
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return 是否存在
     */
    fun containsKeysWithPrefix(prefix: String, mmkv: MMKV = MMKV.defaultMMKV()): Boolean {
        return getKeysWithPrefix(prefix, mmkv).isNotEmpty()
    }

    /**
     * 获取带指定前缀的 key 数量
     * @param prefix 前缀
     * @param mmkv MMKV 实例，默认为 defaultMMKV()
     * @return key 数量
     */
    fun getKeyCountWithPrefix(prefix: String, mmkv: MMKV = MMKV.defaultMMKV()): Int {
        return getKeysWithPrefix(prefix, mmkv).size
    }
}
