package cn.com.omnimind.baselib.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import cn.com.omnimind.baselib.Constants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object APPPackageUtil {
     fun isAppDebug(): Boolean {
        return try {
            val pm = BaseApplication. instance.packageManager
            val packageInfo = pm?.getPackageInfo(BaseApplication. instance.packageName ?: "", 0)
            val applicationInfo = packageInfo?.applicationInfo
            (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
        } catch (e: Exception) {
            OmniLog.e("OkHttpManager", "Failed to check if app is debug", e)
            false
        }
    }
    fun getAppName(context: Context, packageName: String): String {
        var appName = ""
        try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getPackageInfo(packageName, 0).applicationInfo
            appName = appInfo?.loadLabel(context.packageManager).toString()
        } catch (e: Exception) {

        }


        return appName;
    }

    fun getAppIconBase64(context: Context, packageName: String): String {
        var base64 = "";

        try {
            val packageManager = context.packageManager
            var iconDrawable = packageManager.getApplicationIcon(packageName)
            val bitmap = iconDrawable.toBitmap()
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            base64 = Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (e: Exception) {
            OmniLog.w("APPPackageUtil", "Unable to render app icon type=${e.javaClass.simpleName}")

        }

        return base64
    }

    fun getAppIconFilePath(context: Context, packageName: String): String {
        var filePath = "";

        try {
            val packageManager = context.packageManager
            var iconDrawable = packageManager.getApplicationIcon(packageName)
            val bitmap = iconDrawable.toBitmap()
            val fileName = "${Constants.APP_ICON_FILENAME_PREFIX}${packageName}.png"
            val file = File(Constants.APP_ICON_DIR, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 50, fos)
            }
            filePath = file.absolutePath
        } catch (e: Exception) {
            OmniLog.w("APPPackageUtil", "Unable to persist app icon type=${e.javaClass.simpleName}")
        }

        return filePath
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
