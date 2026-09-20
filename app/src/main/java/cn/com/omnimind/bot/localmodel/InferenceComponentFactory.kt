package cn.com.omnimind.bot.localmodel

import android.app.Service
import android.content.Intent
import androidx.core.app.CoreComponentFactory
import cn.com.omnimind.bot.App

/** Android owns service attachment and lifecycle; only class resolution is customized. */
class InferenceComponentFactory : CoreComponentFactory() {
    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        val resolved = if (className == DownloadableInferenceRuntime.SERVICE)
            DownloadableInferenceRuntime.classLoader(App.instance) else cl
        return super.instantiateService(resolved, className, intent)
    }
}
