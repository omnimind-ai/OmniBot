package cn.com.omnimind.releaseaudit;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Independent fixtures against the installed APK, with no target/library dependencies. */
public final class ReleaseArtifactInstrumentation extends Instrumentation {
    private static final String TARGET = "cn.com.omnimind.bot";
    private static final String AGENT = TARGET + ".agent.";
    private final JSONArray cases = new JSONArray();
    private ClassLoader targetLoader;
    private Object gson;
    private int failed;
    private Application replacement;

    @Override public Application newApplication(ClassLoader cl, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        replacement = super.newApplication(cl, Application.class.getName(), context);
        return replacement;
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private interface Check { void run() throws Exception; }

    private void check(String name, Check body) {
        JSONObject result = new JSONObject();
        try {
            result.put("name", name);
            body.run();
            result.put("passed", true);
        } catch (Throwable error) {
            failed++;
            while (error instanceof InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            try {
                result.put("passed", false);
                result.put("error", error.getClass().getName());
                // Assertions contain only fixture data; other exceptions may contain app paths.
                if (error instanceof AssertionError) result.put("detail", error.getMessage());
            } catch (Exception ignored) { }
        }
        cases.put(result);
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        try {
            targetLoader = getTargetContext().getClassLoader();
            report.put("targetApkSha256", digest(getTargetContext().getApplicationInfo().sourceDir));
            report.put("process", Application.getProcessName());
            check("isolated_release_process", () -> {
                require((TARGET + ":release_audit").equals(Application.getProcessName()), "Wrong process");
                require(replacement != null && replacement.getClass() == Application.class,
                        "Business Application must not run");
                require(getTargetContext().getApplicationContext() == replacement, "Wrong Application context");
                List<ProviderInfo> providers = getTargetContext().getPackageManager().queryContentProviders(
                        Application.getProcessName(), Process.myUid(), 0);
                require(providers == null || providers.isEmpty(), "Process unexpectedly has providers");
                require((getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
                        "Expected non-debuggable Release target");
            });
            // Do not run any fixtures if process isolation is not established.
            if (failed == 0) runFixtures();
        } catch (Throwable error) {
            failed++;
            try { report.put("runnerError", error.getClass().getName()); } catch (Exception ignored) { }
        } finally {
            try {
                int passed = 0;
                for (int i = 0; i < cases.length(); i++) {
                    if (cases.getJSONObject(i).optBoolean("passed", false)) passed++;
                }
                report.put("cases", cases);
                report.put("passed", passed);
                report.put("failed", failed);
            } catch (Exception ignored) { failed++; }
        }
        Bundle result = new Bundle();
        result.putString("audit_json", report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runFixtures() throws Exception {
        gson = type("com.google.gson.Gson").getDeclaredConstructor().newInstance();
        check("alarm_record_generic_roundtrip", () -> {
            // Inspect the exact anonymous generic used by production loadRecords. Its unused
            // constructor may be inlined legitimately; the Signature is the Gson contract.
            Type parent = type(AGENT + "AgentAlarmToolService$loadRecords$type$1").getGenericSuperclass();
            require(parent instanceof ParameterizedType, "Alarm TypeToken lost generic Signature");
            Type listType = ((ParameterizedType) parent).getActualTypeArguments()[0];
            Object decoded = decode("[{\"alarmId\":\"audit-fixture\",\"triggerAtMillis\":4102444800000,"
                    + "\"createdAtMillis\":1,\"timezone\":\"UTC\",\"state\":\"pending\"}]", listType);
            Object record = ((List<?>) decoded).get(0);
            require(record.getClass().getName().equals(AGENT + "AgentAlarmToolService$ExactAlarmRecordRaw"),
                    "Alarm generic decodes to " + record.getClass().getName());
            require("audit-fixture".equals(field(record, "alarmId")), "Alarm identity changed");
            require(((Number) field(record, "triggerAtMillis")).longValue() == 4102444800000L,
                    "Alarm time changed");
        });
        check("bundled_skill_manifest", () -> {
            String json = asset("builtin_skills/manifest.json");
            Object manifest = decode(json, type(AGENT + "BuiltinSkillManifest"));
            List<?> skills = (List<?>) field(manifest, "skills");
            JSONArray expected = new JSONObject(json).getJSONArray("skills");
            require(skills.size() == expected.length() && skills.size() > 0, "Bundled skills lost");
            for (int i = 0; i < skills.size(); i++) {
                Object skill = skills.get(i);
                require(skill.getClass().getName().equals(AGENT + "BuiltinSkillAsset"), "Skill generic erased");
                require(expected.getJSONObject(i).getString("id").equals(field(skill, "id")), "Skill id changed");
                require(expected.getJSONObject(i).getString("assetPath").equals(field(skill, "assetPath")),
                        "Skill asset path changed");
            }
        });
        check("agent_health_missing_field_defaults", () -> {
            Object value = decode("{\"status\":\"online\",\"installed\":true}", type(AGENT + "runtime.AcpAgentHealth"));
            require(field(value, "capabilities") instanceof Map, "Missing capabilities default became null");
            require("online".equals(field(value, "status")), "Status lost");
        });
        check("official_vlm_missing_field_defaults", () -> {
            Object value = decode("{\"enabled\":false}", type("cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig"));
            for (String name : new String[]{"apiBase", "model", "wireApi"}) {
                require(field(value, name) instanceof String, "Missing " + name + " default became null");
            }
        });
        check("skill_registry_missing_field_defaults", () -> {
            Object value = decode("{}", type(AGENT + "SkillRegistryEntry"));
            require(Boolean.TRUE.equals(field(value, "enabled")), "Skill enabled default lost");
            require("user".equals(field(value, "source")), "Skill source default lost");
            require("installed".equals(field(value, "installState")), "Skill installation default lost");
        });
        check("memory_embedding_response", () -> {
            Object value = decode("{\"data\":[{\"embedding\":[0.125,0.25]}]}",
                    type(AGENT + "PlatformEmbeddingGateway$EmbeddingResponse"));
            List<?> entries = (List<?>) field(value, "data");
            List<?> vector = (List<?>) field(entries.get(0), "embedding");
            require(vector.size() == 2 && Double.valueOf(0.125).equals(vector.get(0))
                    && Double.valueOf(0.25).equals(vector.get(1)), "Embedding vector lost");
        });
        check("quicklog_widget_missing_field_defaults", () -> {
            Object value = decode("{}", type(TARGET + ".quicklog.QuickLogWidgetSettings"));
            require("tasks".equals(field(value, "selectedListId")), "Widget list default lost");
            require(Integer.valueOf(92).equals(field(value, "opacityPercent")), "Widget opacity default lost");
            require("dark".equals(field(value, "colorTheme")), "Widget theme default lost");
        });
        check("provider_legacy_and_canonical_fields", () -> {
            Class<?> model = type("cn.com.omnimind.baselib.llm.ModelProviderConfigStore$StoredModelProviderProfile");
            Object legacy = decode("{\"a\":\"fixture\",\"b\":\"test\",\"c\":\"https://example.invalid\",\"d\":\"fake-key\"}", model);
            Object current = decode("{\"id\":\"fixture\",\"name\":\"test\",\"baseUrl\":\"https://example.invalid\",\"apiKey\":\"fake-key\"}", model);
            for (String name : new String[]{"id", "name", "baseUrl", "apiKey"}) {
                require(field(current, name) != null && field(current, name).equals(field(legacy, name)),
                        "Legacy Provider alias mismatch: " + name);
            }
            Method encode = gson.getClass().getDeclaredMethod("toJson", Object.class); encode.setAccessible(true);
            JSONObject encoded = new JSONObject((String) encode.invoke(gson, legacy));
            require("fixture".equals(encoded.getString("id")) && !encoded.has("a"), "Provider canonical JSON changed");
        });

        String[] plugins = {
            "com.mr.flutter.plugin.filepicker.FilePickerPlugin", "com.ryanheise.audio_session.AudioSessionPlugin",
            "io.flutter.plugins.flutter_plugin_android_lifecycle.FlutterAndroidLifecyclePlugin", "studio.midoridesign.gal.GalPlugin",
            "io.flutter.plugins.imagepicker.ImagePickerPlugin", "com.github.dart_lang.jni.JniPlugin",
            "com.github.dart_lang.jni_flutter.JniFlutterPlugin", "com.ryanheise.just_audio.JustAudioPlugin",
            "dev.steenbakker.mobile_scanner.MobileScannerPlugin", "io.flutter.plugins.sharedpreferences.SharedPreferencesPlugin",
            "com.tekartik.sqflite.SqflitePlugin", "io.flutter.plugins.urllauncher.UrlLauncherPlugin",
            "io.flutter.plugins.videoplayer.VideoPlayerPlugin", "io.flutter.plugins.webviewflutter.WebViewFlutterPlugin"
        };
        for (String plugin : plugins) {
            check("plugin_construct_" + plugin.substring(plugin.lastIndexOf('.') + 1),
                    () -> require(type(plugin).getDeclaredConstructor().newInstance() != null, "Plugin construction failed"));
        }
        check("terminal_jni_subprocess", () -> {
            Class<?> jni = type("com.termux.terminal.JNI");
            int[] pid = new int[1];
            Method create = jni.getDeclaredMethod("createSubprocess", String.class, String.class,
                    String[].class, String[].class, int[].class, int.class, int.class, int.class, int.class);
            create.setAccessible(true);
            int fd = (Integer) create.invoke(null, "/system/bin/sh", getTargetContext().getCacheDir().getPath(),
                    new String[]{"/system/bin/sh", "-c", "exit 7"}, new String[]{"PATH=/system/bin"}, pid, 24, 80, 0, 0);
            require(fd >= 0 && pid[0] > 0, "PTY process creation failed");
            try {
                Method wait = jni.getDeclaredMethod("waitFor", int.class); wait.setAccessible(true);
                require(Integer.valueOf(7).equals(wait.invoke(null, pid[0])), "Native child exit code mismatch");
            } finally {
                Method close = jni.getDeclaredMethod("close", int.class); close.setAccessible(true); close.invoke(null, fd);
            }
        });
        check("room_generated_database_in_memory", () -> {
            Class<?> dbClass = type("cn.com.omnimind.baselib.database.AppDatabase");
            Constructor<?> ctor = type("androidx.room.RoomDatabase$Builder").getDeclaredConstructor(Context.class, Class.class, String.class);
            ctor.setAccessible(true);
            Object builder = ctor.newInstance(getTargetContext(), dbClass, null);
            Object room = call(builder, "build");
            Object helper = call(room, "getOpenHelper");
            try {
                require(call(helper, "getDatabaseName") == null, "Refusing to open a named database");
                Object wrapped = call(helper, "getWritableDatabase");
                SQLiteDatabase database = null;
                for (Field field : wrapped.getClass().getDeclaredFields()) {
                    if (field.getType() == SQLiteDatabase.class) {
                        field.setAccessible(true); database = (SQLiteDatabase) field.get(wrapped); break;
                    }
                }
                require(database != null && database.isOpen(), "Room generated schema did not open");
                require(":memory:".equals(database.getPath()), "Refusing a disk database fixture");
                require(database.getVersion() == 19, "Wrong Room schema version");
                try (Cursor cursor = database.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table'", null)) {
                    require(cursor.moveToFirst() && cursor.getInt(0) >= 6, "Room tables missing");
                }
                database.execSQL("CREATE TEMP TABLE release_audit_fixture (value TEXT NOT NULL)");
                database.execSQL("INSERT INTO release_audit_fixture VALUES ('synthetic')");
                try (Cursor cursor = database.rawQuery("SELECT value FROM release_audit_fixture", null)) {
                    require(cursor.moveToFirst() && "synthetic".equals(cursor.getString(0)), "Memory SQL roundtrip failed");
                }
            } finally { call(helper, "close"); }
        });
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, targetLoader); }
    private Object decode(String json, Type type) throws Exception {
        Method method = gson.getClass().getDeclaredMethod("fromJson", String.class, Type.class);
        method.setAccessible(true); return method.invoke(gson, json, type);
    }
    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static Object call(Object value, String name) throws Exception {
        Method method = value.getClass().getMethod(name); method.setAccessible(true); return method.invoke(value);
    }
    private String asset(String path) throws Exception {
        try (InputStream input = getTargetContext().getAssets().open(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
            return bytes.toString("UTF-8");
        }
    }
    private static String digest(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(path)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
