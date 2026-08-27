# Project-specific R8 rules.
#
# Android framework components, JNI methods, WebView JavaScript interfaces,
# Parcelable implementations, and common Android libraries are covered by the
# optimized Android defaults or by dependency consumer rules. Keep this file
# limited to reflection that is owned by this application.

# Runtime metadata used by Gson, Kotlin, and reflective generic type lookup.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod

# FlutterEngineConnectionRegistry keys plugins by their concrete runtime class.
# Keep each FlutterPlugin implementation as a distinct class so R8 cannot merge
# unrelated plugins and make later registrations look like duplicates.
-keep,allowobfuscation,allowshrinking class * implements io.flutter.embedding.engine.plugins.FlutterPlugin {
    <init>(...);
}

# OkHttpManager resolves this value by its binary class and field names.
-keep class cn.com.omnimind.bot.BuildConfig {
    public static final java.lang.String BASE_URL;
}

# Gson persists these Kotlin models. Keep only their instance field names as a
# compatibility safety net; classes, constructors, methods, and service/store
# fields remain eligible for shrinking and optimization. Persisted baselib
# models also declare stable @SerializedName values.
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.runtime.AcpAgentProfile {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.runtime.AcpAgentHealth {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.BuiltinSkillManifest {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.BuiltinSkillAsset {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.SkillRegistryEntry {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.AgentAlarmToolService$AlarmSoundSettings {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.AgentAlarmToolService$ExactAlarmRecordRaw {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.AgentAlarmToolService$ExactAlarmRecord {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler$StoredTask {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.agent.MemoryIndexEntry {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.mcp.RemoteMcpServerConfig {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.quicklog.QuickLogRecord {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.bot.quicklog.QuickLogWidgetSettings {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.config.ModelSceneConfigCache$CacheData {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.config.ModelSceneConfigCache$Metadata {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.llm.AiRequestLogEntry {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.llm.ModelProviderConfigStore$StoredModelProviderProfile {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.llm.SceneModelBindingEntry {
    <fields>;
}
-keepclassmembers,allowoptimization class cn.com.omnimind.baselib.util.RuntimeLogEntry {
    <fields>;
}

# Some persisted enums do not yet declare @SerializedName values.
-keepclassmembers,allowoptimization enum cn.com.omnimind.** {
    <fields>;
}

# Logback is an optional JVM logging backend and is not packaged on Android.
-dontwarn ch.qos.logback.**

# Ktor's IntelliJ debugger detector probes these JVM-only management APIs.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
