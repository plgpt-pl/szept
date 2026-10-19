package pl.fourdt.szept

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SzeptApp : Application() {
    lateinit var engine: Engine
    @Volatile var inForeground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = Engine(this)
        Notifications.channels(this)
        engine.onIncoming = { if (!inForeground) Notifications.newMessage(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { inForeground = true; engine.start(); if (engine.registered.value && engine.listenInBackground) ListenService.start(this@SzeptApp) }
            override fun onStop(owner: LifecycleOwner) { inForeground = false; if (!engine.listenInBackground) engine.stop() }
        })
    }

    companion object { lateinit var instance: SzeptApp }
}

object Notifications {
    const val CH_MSG = "wiadomosci"; const val CH_SVC = "nasluch"
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Nowe wiadomości", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Nasłuch w tle", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }
    /** Bez treści i bez nadawcy – z telefonu nic nie wycieka na ekran blokady. */
    fun newMessage(c: Context) {
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH_MSG).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Szept").setContentText("Nowa wiadomość")
            .setContentIntent(pi).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
        try { c.getSystemService(NotificationManager::class.java).notify(1, n) } catch (_: SecurityException) {}
    }
}

/** Trwałe połączenie z własnym serwerem zamiast Google Play Services – dostarczanie w tle bez udziału Google. */
class ListenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifications.CH_SVC).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Szept nasłuchuje")
            .setContentText("Połączenie z Twoim serwerem, bez usług Google").setContentIntent(pi).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(2, n)
        SzeptApp.instance.engine.start()
        return START_STICKY
    }
    companion object {
        fun start(c: Context) { try { c.startForegroundService(Intent(c, ListenService::class.java)) } catch (_: Exception) {} }
        fun stop(c: Context) { c.stopService(Intent(c, ListenService::class.java)) }
    }
}
