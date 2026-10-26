package ua.svitlo.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Фонова служба: тримає датчик живлення, голос і оголошує події розкладу. */
class PowerService : Service() {

    companion object {
        const val ACTION_ANNOUNCE = "ua.svitlo.ANNOUNCE"
        const val ACTION_TEST = "ua.svitlo.TEST"
        const val ACTION_STOP_SOUND = "ua.svitlo.STOP_SOUND"
        const val ACTION_REFRESH = "ua.svitlo.REFRESH"
        const val ACTION_FETCH = "ua.svitlo.FETCH"
        const val BROADCAST_UPDATE = "ua.svitlo.UPDATE"
        private const val CH_SVC = "svc"
        private const val CH_ALERT = "alert"
        private const val ID_SVC = 1
        private const val ID_ALERT = 2

        fun start(c: Context, action: String? = null, extras: Intent? = null) {
            val i = Intent(c, PowerService::class.java)
            action?.let { i.action = it }
            extras?.extras?.let { i.putExtras(it) }
            c.startForegroundService(i)
        }
    }

    private lateinit var announcer: Announcer
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlugged: Boolean? = null

    private val check = Runnable {
        val p = isPlugged(this)
        val prev = lastPlugged
        lastPlugged = p
        if (prev != null && p != prev) {
            if (Prefs.useSensor(this)) {
                if (p) announce("on", Texts.SENSOR_ON) else announce("off", Texts.SENSOR_OFF)
            } else {
                Prefs.addLog(this, if (p) "Датчик: живлення з'явилося" else "Датчик: живлення зникло")
            }
        }
        notifyUi()
    }

    /** Оновлення графіка з сайту; наступне ставить будильником (див. Updater.nextDelay). */
    private fun siteRefresh() {
        if (!Prefs.enabled(this) || !Prefs.autoSite(this) || Prefs.accountSlots(this).isEmpty()) return
        Updater.update(this) { out ->
            Schedule.scheduleNext(this)
            if (out.changes.isNotEmpty()) announce("warn", Texts.scheduleChanged(this, out.changes))
            else if (!out.ok) Prefs.addLog(this, out.message)
            Schedule.scheduleFetch(this, Updater.nextDelay(this))
            notifyUi()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Невелика затримка, щоб не реагувати на блимання світла
            handler.removeCallbacks(check)
            handler.postDelayed(check, 4000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        goForeground()
        announcer = Announcer(this)
        lastPlugged = isPlugged(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(powerReceiver, f)
        // Після запуску служби (перезавантаження, оновлення додатка) не питаємо сайт, якщо дані свіжі
        val age = System.currentTimeMillis() - Prefs.lastFetch(this)
        if (age in 0 until 50 * 60_000L) Schedule.scheduleFetch(this, 60 * 60_000L - age)
        else handler.postDelayed({ siteRefresh() }, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_ANNOUNCE -> {
                val at = intent.getLongExtra("at", 0L)
                // пропускаємо дуже запізнілі будильники (наприклад, після довгого сну телефону)
                val late = System.currentTimeMillis() - at > 10 * 60_000L
                // усі події цієї хвилини: в одному місці можуть вимикати, а в іншому вмикати
                val evs = if (late || !Prefs.useSchedule(this)) emptyList() else Schedule.eventsAt(this, at)
                if (evs.isNotEmpty()) {
                    val kind = when {
                        evs.any { it.kind == "off" } -> "off"
                        evs.any { it.kind == "on" } -> "on"
                        else -> "warn"
                    }
                    announce(kind, evs.joinToString(" ") { Texts.event(this, it) })
                }
                Schedule.scheduleNext(this)
            }
            ACTION_TEST -> {
                val kind = intent.getStringExtra("kind")
                if (kind == "off") announce("off", Texts.OFF, log = false)
                else announce("on", Texts.ON, log = false)
            }
            ACTION_STOP_SOUND -> announcer.stop()
            ACTION_FETCH -> handler.post { siteRefresh() }
            else -> Schedule.scheduleNext(this)
        }
        if (!Prefs.enabled(this) && intent?.action != ACTION_TEST && intent?.action != ACTION_STOP_SOUND) {
            stopSelf()
        }
        notifyUi()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        announcer.shutdown()
        super.onDestroy()
    }

    private fun announce(kind: String, text: String, log: Boolean = true) {
        // будимо процесор на час оголошення
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svitlo:announce")
        wl.acquire(90_000L)
        announcer.announce(kind, text, Prefs.repeats(this), Prefs.siren(this))
        if (log) Prefs.addLog(this, text)
        showAlert(kind, text)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Робота у фоні",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Оголошення про світло",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // звук і голос відтворює сам додаток
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun statusLine(): String {
        val ev = Schedule.upcoming(this, false).firstOrNull()
        val plug = if (isPlugged(this)) "живлення від мережі" else "на батареї"
        return if (ev != null && Prefs.useSchedule(this)) {
            val what = if (ev.kind == "off") "Відключення" else "Включення"
            val where = if (Prefs.activeSlots(this).size > 1) " (${ev.slots.joinToString { Prefs.place(this, it) }})" else ""
            "$what о ${Texts.hhmm(ev.at)}$where · $plug"
        } else plug
    }

    private fun goForeground() {
        val n = Notification.Builder(this, CH_SVC)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Стежу за світлом")
            .setContentText(statusLine())
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SVC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_SVC, n)
        }
    }

    private fun showAlert(kind: String, text: String) {
        val title = when (kind) {
            "off" -> "Відключення електроенергії"
            "on" -> "Світло відновлено"
            else -> "Попередження"
        }
        val stop = PendingIntent.getForegroundService(this, 5,
            Intent(this, PowerService::class.java).setAction(ACTION_STOP_SOUND),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Тиша", stop).build())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
    }

    private fun notifyUi() {
        goForeground()
        sendBroadcast(Intent(BROADCAST_UPDATE).setPackage(packageName))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Prefs.enabled(c)) return
        try {
            if (i.action == PowerService.ACTION_FETCH) PowerService.start(c, PowerService.ACTION_FETCH)
            else PowerService.start(c, PowerService.ACTION_ANNOUNCE, i)
        } catch (e: IllegalStateException) {
            // система не дала запустити службу з фону — спробуємо оновитися за годину
            if (i.action == PowerService.ACTION_FETCH) Schedule.scheduleFetch(c, 60 * 60_000L)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.enabled(c)) PowerService.start(c)
    }
}
