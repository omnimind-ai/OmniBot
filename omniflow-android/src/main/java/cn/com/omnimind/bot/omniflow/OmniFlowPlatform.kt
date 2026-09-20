package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import java.io.File

data class OmniFlowSkillLocation(
    val androidRoot: File,
    val shellRoot: String,
    val source: String,
)

interface OmniFlowPlatform {
    suspend fun startProcess(
        context: Context,
        command: String,
        environment: Map<String, String>,
    ): Process

    suspend fun prepareEnvironment(context: Context, command: String)

    suspend fun resolveRuntimeSkill(
        context: Context,
        refresh: Boolean,
    ): OmniFlowSkillLocation

    suspend fun resolvePackagedRuntimeSkill(context: Context): OmniFlowSkillLocation =
        resolveRuntimeSkill(context, refresh = false)

    fun allowsPackagedRuntimeFallback(): Boolean = true

    suspend fun bootstrapRuntimeSkill(
        context: Context,
        location: OmniFlowSkillLocation,
    ): OmniFlowSkillLocation

    suspend fun reclaimRuntimeSkill(context: Context)

    suspend fun completeJson(request: ChatCompletionRequest): String
}
