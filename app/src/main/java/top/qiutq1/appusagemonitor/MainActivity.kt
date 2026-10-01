package top.qiutq1.appusagemonitor

import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

private data class AppEntry(val packageName: String, val label: String)
private data class SortableApp(val app: AppEntry, val pinyin: String, val initial: String)
private data class UsageRow(val app: AppEntry, val millis: Long, val launches: Int)
private data class OpenRecord(val app: AppEntry, val startedAt: Long, val endedAt: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasUsageAccess(this)) startUsageMonitor(this)
        onBackPressedDispatcher.addCallback(this) {
            if (getSharedPreferences("monitor", Context.MODE_PRIVATE).getBoolean("hide_from_recents_on_exit", false)) finishAndRemoveTask() else finish()
        }
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF426B5A), background = Color(0xFFF5F6F2))) { UsageScreen() } }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UsageScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("monitor", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(hasUsageAccess(context)) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var selected by remember { mutableStateOf(prefs.getStringSet("packages", emptySet())?.toSet() ?: emptySet()) }
    var rangeStartMillis by remember { mutableLongStateOf(startOfTodayMillis()) }
    var rangeEndMillis by remember { mutableLongStateOf(startOfTodayMillis()) }
    var draftStartMillis by remember { mutableLongStateOf(startOfTodayMillis()) }
    var draftEndMillis by remember { mutableLongStateOf(startOfTodayMillis()) }
    var rows by remember { mutableStateOf(emptyList<UsageRow>()) }
    var records by remember { mutableStateOf(emptyList<OpenRecord>()) }
    var loading by remember { mutableStateOf(false) }
    var sortByAppName by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showDateRange by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var saveOldHistory by remember { mutableStateOf(prefs.getBoolean("save_old_history", true)) }
    var hideFromRecentsOnExit by remember { mutableStateOf(prefs.getBoolean("hide_from_recents_on_exit", false)) }
    var selectedApp by remember { mutableStateOf<AppEntry?>(null) }
    var appSearchQuery by remember { mutableStateOf("") }
    val sortedApps = remember(apps) { sortAppsByPinyinInitial(apps) }
    val visibleApps = remember(sortedApps, appSearchQuery) {
        val query = appSearchQuery.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) sortedApps else sortedApps.filter {
            it.app.label.contains(query, ignoreCase = true) || it.pinyin.contains(query)
        }
    }
    val sortedRows = remember(rows, sortByAppName) {
        if (sortByAppName) {
            val rowsByPackage = rows.associateBy { it.app.packageName }
            sortAppsByPinyinInitial(rows.map { it.app }).mapNotNull { rowsByPackage[it.app.packageName] }
        } else {
            rows.sortedByDescending { it.millis }
        }
    }

    fun refresh() {
        allowed = hasUsageAccess(context)
        if (!allowed) return
        startUsageMonitor(context)
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { readUsage(context, selected, rangeStartMillis, rangeEndMillis) }
            rows = result.first
            records = result.second
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { installedApps(context) }
        if (!prefs.contains("packages")) {
            selected = apps.map { it.packageName }.toSet()
            prefs.edit().putStringSet("packages", selected).apply()
        }
        refresh()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) { exportCsv(context, uri, records) }
    }
    val excelExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) { exportExcel(context, uri, records) }
    }

    Scaffold(containerColor = Color(0xFFF5F6F2)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("应用使用时长", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = true }) { Text("设置") }
            }
            val todayMillis = startOfTodayMillis()
            val isToday = rangeStartMillis == todayMillis && rangeEndMillis == todayMillis
            val dateFormatter = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
            val rangeLabel = if (rangeStartMillis == rangeEndMillis) dateFormatter.format(Date(rangeStartMillis))
                else "${dateFormatter.format(Date(rangeStartMillis))} 至 ${dateFormatter.format(Date(rangeEndMillis))}"
            Text(
                "${if (isToday) "今天 · " else "查看范围 · "}$rangeLabel  ▾",
                color = Color(0xFF68736D), modifier = Modifier.padding(top = 6.dp).clickable {
                    draftStartMillis = rangeStartMillis
                    draftEndMillis = rangeEndMillis
                    showDateRange = true
                }
            )
            Spacer(Modifier.height(20.dp))
            if (!allowed) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("需要使用情况访问权限", fontWeight = FontWeight.SemiBold)
                        Text("Android 仅在你授权后允许查看应用使用时长。", color = Color(0xFF68736D), modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("前往系统设置") }
                    }
                }
            } else {
                val total = rows.sumOf { it.millis }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF426B5A))) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("监控应用总时长", color = Color.White.copy(alpha = .8f))
                        Text(formatDuration(total), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text("${selected.size} 个应用 · ${rows.sumOf { it.launches }} 次打开", color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { appSearchQuery = ""; showApps = true }, modifier = Modifier.weight(1f)) { Text("管理应用 (${selected.size})") }
                    OutlinedButton(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "刷新中" else "刷新") }
                    Box {
                        Button(onClick = { showExportMenu = true }, enabled = rows.isNotEmpty()) { Text("导出") }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(text = { Text("导出 CSV") }, onClick = {
                                showExportMenu = false
                                val fileDates = SimpleDateFormat("yyyyMMdd", Locale.US)
                                exportLauncher.launch("app_usage_${fileDates.format(Date(rangeStartMillis))}_${fileDates.format(Date(rangeEndMillis))}.csv")
                            })
                            DropdownMenuItem(text = { Text("导出 Excel") }, onClick = {
                                showExportMenu = false
                                val fileDates = SimpleDateFormat("yyyyMMdd", Locale.US)
                                excelExportLauncher.launch("app_usage_${fileDates.format(Date(rangeStartMillis))}_${fileDates.format(Date(rangeEndMillis))}.xlsx")
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("汇总排序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(12.dp))
                    FilterChip(selected = !sortByAppName, onClick = { sortByAppName = false }, label = { Text("使用时长") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = sortByAppName, onClick = { sortByAppName = true }, label = { Text("应用名") })
                }
                if (rows.isEmpty() && !loading) Text("所选日期范围没有监测到应用使用记录。", color = Color(0xFF68736D), modifier = Modifier.padding(top = 12.dp))
                LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stickyHeader {
                        Text(
                            "使用汇总 · ${if (sortByAppName) "应用名" else "使用时长"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F6F2)).padding(top = 10.dp, bottom = 8.dp)
                        )
                    }
                    items(sortedRows, key = { it.app.packageName }) { row ->
                        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.clickable { selectedApp = row.app }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.app.label, fontWeight = FontWeight.SemiBold)
                                    Text("${row.launches} 次打开", color = Color(0xFF68736D), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(formatDuration(row.millis), fontWeight = FontWeight.Bold, color = Color(0xFF426B5A))
                            }
                        }
                    }
                    stickyHeader {
                        Text(
                            "打开记录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F6F2)).padding(top = 10.dp, bottom = 8.dp)
                        )
                    }
                    items(records.asReversed().take(100), key = { "${it.app.packageName}_${it.startedAt}_${it.endedAt}" }) { record ->
                        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(record.app.label, fontWeight = FontWeight.SemiBold)
                                    Text(SimpleDateFormat("M月d日 HH:mm:ss", Locale.getDefault()).format(Date(record.startedAt)), color = Color(0xFF68736D), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(formatDuration(record.endedAt - record.startedAt), color = Color(0xFF426B5A))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("保存超过 10 天的数据", fontWeight = FontWeight.Medium)
                        Text("关闭后不再写入本地 SQLite，已保存的数据不会删除。", color = Color(0xFF68736D), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = saveOldHistory, onCheckedChange = {
                        saveOldHistory = it
                        prefs.edit().putBoolean("save_old_history", it).apply()
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("退出时从最近任务中隐藏", fontWeight = FontWeight.Medium)
                        Text("使用返回键结束应用时，不保留任务卡片。", color = Color(0xFF68736D), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = hideFromRecentsOnExit, onCheckedChange = {
                        hideFromRecentsOnExit = it
                        prefs.edit().putBoolean("hide_from_recents_on_exit", it).apply()
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } }
    )

    if (showApps) AlertDialog(
        onDismissRequest = { showApps = false },
        title = { Text("选择监控应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索应用名") },
                    placeholder = { Text("输入中文名、英文名或拼音") }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        selected = apps.map { it.packageName }.toSet()
                        prefs.edit().putStringSet("packages", selected).apply()
                    }) { Text("全选") }
                    TextButton(onClick = {
                        selected = emptySet()
                        prefs.edit().putStringSet("packages", selected).apply()
                    }) { Text("取消全选") }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(visibleApps, key = { it.app.packageName }) { sortable ->
                        val app = sortable.app
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                            prefs.edit().putStringSet("packages", selected).apply()
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                            Text(app.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showApps = false; refresh() }) { Text("完成") } }
    )

    if (showDateRange) AlertDialog(
        onDismissRequest = { showDateRange = false },
        title = { Text("选择查看范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val current = Calendar.getInstance().apply { timeInMillis = draftStartMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        draftStartMillis = localDayMillis(year, month, day)
                        if (draftStartMillis > draftEndMillis) draftEndMillis = draftStartMillis
                    }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.maxDate = endOfLocalDayMillis(draftEndMillis)
                    }.show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("开始日期：${formatDate(draftStartMillis)}")
                }
                OutlinedButton(onClick = {
                    val current = Calendar.getInstance().apply { timeInMillis = draftEndMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        draftEndMillis = localDayMillis(year, month, day)
                        if (draftEndMillis < draftStartMillis) draftStartMillis = draftEndMillis
                    }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("结束日期：${formatDate(draftEndMillis)}")
                }
                Text("包含开始和结束日期的全部记录。", color = Color(0xFF68736D), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            rangeStartMillis = draftStartMillis
            rangeEndMillis = draftEndMillis
            showDateRange = false
            refresh()
        }) { Text("查看") } },
        dismissButton = { TextButton(onClick = { showDateRange = false }) { Text("取消") } }
    )

    selectedApp?.let { app ->
        val appRecords = records.filter { it.app.packageName == app.packageName }.asReversed()
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text("${app.label} · 打开记录") },
            text = {
                if (appRecords.isEmpty()) {
                    Text("所选日期范围没有该应用的打开记录。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(appRecords, key = { "${it.startedAt}_${it.endedAt}" }) { record ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F6F2))) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("打开：${formatTime(record.startedAt)}", fontWeight = FontWeight.Medium)
                                    Text("关闭：${formatTime(record.endedAt)}", modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedApp = null }) { Text("关闭") } }
        )
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}

private fun startUsageMonitor(context: Context) {
    val intent = Intent(context, UsageMonitorService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
}

private fun installedApps(context: Context): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return context.packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
        val activity = info.activityInfo ?: return@mapNotNull null
        AppEntry(activity.packageName, info.loadLabel(context.packageManager).toString())
}.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

private val pinyinOutputFormat = HanyuPinyinOutputFormat().apply {
    setCaseType(HanyuPinyinCaseType.LOWERCASE)
    setToneType(HanyuPinyinToneType.WITHOUT_TONE)
    setVCharType(HanyuPinyinVCharType.WITH_V)
}

private fun sortAppsByPinyinInitial(apps: List<AppEntry>): List<SortableApp> = apps
    .map { app ->
        val pinyin = buildString {
            app.label.forEach { character ->
                val romanized = try {
                    PinyinHelper.toHanyuPinyinStringArray(character, pinyinOutputFormat)?.firstOrNull()
                } catch (_: Exception) {
                    null
                }
                append(romanized ?: character.toString())
            }
        }.lowercase(Locale.ROOT)
        val initial = pinyin.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
        SortableApp(app, pinyin, initial)
    }
    .sortedWith(compareBy<SortableApp> { it.initial }.thenBy { it.pinyin }.thenBy { it.app.label.lowercase(Locale.ROOT) })

private fun readUsage(
    context: Context,
    selected: Set<String>,
    selectedRangeStart: Long,
    selectedRangeEnd: Long
): Pair<List<UsageRow>, List<OpenRecord>> {
    val apps = installedApps(context).associateBy { it.packageName }
    val now = System.currentTimeMillis()
    val start = selectedRangeStart
    val endExclusive = Calendar.getInstance().apply { timeInMillis = selectedRangeEnd; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    val end = minOf(endExclusive, now)
    if (end <= start) return emptyList<UsageRow>() to emptyList()
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val dailyStats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: emptyList()
    val dailyTotals = mutableMapOf<String, Long>()
    dailyStats.forEach { stat ->
        if (stat.packageName in selected) {
            dailyTotals[stat.packageName] = (dailyTotals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
        }
    }
    val apiEvents = mutableListOf<StoredUsageEvent>()
    val apiCursor = manager.queryEvents(start, end)
    val apiEvent = UsageEvents.Event()
    while (apiCursor.hasNextEvent()) {
        apiCursor.getNextEvent(apiEvent)
        apiEvents += StoredUsageEvent(apiEvent.packageName, apiEvent.className, apiEvent.eventType, apiEvent.timeStamp)
    }
    val archivedEvents = UsageEventStore(context).use { it.read(start, end) }
    val events = (apiEvents + archivedEvents).distinctBy { listOf(it.packageName, it.className, it.type, it.timestamp) }.sortedBy { it.timestamp }
    val eventTotals = mutableMapOf<String, Long>()
    val launches = mutableMapOf<String, Int>()
    val sessionStarts = mutableMapOf<String, Long>()
    val foregroundActivities = mutableMapOf<String, MutableSet<String>>()
    val pendingBackground = mutableMapOf<String, Long>()
    val records = mutableListOf<OpenRecord>()
    var eventIndex = 0

    fun finishSession(packageName: String, endedAt: Long) {
        val beganAt = sessionStarts.remove(packageName) ?: return
        val duration = (endedAt - beganAt).coerceAtLeast(0)
        eventTotals[packageName] = (eventTotals[packageName] ?: 0L) + duration
        records.add(OpenRecord(apps[packageName] ?: AppEntry(packageName, packageName), beganAt, endedAt))
    }

    fun flushExpiredBackgrounds(eventTime: Long) {
        pendingBackground.toMap().forEach { (packageName, endedAt) ->
            if (eventTime - endedAt > ACTIVITY_SWITCH_GRACE_MILLIS) {
                pendingBackground.remove(packageName)
                foregroundActivities.remove(packageName)
                finishSession(packageName, endedAt)
            }
        }
    }

    while (eventIndex < events.size) {
        val event = events[eventIndex++]
        val timestamp = event.timestamp
        flushExpiredBackgrounds(timestamp)
        val pkg = event.packageName
        if (pkg !in selected) continue

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (event.type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pendingEnd = pendingBackground.remove(pkg)
                    if (pendingEnd != null && timestamp - pendingEnd > ACTIVITY_SWITCH_GRACE_MILLIS) {
                        finishSession(pkg, pendingEnd)
                    }
                    if (pkg !in sessionStarts) {
                        sessionStarts[pkg] = timestamp
                        launches[pkg] = (launches[pkg] ?: 0) + 1
                    }
                    val activity = event.className ?: UNKNOWN_ACTIVITY
                    foregroundActivities.getOrPut(pkg) { mutableSetOf() }.add(activity)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val activity = event.className ?: UNKNOWN_ACTIVITY
                    val activeActivities = foregroundActivities[pkg]
                    activeActivities?.remove(activity)
                    if (activeActivities.isNullOrEmpty() && pkg in sessionStarts) {
                        pendingBackground[pkg] = timestamp
                    }
                }
            }
        } else {
            when (event.type) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (pkg !in sessionStarts) {
                        sessionStarts[pkg] = timestamp
                        launches[pkg] = (launches[pkg] ?: 0) + 1
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> finishSession(pkg, timestamp)
            }
        }
    }

    pendingBackground.toMap().forEach { (pkg, endedAt) ->
        pendingBackground.remove(pkg)
        foregroundActivities.remove(pkg)
        finishSession(pkg, endedAt)
    }
    sessionStarts.toMap().forEach { (pkg, beganAt) ->
        val sessionEnd = end
        val duration = (sessionEnd - beganAt).coerceAtLeast(0)
        eventTotals[pkg] = (eventTotals[pkg] ?: 0L) + duration
        records.add(OpenRecord(apps[pkg] ?: AppEntry(pkg, pkg), beganAt, sessionEnd))
    }

    val summaries = selected.mapNotNull { pkg ->
        apps[pkg]?.let { UsageRow(it, maxOf(dailyTotals[pkg] ?: 0L, eventTotals[pkg] ?: 0L), launches[pkg] ?: 0) }
    }
        .filter { it.millis > 0 || it.launches > 0 }.sortedByDescending { it.millis }
    return summaries to records.sortedBy { it.startedAt }
}

private const val ACTIVITY_SWITCH_GRACE_MILLIS = 2_000L
private const val UNKNOWN_ACTIVITY = "<unknown>"

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return if (hours > 0) "${hours}小时${minutes % 60}分" else "${minutes}分钟"
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatDate(timestamp: Long): String = SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))

private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun localDayMillis(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
    set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfLocalDayMillis(dayStart: Long): Long = Calendar.getInstance().apply {
    timeInMillis = dayStart
    add(Calendar.DAY_OF_YEAR, 1)
    add(Calendar.MILLISECOND, -1)
}.timeInMillis

private fun exportCsv(context: Context, uri: Uri, records: List<OpenRecord>) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write("应用名称,包名,打开时间,结束时间,使用时长(毫秒)\r\n")
            records.forEach { record ->
                fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                writer.write("${quote(record.app.label)},${quote(record.app.packageName)},${quote(dateFormat.format(Date(record.startedAt)))},${quote(dateFormat.format(Date(record.endedAt)))},${(record.endedAt - record.startedAt).coerceAtLeast(0)}\r\n")
            }
        }
    }
}

private fun exportExcel(context: Context, uri: Uri, records: List<OpenRecord>) {
    val output = context.contentResolver.openOutputStream(uri) ?: return
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun cell(reference: String, value: String) = "<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="应用使用记录" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            append("<row r=\"1\">")
            append(cell("A1", "应用名称")); append(cell("B1", "包名")); append(cell("C1", "打开时间")); append(cell("D1", "结束时间")); append(cell("E1", "使用时长(毫秒)"))
            append("</row>")
            records.forEachIndexed { index, record ->
                val row = index + 2
                append("<row r=\"$row\">")
                append(cell("A$row", record.app.label)); append(cell("B$row", record.app.packageName))
                append(cell("C$row", dateFormat.format(Date(record.startedAt)))); append(cell("D$row", dateFormat.format(Date(record.endedAt))))
                append("<c r=\"E$row\"><v>${(record.endedAt - record.startedAt).coerceAtLeast(0)}</v></c>")
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        entry("xl/worksheets/sheet1.xml", sheet)
    }
}
