package top.qiutq1.appusagemonitor

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class StoredUsageEvent(val packageName: String, val className: String?, val type: Int, val timestamp: Long)

internal class UsageEventStore(context: Context) : SQLiteOpenHelper(context, "usage_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage_events (package_name TEXT NOT NULL, class_name TEXT NOT NULL, event_type INTEGER NOT NULL, timestamp INTEGER NOT NULL, PRIMARY KEY(package_name, class_name, event_type, timestamp))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(events: List<StoredUsageEvent>) {
        if (events.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            val statement = writableDatabase.compileStatement("INSERT OR IGNORE INTO usage_events VALUES (?, ?, ?, ?)")
            events.forEach {
                statement.clearBindings()
                statement.bindString(1, it.packageName)
                statement.bindString(2, it.className ?: "")
                statement.bindLong(3, it.type.toLong())
                statement.bindLong(4, it.timestamp)
                statement.executeInsert()
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun read(from: Long, to: Long): List<StoredUsageEvent> {
        val result = mutableListOf<StoredUsageEvent>()
        readableDatabase.query("usage_events", arrayOf("package_name", "class_name", "event_type", "timestamp"), "timestamp >= ? AND timestamp < ?", arrayOf(from.toString(), to.toString()), null, null, "timestamp ASC").use { cursor ->
            while (cursor.moveToNext()) result += StoredUsageEvent(cursor.getString(0), cursor.getString(1).ifEmpty { null }, cursor.getInt(2), cursor.getLong(3))
        }
        return result
    }

    fun latestTimestamp(): Long = readableDatabase.rawQuery("SELECT MAX(timestamp) FROM usage_events", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}

class UsageMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: UsageEventStore
    override fun onCreate() {
        super.onCreate()
        store = UsageEventStore(this)
        createChannel()
        startForeground(41, notification())
        scope.launch {
            while (isActive) {
                if (hasUsageAccess(this@UsageMonitorService)) collectEvents()
                delay(15_000)
            }
        }
    }

    private fun collectEvents() {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("monitor", MODE_PRIVATE)
        if (!prefs.getBoolean("save_old_history", true)) {
            // Advance the cursor while archival is disabled so events from this interval are not backfilled later.
            prefs.edit().putLong("event_cursor", now).apply()
            return
        }
        val last = prefs.getLong("event_cursor", 0L)
        // Seed the local archive with the portion the system still exposes, then continue from its tail.
        val from = if (last == 0L) now - 10L * 24 * 60 * 60 * 1000 else (last - 2_000).coerceAtLeast(0)
        val events = (getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(from, now)
        val event = UsageEvents.Event()
        val batch = mutableListOf<StoredUsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val trackedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.ACTIVITY_PAUSED
                else type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.MOVE_TO_BACKGROUND
            if (trackedType && event.packageName != packageName) batch += StoredUsageEvent(event.packageName, event.className, type, event.timeStamp)
        }
        store.insert(batch)
        // Persist the cursor separately so a quiet interval does not cause a ten-day rescan each cycle.
        prefs.edit().putLong("event_cursor", now).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("usage_monitor", "应用使用记录", NotificationManager.IMPORTANCE_LOW).apply { description = "持续保存应用使用事件到本机" })
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "usage_monitor") else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("应用使用记录采集中").setContentText("记录仅保存在本机，可查询更早的使用历史").setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); store.close(); super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || !hasUsageAccess(context)) return
        val service = Intent(context, UsageMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service) else context.startService(service)
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}
