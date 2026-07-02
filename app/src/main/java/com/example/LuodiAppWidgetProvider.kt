package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class LuodiAppWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_ITEM_CLICK) {
            val clickType = intent.getStringExtra(EXTRA_CLICK_TYPE)
            if (clickType == "COMPLETE") {
                val thoughtId = intent.getIntExtra(EXTRA_THOUGHT_ID, -1)
                if (thoughtId != -1) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            val dao = db.luodiDao()
                            val thought = dao.getThoughtById(thoughtId)
                            if (thought != null) {
                                val nextStatus = if (thought.status == "COMPLETED") "PLACED" else "COMPLETED"
                                val nextCompletedAt = if (nextStatus == "COMPLETED") System.currentTimeMillis() else 0L
                                dao.updateThought(thought.copy(
                                    status = nextStatus,
                                    completedAt = nextCompletedAt
                                ))
                                updateWidgets(context)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            } else {
                // OPEN_APP or default
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(appIntent)
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Quick add action pending intent
        val quickAddIntent = Intent(context, QuickAddActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val quickAddPendingIntent = PendingIntent.getActivity(
            context,
            9999,
            quickAddIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val dao = db.luodiDao()

                val configs = dao.getAllDayConfigsOnce()
                val todayIndex = getTodayIndex()
                val todayConfig = configs.find { it.dayIndex == todayIndex } ?: DayConfig(
                    todayIndex,
                    when (todayIndex) {
                        0 -> "週一"
                        1 -> "週二"
                        2 -> "週三"
                        3 -> "週四"
                        4 -> "週五"
                        5 -> "週六"
                        else -> "週日"
                    },
                    "NON_SPORT"
                )

                val dayTypeLabel = try {
                    DayType.valueOf(todayConfig.dayType).label
                } catch (e: Exception) {
                    "非運動日"
                }

                val formattedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("M/d"))
                val dateText = "$formattedDate ${todayConfig.dayName} - $dayTypeLabel"

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.luodi_widget)
                    
                    // Set header texts (single header)
                    views.setTextViewText(R.id.widget_title, dateText)

                    // Setup click on widget root (outside ListView) to open the App
                    views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)

                    // Setup click on the quick add text input bar
                    views.setOnClickPendingIntent(R.id.widget_input_bar, quickAddPendingIntent)

                    // Set up RemoteViewsService for scrollable ListView
                    val serviceIntent = Intent(context, LuodiWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                    }
                    views.setRemoteAdapter(R.id.widget_list, serviceIntent)

                    // Set up PendingIntent template for ListView click handling
                    val clickIntentTemplate = Intent(context, LuodiAppWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_ITEM_CLICK
                    }
                    val clickPendingIntentTemplate = PendingIntent.getBroadcast(
                        context,
                        appWidgetId,
                        clickIntentTemplate,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    views.setPendingIntentTemplate(R.id.widget_list, clickPendingIntentTemplate)

                    // Notify ListView of data change to refresh items
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_ITEM_CLICK = "com.example.ACTION_WIDGET_ITEM_CLICK"
        const val EXTRA_CLICK_TYPE = "com.example.EXTRA_CLICK_TYPE"
        const val EXTRA_THOUGHT_ID = "com.example.EXTRA_THOUGHT_ID"

        fun updateWidgets(context: Context) {
            val intent = Intent(context, LuodiAppWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, LuodiAppWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
