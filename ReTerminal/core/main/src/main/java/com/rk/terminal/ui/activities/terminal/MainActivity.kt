package com.rk.terminal.ui.activities.terminal

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rk.terminal.service.SessionService
import com.rk.libcommons.OMNIBOT_SETUP_SESSION_ID
import com.rk.terminal.ui.navHosts.MainActivityNavHost
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.terminal.TerminalScreen
import com.rk.terminal.ui.screens.terminal.terminalView
import com.rk.terminal.ui.theme.KarbonTheme
import com.rk.terminal.util.PredictiveBackGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    var sessionBinder:SessionService.SessionBinder? = null
    var isBound = false


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SessionService.SessionBinder
            sessionBinder = binder
            isBound = true

            lifecycleScope.launch(Dispatchers.Main){
                setContent {
                    KarbonTheme {
                        Surface {
                            val navController = rememberNavController()
                            MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)

                            val backStackEntry by navController.currentBackStackEntryAsState()

                            val focusManager = LocalFocusManager.current
                            val keyboardController = LocalSoftwareKeyboardController.current

                            LaunchedEffect(backStackEntry?.destination?.route) {
                                if (backStackEntry?.destination?.route != MainActivityRoutes.MainScreen.route) {
                                    // 1️⃣ Clear Compose focus
                                    focusManager.clearFocus(force = true)

                                    // 2️⃣ Clear Android View focus
                                    terminalView.get()?.clearFocus()

                                    // 3️⃣ Hide IME explicitly
                                    keyboardController?.hide()
                                }
                            }


                        }
                    }
                }
            }


        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            sessionBinder = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SessionService::class.java))
        }else{
            startService(Intent(this, SessionService::class.java))
        }
        Intent(this, SessionService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (isFinishing && !isChangingConfigurations) {
            cleanupSetupSessionIfNeeded()
        }
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }


    private var denied = 1
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted && denied <= 2) {
                denied++
                requestPermission()
            }
        }

    fun requestPermission(){
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var isKeyboardVisible = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PredictiveBackGate.install(this)
        enableEdgeToEdge()
        requestPermission()

        // Cross-activity transitions matching the main app's page style
        // (full-width slide in, 25% parallax on the covered page).
        // On API 34+ these participate in the predictive back gesture.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            PredictiveBackGate.isPredictiveBackEnabled(this)
        ) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                com.rk.terminal.R.anim.slide_in_right,
                com.rk.terminal.R.anim.slide_out_left,
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                com.rk.terminal.R.anim.slide_in_left,
                com.rk.terminal.R.anim.slide_out_right,
            )
        }

        if (intent.hasExtra("awake_intent")){
            moveTaskToBack(true)
        }

    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            PredictiveBackGate.isPredictiveBackEnabled(this)
        ) {
            overridePendingTransition(
                com.rk.terminal.R.anim.slide_in_left,
                com.rk.terminal.R.anim.slide_out_right,
            )
        }
    }

    var wasKeyboardOpen = false
    override fun onPause() {
        super.onPause()
        wasKeyboardOpen = isKeyboardVisible
    }

    override fun onResume() {
        super.onResume()

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val isVisible = keypadHeight > screenHeight * 0.15

            isKeyboardVisible = isVisible
        }


        if (wasKeyboardOpen && !isKeyboardVisible){
            terminalView.get()?.let {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun cleanupSetupSessionIfNeeded() {
        val binder = sessionBinder ?: return
        if (binder.getSession(OMNIBOT_SETUP_SESSION_ID) == null) {
            return
        }
        runCatching {
            binder.terminateSession(OMNIBOT_SETUP_SESSION_ID)
        }
    }
}
