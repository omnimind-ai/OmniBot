package cn.com.omnimind.bot

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {
    
    companion object {
        private const val TAG = "NotificationListener"
        var isListening = false
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        isListening = true
        Log.d(TAG, "Notification listener connected")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            val packageName = notification.packageName
            val title = notification.notification.extras.getString("android.title")
            val text = notification.notification.extras.getString("android.text")
            
            // Process notification for AI agent
            if (title != null && text != null) {
                // Send to AI agent for processing
                handleNotification(packageName, title, text)
            }
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Handle notification removal if needed
    }
    
    private fun handleNotification(packageName: String, title: String, text: String) {
        Log.d(TAG, "Received notification from \$packageName: \$title - \$text")
        // Pass to AI agent for processing
        // Example: AgentManager.processNotification(packageName, title, text)
    }
}
