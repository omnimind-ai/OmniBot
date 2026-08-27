package cn.com.omnimind.baselib.llm

import com.tencent.mmkv.MMKV

enum class LocalInferenceMode {
    AUTOMATIC,
    ONLINE,
    OFFLINE;

    companion object {
        private const val KEY = "local_model_inference_mode_v1"

        fun get(): LocalInferenceMode {
            return when (MMKV.defaultMMKV()?.decodeString(KEY, "automatic")?.lowercase()) {
                "online" -> ONLINE
                "offline" -> OFFLINE
                else -> AUTOMATIC
            }
        }

        fun set(mode: LocalInferenceMode) {
            MMKV.defaultMMKV()?.encode(KEY, mode.name.lowercase())
        }
    }
}
