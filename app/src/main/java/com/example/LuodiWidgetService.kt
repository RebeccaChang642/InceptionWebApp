package com.example

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class LuodiWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return LuodiWidgetFactory(applicationContext)
    }
}

class LuodiWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var listItems = listOf<WidgetListItem>()

    sealed class WidgetListItem {
        data class EmptySlot(val gap: TimeGap) : WidgetListItem()
        data class SlotWithThoughts(val gap: TimeGap, val thoughts: List<Thought>) : WidgetListItem()
    }

    override fun onCreate() {
        // No-op
    }

    override fun onDataSetChanged() {
        kotlinx.coroutines.runBlocking {
            try {
                val db = AppDatabase.getDatabase(context)
                val dao = db.luodiDao()
                val configs = dao.getAllDayConfigsOnce()
                val todayIndex = getTodayIndex()
                val todayConfig = configs.find { it.dayIndex == todayIndex } ?: DayConfig(
                    todayIndex,
                    getTodayName(todayIndex),
                    "NON_SPORT"
                )

                val allThoughts = dao.getAllThoughtsOnce()
                val todayDateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val todayThoughts = allThoughts.filter {
                    (it.placedDate == todayDateStr || (it.placedDate == null && it.placedDayIndex == todayIndex)) &&
                    (it.status == "PLACED" || it.status == "COMPLETED")
                }

                val gaps = getGapsForDayType(todayConfig.dayType, todayIndex)

                val items = mutableListOf<WidgetListItem>()
                for (gap in gaps) {
                    val slotThoughts = todayThoughts.filter { it.placedSlotId == gap.id }
                    if (slotThoughts.isNotEmpty()) {
                        items.add(WidgetListItem.SlotWithThoughts(gap, slotThoughts))
                    } else {
                        items.add(WidgetListItem.EmptySlot(gap))
                    }
                }
                listItems = items
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getTodayName(dayIndex: Int): String {
        return when (dayIndex) {
            0 -> "週一"
            1 -> "週二"
            2 -> "週三"
            3 -> "週四"
            4 -> "週五"
            5 -> "週六"
            else -> "週日"
        }
    }

    override fun onDestroy() {
        // No-op
    }

    override fun getCount(): Int {
        return listItems.size
    }

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= listItems.size) {
            return RemoteViews(context.packageName, R.layout.widget_item)
        }

        val views = RemoteViews(context.packageName, R.layout.widget_item)
        val item = listItems[position]

        when (item) {
            is WidgetListItem.EmptySlot -> {
                val gap = item.gap
                // Indicator
                val indicatorText = getIndicatorEmoji(gap.semantic)
                views.setTextViewText(R.id.item_indicator, indicatorText)

                // Time and Name
                views.setTextViewText(R.id.item_time_name, "${gap.timeRange} ${gap.name}")

                // Thoughts container is GONE
                views.setViewVisibility(R.id.item_thoughts_container, View.GONE)

                // Warning
                if (gap.semantic == GapSemantic.PURPLE) {
                    views.setViewVisibility(R.id.item_warning, View.VISIBLE)
                    views.setTextViewText(R.id.item_warning, "別碰 / 要睡覺")
                } else {
                    views.setViewVisibility(R.id.item_warning, View.GONE)
                }

                // Setup Open App Click on item
                val fillInIntent = Intent().apply {
                    putExtra(LuodiAppWidgetProvider.EXTRA_CLICK_TYPE, "OPEN_APP")
                }
                views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)
            }
            is WidgetListItem.SlotWithThoughts -> {
                val gap = item.gap
                val thoughts = item.thoughts

                // Indicator
                val indicatorText = getIndicatorEmoji(gap.semantic)
                views.setTextViewText(R.id.item_indicator, indicatorText)

                // Time and Name
                views.setTextViewText(R.id.item_time_name, "${gap.timeRange} ${gap.name}")

                // Clear previous thoughts
                views.removeAllViews(R.id.item_thoughts_container)
                views.setViewVisibility(R.id.item_thoughts_container, View.VISIBLE)

                // Render each thought
                for (thought in thoughts) {
                    val thoughtRow = RemoteViews(context.packageName, R.layout.widget_thought_row)
                    val isCompleted = thought.status == "COMPLETED"
                    val prefix = if (isCompleted) "✓ " else "🧠 "
                    val titleText = "$prefix${thought.title}"

                    val spannable = SpannableString(titleText)
                    val color = if (thought.type == "REVIEW") {
                        0xFFFFA000.toInt() // Amber
                    } else {
                        0xFF80CBB5.toInt() // Premium Mint/Green
                    }

                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        0,
                        titleText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    if (isCompleted) {
                        spannable.setSpan(
                            StrikethroughSpan(),
                            0,
                            titleText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        thoughtRow.setTextViewText(R.id.thought_done_btn, "✓")
                        thoughtRow.setTextColor(R.id.thought_done_btn, 0xFF80CBB5.toInt())
                    } else {
                        thoughtRow.setTextViewText(R.id.thought_done_btn, "○")
                        thoughtRow.setTextColor(R.id.thought_done_btn, 0xFF64748B.toInt())
                    }

                    thoughtRow.setTextViewText(R.id.thought_title, spannable)

                    // Click handler for done button (toggle / complete)
                    val clickIntent = Intent().apply {
                        putExtra(LuodiAppWidgetProvider.EXTRA_CLICK_TYPE, "COMPLETE")
                        putExtra(LuodiAppWidgetProvider.EXTRA_THOUGHT_ID, thought.id)
                    }
                    thoughtRow.setOnClickFillInIntent(R.id.thought_done_btn, clickIntent)

                    // Click on the row opens app
                    val openAppIntent = Intent().apply {
                        putExtra(LuodiAppWidgetProvider.EXTRA_CLICK_TYPE, "OPEN_APP")
                    }
                    thoughtRow.setOnClickFillInIntent(R.id.thought_row_root, openAppIntent)

                    views.addView(R.id.item_thoughts_container, thoughtRow)
                }

                // Warning
                views.setViewVisibility(R.id.item_warning, View.GONE)

                // Setup Open App click on the rest of the row card
                val rowFillInIntent = Intent().apply {
                    putExtra(LuodiAppWidgetProvider.EXTRA_CLICK_TYPE, "OPEN_APP")
                }
                views.setOnClickFillInIntent(R.id.widget_item_root, rowFillInIntent)
            }
        }

        return views
    }

    private fun getIndicatorEmoji(semantic: GapSemantic): String {
        return when (semantic) {
            GapSemantic.AMBER -> "🔸"
            GapSemantic.GREEN -> "🟢"
            GapSemantic.PURPLE -> "💤"
            GapSemantic.GREY_LOCKED -> "🔒"
            GapSemantic.GREY_TIGHT -> "⚠️"
        }
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }
}
