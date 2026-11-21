package dev.brainrotprevention.fixture.target

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** `am broadcast -n dev.brainrotprevention.fixture.target/.PostReceiver --es title X` posts a normal notification. */
class PostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "fixture"
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fixture", NotificationManager.IMPORTANCE_DEFAULT))
        val id = nextId++
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("fixture notification $id")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
        Fix.log("posted", "title" to title, "id" to id, "enabled" to nm.areNotificationsEnabled())
        resultCode = 1
        resultData = "posted $id"
    }

    companion object {
        const val CHANNEL = "fixture"
        private var nextId = 1
    }
}
