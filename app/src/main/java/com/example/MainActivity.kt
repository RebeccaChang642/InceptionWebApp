package com.example

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.roundToInt

// ==========================================
// ROOM PERSISTENCE
// ==========================================

@Entity(tableName = "thoughts")
data class Thought(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val type: String, // "REVIEW" or "FOCUS"
    val isDeadline: Boolean,
    val dueDate: String?, // "yyyy-MM-dd" or null
    val status: String, // "PENDING", "PLACED", "COMPLETED"
    val placedDayIndex: Int? = null, // 0..6
    val placedSlotId: String? = null, // composite ID like "dayIndex_slotIndex"
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val customOrder: Int = 0,
    val placedDate: String? = null
)

@Entity(tableName = "day_configs")
data class DayConfig(
    @PrimaryKey val dayIndex: Int, // 0..6
    val dayName: String, // "週一", "週二" ...
    val dayType: String // "SPORT", "NON_SPORT", "SATURDAY", "SUNDAY"
)

@Dao
interface LuodiDao {
    @Query("SELECT * FROM thoughts ORDER BY createdAt DESC")
    fun getAllThoughts(): Flow<List<Thought>>

    @Query("SELECT * FROM thoughts ORDER BY createdAt DESC")
    suspend fun getAllThoughtsOnce(): List<Thought>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThought(thought: Thought)

    @Update
    suspend fun updateThought(thought: Thought)

    @Delete
    suspend fun deleteThought(thought: Thought)

    @Query("SELECT * FROM thoughts WHERE id = :id LIMIT 1")
    suspend fun getThoughtById(id: Int): Thought?

    @Query("SELECT * FROM day_configs ORDER BY dayIndex ASC")
    fun getAllDayConfigs(): Flow<List<DayConfig>>

    @Query("SELECT * FROM day_configs ORDER BY dayIndex ASC")
    suspend fun getAllDayConfigsOnce(): List<DayConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayConfigs(configs: List<DayConfig>)

    @Update
    suspend fun updateDayConfig(config: DayConfig)
}

@Database(entities = [Thought::class, DayConfig::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun luodiDao(): LuodiDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "luodi_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// DOMAIN MODELS & HELPERS
// ==========================================

enum class DayType(val label: String) {
    SPORT("運動日"),
    NON_SPORT("非運動日"),
    SATURDAY("週六"),
    SUNDAY("週日")
}

enum class GapSemantic(val label: String, val color: Color, val desc: String) {
    AMBER("零碎縫隙", Color(0xFFE5A93C), "只收零碎"),
    GREEN("可放", Color(0xFF5FA777), "收兩種"),
    PURPLE("睡眠保護", Color(0xFFB39DDB), "放入要跳警告"),
    GREY_LOCKED("鎖定", Color(0xFF757575), "不可放"),
    GREY_TIGHT("行程緊湊", Color(0xFFA1887F), "放入要跳警告")
}

data class TimeGap(
    val id: String, // "dayIndex_slotIndex"
    val timeRange: String,
    val name: String,
    val semantic: GapSemantic,
    val isTight: Boolean = false
)

fun getGapsForDayType(dayTypeStr: String, dayIndex: Int): List<TimeGap> {
    val dayType = try {
        DayType.valueOf(dayTypeStr)
    } catch (e: Exception) {
        DayType.NON_SPORT
    }
    return when (dayType) {
        DayType.SPORT -> listOf(
            TimeGap("${dayIndex}_0", "07:10–08:00", "通勤後乾等", GapSemantic.AMBER),
            TimeGap("${dayIndex}_1", "09:00–17:30", "上班", GapSemantic.GREY_LOCKED),
            TimeGap("${dayIndex}_2", "17:30–20:00", "回家→團課前", GapSemantic.GREY_TIGHT, isTight = true),
            TimeGap("${dayIndex}_3", "20:00–21:00", "團課", GapSemantic.GREY_LOCKED),
            TimeGap("${dayIndex}_4", "21:00–睡", "下課洗澡睡", GapSemantic.PURPLE)
        )
        DayType.NON_SPORT -> listOf(
            TimeGap("${dayIndex}_0", "07:10–08:00", "乾等", GapSemantic.AMBER),
            TimeGap("${dayIndex}_1", "09:00–17:30", "上班", GapSemantic.GREY_LOCKED),
            TimeGap("${dayIndex}_2", "17:30–22:30", "晚上", GapSemantic.GREEN),
            TimeGap("${dayIndex}_3", "22:30–睡", "早睡還債", GapSemantic.PURPLE)
        )
        DayType.SATURDAY -> listOf(
            TimeGap("${dayIndex}_0", "上午", "可放", GapSemantic.GREEN),
            TimeGap("${dayIndex}_1", "中午–下午", "可放", GapSemantic.GREEN),
            TimeGap("${dayIndex}_2", "傍晚", "可放", GapSemantic.GREEN),
            TimeGap("${dayIndex}_3", "晚上→週日", "必睡窗口", GapSemantic.PURPLE)
        )
        DayType.SUNDAY -> listOf(
            TimeGap("${dayIndex}_0", "上午", "補眠緩衝", GapSemantic.GREEN),
            TimeGap("${dayIndex}_1", "下午", "可放", GapSemantic.GREEN),
            TimeGap("${dayIndex}_2", "晚上", "可放", GapSemantic.GREEN)
        )
    }
}

fun calculateDaysRemaining(dueDateStr: String?): Long {
    if (dueDateStr.isNullOrEmpty()) return Long.MAX_VALUE
    return try {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val dueDate = LocalDate.parse(dueDateStr, formatter)
        val today = LocalDate.now()
        ChronoUnit.DAYS.between(today, dueDate)
    } catch (e: Exception) {
        Long.MAX_VALUE
    }
}

fun getTodayIndex(): Int {
    val dayOfWeek = LocalDate.now().dayOfWeek
    // DayOfWeek in java.time: 1 (Monday) to 7 (Sunday)
    return dayOfWeek.value - 1
}

fun getDateStringForIndex(dayIndex: Int, weekOffset: Int = 0): String {
    val today = LocalDate.now()
    val todayDayOfWeekIndex = today.dayOfWeek.value - 1 // 0..6
    val targetDate = today.plusDays((dayIndex - todayDayOfWeekIndex + weekOffset * 7).toLong())
    val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    return targetDate.format(formatter)
}

fun getShortDateStringForIndex(dayIndex: Int, weekOffset: Int = 0): String {
    val today = LocalDate.now()
    val todayDayOfWeekIndex = today.dayOfWeek.value - 1 // 0..6
    val targetDate = today.plusDays((dayIndex - todayDayOfWeekIndex + weekOffset * 7).toLong())
    val formatter = DateTimeFormatter.ofPattern("M/d")
    return targetDate.format(formatter)
}

fun getFullDateStringForIndex(dayIndex: Int, weekOffset: Int = 0): String {
    val today = LocalDate.now()
    val todayDayOfWeekIndex = today.dayOfWeek.value - 1 // 0..6
    val targetDate = today.plusDays((dayIndex - todayDayOfWeekIndex + weekOffset * 7).toLong())
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    return targetDate.format(formatter)
}

// ==========================================
// VIEWMODEL
// ==========================================

class LuodiViewModel(private val dao: LuodiDao, private val context: android.content.Context) : ViewModel() {
    val googleAccount = MutableStateFlow<com.google.android.gms.auth.api.signin.GoogleSignInAccount?>(null)
    val isSyncing = MutableStateFlow(false)
    val syncStatusMessage = MutableStateFlow<String?>(null)

    val thoughts = dao.getAllThoughts().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val dayConfigs = dao.getAllDayConfigs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun checkLastSignedInAccount() {
        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
        googleAccount.value = account
        if (account != null) {
            triggerAutoSync()
        }
    }

    fun setGoogleAccount(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount?) {
        googleAccount.value = account
        if (account != null) {
            triggerAutoSync()
        }
    }

    fun triggerAutoSync() {
        val account = googleAccount.value ?: return
        viewModelScope.launch {
            isSyncing.value = true
            val token = GoogleDriveSyncHelper.getAccessToken(context, account)
            if (token != null) {
                val result = GoogleDriveSyncHelper.sync(context, token, dao)
                when (result) {
                    is GoogleDriveSyncHelper.SyncResult.Success -> {
                        syncStatusMessage.value = "雲端同步成功 ✨"
                    }
                    is GoogleDriveSyncHelper.SyncResult.Error -> {
                        syncStatusMessage.value = "同步失敗: ${result.message}"
                    }
                }
            } else {
                syncStatusMessage.value = "無法取得 Google 授權"
            }
            isSyncing.value = false
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            val client = GoogleDriveSyncHelper.getGoogleSignInClient(context)
            client.signOut().addOnCompleteListener {
                googleAccount.value = null
                syncStatusMessage.value = "已登出 Google 帳號"
            }
        }
    }

    fun clearSyncStatusMessage() {
        syncStatusMessage.value = null
    }

    init {
        checkLastSignedInAccount()

        viewModelScope.launch {
            if (dao.getAllDayConfigsOnce().isEmpty()) {
                val defaultConfigs = listOf(
                    DayConfig(0, "週一", "NON_SPORT"),
                    DayConfig(1, "週二", "SPORT"),
                    DayConfig(2, "週三", "NON_SPORT"),
                    DayConfig(3, "週四", "SPORT"),
                    DayConfig(4, "週五", "NON_SPORT"),
                    DayConfig(5, "週六", "SATURDAY"),
                    DayConfig(6, "週日", "SUNDAY")
                )
                dao.insertDayConfigs(defaultConfigs)
                LuodiAppWidgetProvider.updateWidgets(context)
            }
        }

        // Check and bounce uncompleted thoughts from past weeks
        viewModelScope.launch {
            bouncePastUncompletedThoughts()
        }

        // Automatic 24-hour Completed Thought Archiver
        viewModelScope.launch {
            thoughts.collect { list ->
                val now = System.currentTimeMillis()
                val overdueCompleted = list.filter {
                    it.status == "COMPLETED" && (it.completedAt == 0L || (now - it.completedAt) >= 24 * 60 * 60 * 1000L)
                }
                if (overdueCompleted.isNotEmpty()) {
                    overdueCompleted.forEach {
                        dao.updateThought(it.copy(status = "ARCHIVED"))
                    }
                    LuodiAppWidgetProvider.updateWidgets(context)
                }
            }
        }
    }

    private suspend fun bouncePastUncompletedThoughts() {
        try {
            val allThoughts = dao.getAllThoughtsOnce()
            val today = LocalDate.now()
            val todayDayOfWeekIndex = today.dayOfWeek.value - 1 // 0..6
            var updatedAny = false

            for (thought in allThoughts) {
                if (thought.status == "PLACED" || thought.status == "COMPLETED") {
                    if (thought.placedDate.isNullOrEmpty() && thought.placedDayIndex != null) {
                        // Populate legacy data to current week
                        val resolvedDateStr = today.plusDays((thought.placedDayIndex - todayDayOfWeekIndex).toLong())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        val updated = thought.copy(placedDate = resolvedDateStr)
                        dao.updateThought(updated)
                        // Continue check with the resolved date
                        val resolvedDate = LocalDate.parse(resolvedDateStr)
                        if (thought.status == "PLACED" && resolvedDate.isBefore(today)) {
                            val bounced = updated.copy(
                                status = "PENDING",
                                placedDayIndex = null,
                                placedSlotId = null,
                                placedDate = null
                            )
                            dao.updateThought(bounced)
                        }
                        updatedAny = true
                    } else if (!thought.placedDate.isNullOrEmpty()) {
                        val pDate = LocalDate.parse(thought.placedDate)
                        if (thought.status == "PLACED" && pDate.isBefore(today)) {
                            val bounced = thought.copy(
                                status = "PENDING",
                                placedDayIndex = null,
                                placedSlotId = null,
                                placedDate = null
                            )
                            dao.updateThought(bounced)
                            updatedAny = true
                        }
                    }
                }
            }
            if (updatedAny) {
                LuodiAppWidgetProvider.updateWidgets(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addThought(title: String, type: String, isDeadline: Boolean, dueDate: String?) {
        viewModelScope.launch {
            val thought = Thought(
                title = title,
                type = type,
                isDeadline = isDeadline,
                dueDate = if (isDeadline) dueDate else null,
                status = "PENDING"
            )
            dao.insertThought(thought)
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun placeThought(thoughtId: Int, dayIndex: Int, slotId: String, placedDate: String? = null) {
        viewModelScope.launch {
            thoughts.value.find { it.id == thoughtId }?.let { thought ->
                val updated = thought.copy(
                    status = "PLACED",
                    placedDayIndex = dayIndex,
                    placedSlotId = slotId,
                    placedDate = placedDate
                )
                dao.updateThought(updated)
                LuodiAppWidgetProvider.updateWidgets(context)
                triggerAutoSync()
            }
        }
    }

    fun removeThought(thought: Thought) {
        viewModelScope.launch {
            dao.deleteThought(thought)
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun completeThought(thought: Thought) {
        viewModelScope.launch {
            val updated = thought.copy(
                status = "COMPLETED",
                completedAt = System.currentTimeMillis()
            )
            dao.updateThought(updated)
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun archiveThought(thought: Thought) {
        viewModelScope.launch {
            val updated = thought.copy(status = "ARCHIVED")
            dao.updateThought(updated)
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun archiveAllCompletedThoughts() {
        viewModelScope.launch {
            val completedList = thoughts.value.filter { it.status == "COMPLETED" }
            completedList.forEach {
                dao.updateThought(it.copy(status = "ARCHIVED"))
            }
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun clearExpiredPendingThoughts() {
        viewModelScope.launch {
            val expiredList = thoughts.value.filter {
                it.status == "PENDING" && it.isDeadline && !it.dueDate.isNullOrEmpty() && calculateDaysRemaining(it.dueDate) < 0
            }
            expiredList.forEach {
                dao.deleteThought(it)
            }
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun movePendingThought(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = thoughts.value.filter { it.status == "PENDING" }.sortedWith(
                compareBy<Thought> { it.customOrder }
                    .thenByDescending { it.isDeadline }
                    .thenBy { calculateDaysRemaining(it.dueDate) }
                    .thenByDescending { it.createdAt }
            ).toMutableList()
            
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                list.forEachIndexed { index, thought ->
                    dao.updateThought(thought.copy(customOrder = index))
                }
                LuodiAppWidgetProvider.updateWidgets(context)
                triggerAutoSync()
            }
        }
    }

    fun returnThoughtToPending(thought: Thought) {
        viewModelScope.launch {
            val updated = thought.copy(
                status = "PENDING",
                placedDayIndex = null,
                placedSlotId = null,
                placedDate = null
            )
            dao.updateThought(updated)
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun updateDayConfig(dayIndex: Int, newType: String) {
        viewModelScope.launch {
            val dayName = when (dayIndex) {
                0 -> "週一"
                1 -> "週二"
                2 -> "週三"
                3 -> "週四"
                4 -> "週五"
                5 -> "週六"
                else -> "週日"
            }
            dao.updateDayConfig(DayConfig(dayIndex, dayName, newType))
            LuodiAppWidgetProvider.updateWidgets(context)
            triggerAutoSync()
        }
    }

    fun triggerBounceCheck() {
        viewModelScope.launch {
            bouncePastUncompletedThoughts()
            // We can also trigger a sync if bounce completed any updates
        }
    }

    fun movePlacedThought(currentPlaced: List<Thought>, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = currentPlaced.sortedBy { it.customOrder }.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                list.forEachIndexed { index, thought ->
                    dao.updateThought(thought.copy(customOrder = index))
                }
                LuodiAppWidgetProvider.updateWidgets(context)
                triggerAutoSync()
            }
        }
    }
}

class LuodiViewModelFactory(private val dao: LuodiDao, private val context: android.content.Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LuodiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LuodiViewModel(dao, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    private var quickAddTrigger by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.luodiDao()
        LuodiAppWidgetProvider.updateWidgets(applicationContext)

        if (intent?.getBooleanExtra("ACTION_QUICK_ADD", false) == true) {
            quickAddTrigger = true
        }

        setContent {
            // Calm Cozy Grounding Theme Palette
            val colorScheme = darkColorScheme(
                primary = Color(0xFF80CBB5),      // Serene Mint/Teal
                secondary = Color(0xFFC2B2F0),    // Soothing Lavender
                tertiary = Color(0xFFFFF59D),     // Soft warm yellow
                background = Color(0xFF121418),   // Deep night sky
                surface = Color(0xFF1B1E24),      // Dark slate card
                onPrimary = Color(0xFF0F2C24),
                onSecondary = Color(0xFF22183B),
                onBackground = Color(0xFFE2E8F0),
                onSurface = Color(0xFFF1F5F9)
            )

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LuodiApp(
                        dao = dao,
                        quickAddTrigger = quickAddTrigger,
                        onQuickAddHandled = { quickAddTrigger = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("ACTION_QUICK_ADD", false) == true) {
            quickAddTrigger = true
        }
    }
}

// ==========================================
// COMPOSABLES
// ==========================================

@Composable
fun ProgressRing(
    completedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val targetProgress = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    Box(
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E2623))
            .testTag("progress_ring_container"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val strokeWidth = 3.dp.toPx()
            // Track (background track)
            drawCircle(
                color = Color(0xFF2C323E).copy(alpha = 0.6f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
            // Progress Arc
            if (totalCount > 0) {
                drawArc(
                    color = Color(0xFF80CBB5),
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = strokeWidth,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }
        }

        // Inside layout (Number ratio or Check Icon)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (totalCount > 0 && completedCount == totalCount) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "今日任務已全數完成",
                    tint = Color(0xFF81C784),
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = "$completedCount/$totalCount",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun LuodiApp(
    dao: LuodiDao,
    quickAddTrigger: Boolean = false,
    onQuickAddHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: LuodiViewModel = viewModel(factory = LuodiViewModelFactory(dao, context.applicationContext))

    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    val dayConfigs by viewModel.dayConfigs.collectAsStateWithLifecycle()

    // Automatic bounce check when app is resumed/opened
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.triggerBounceCheck()
                viewModel.triggerAutoSync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var activeTab by remember { mutableStateOf(0) } // 0: 今天, 1: 整週
    var weekOffset by remember { mutableStateOf(0) }
    var selectedThoughtForPlacement by remember { mutableStateOf<Thought?>(null) }

    // Dialog state for warnings
    var showWarningDialog by remember { mutableStateOf(false) }
    var pendingPlacementTarget by remember { mutableStateOf<Pair<Int, String>?>(null) } // Pair(dayIndex, slotId)
    var warningDialogMessage by remember { mutableStateOf("") }

    // New thought input state
    var newThoughtTitle by remember { mutableStateOf("") }
    var newThoughtType by remember { mutableStateOf("REVIEW") } // "REVIEW" or "FOCUS"
    var isDeadline by remember { mutableStateOf(false) }
    var dueDate by remember { mutableStateOf("") }

    // Dropdown/Dialog for Day Type editing
    var editingDayConfigIndex by remember { mutableStateOf<Int?>(null) }

    var showSyncDialog by remember { mutableStateOf(false) }

    val googleAccount by viewModel.googleAccount.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatusMessage by viewModel.syncStatusMessage.collectAsStateWithLifecycle()

    LaunchedEffect(syncStatusMessage) {
        syncStatusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearSyncStatusMessage()
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                viewModel.setGoogleAccount(account)
                Toast.makeText(context, "Google 登入成功！已啟動自動同步", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Google 登入失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val todayIndex = getTodayIndex()

    val dragDropState = remember { DragDropState() }

    val todayScrollState = rememberLazyListState()
    val weeklyScrollState = rememberLazyListState()
    val pendingScrollState = rememberLazyListState()

    var isInputVisible by remember { mutableStateOf(true) }
    var accumulateDragDown by remember { mutableStateOf(0f) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(quickAddTrigger) {
        if (quickAddTrigger) {
            activeTab = 0
            isInputVisible = true
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onQuickAddHandled()
        }
    }

    LaunchedEffect(activeTab) {
        isInputVisible = true
        accumulateDragDown = 0f
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -5f) {
                    // Scrolling up (finger moving up) -> hide input, reset accumulated drag down
                    isInputVisible = false
                    accumulateDragDown = 0f
                } else if (delta > 5f) {
                    // Scrolling down (finger moving down) -> accumulate
                    accumulateDragDown += delta
                    // If accumulated drag down is large (e.g. > 450 pixels)
                    if (accumulateDragDown > 450f) {
                        isInputVisible = true
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                // If they swipe down with a very high speed (e.g. available.y > 2500)
                if (available.y > 2500f) {
                    isInputVisible = true
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    CompositionLocalProvider(LocalDragDropState provides dragDropState) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Dialog for warning
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = {
                showWarningDialog = false
                pendingPlacementTarget = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "警示",
                        tint = Color(0xFFFFA000),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "時間配置提醒",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Text(
                    text = warningDialogMessage,
                    color = Color(0xFFE2E8F0),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedThoughtForPlacement?.let { thought ->
                            pendingPlacementTarget?.let { (dayIdx, slotId) ->
                                val targetOffset = if (activeTab == 0) 0 else weekOffset
                                viewModel.placeThought(
                                    thoughtId = thought.id,
                                    dayIndex = dayIdx,
                                    slotId = slotId,
                                    placedDate = getFullDateStringForIndex(dayIdx, targetOffset)
                                )
                                Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showWarningDialog = false
                        selectedThoughtForPlacement = null
                        pendingPlacementTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80CBB5))
                ) {
                    Text("還是要放", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWarningDialog = false
                        pendingPlacementTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                ) {
                    Text("換一格", fontWeight = FontWeight.Medium)
                }
            },
            containerColor = Color(0xFF1B1E24),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Day Type Changer Dialog
    editingDayConfigIndex?.let { dayIdx ->
        AlertDialog(
            onDismissRequest = { editingDayConfigIndex = null },
            title = {
                Text(
                    text = "調整日型配置",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "請選擇這天的日常節奏：",
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    DayType.values().forEach { dType ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    viewModel.updateDayConfig(dayIdx, dType.name)
                                    editingDayConfigIndex = null
                                    Toast
                                        .makeText(context, "日型已更新", Toast.LENGTH_SHORT)
                                        .show()
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF272C36)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dType.label,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (dayConfigs.find { it.dayIndex == dayIdx }?.dayType == dType.name) Color(0xFF80CBB5) else Color.Transparent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingDayConfigIndex = null }) {
                    Text("取消", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1B1E24),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Google Cloud Sync Dialog
    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Color(0xFF80CBB5)
                    )
                    Text(
                        text = "落念雲端同步",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (googleAccount == null) {
                        Text(
                            text = "安全地備份與跨端同步您的念頭！",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "登入 Google 帳號後，系統會將您的念頭與日型配置安全地儲存至您個人 Google 雲端硬碟的專屬隱私 App 應用程式資料夾（App Data folder）中。\n\n• 🔒 絕對隱私：其他應用程式或人都無法存取此隱私資料夾，100% 安全。\n• ☁️ 零記憶體：幾乎不佔用您可見的雲端儲存空間，對雲端硬碟無負擔。\n• 💻 網頁同步：在網頁 Companion App 登入同個帳號，即能一秒同步您的「腦海中的念頭」待排區！\n• 🔄 背景自動：開啟與回到 App 時會自動執行雙向背景同步。",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val signInIntent = GoogleDriveSyncHelper.getGoogleSignInClient(context).signInIntent
                                googleSignInLauncher.launch(signInIntent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBB5)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "登入 Google 帳號並啟用",
                                color = Color(0xFF121418),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        // Logged in UI
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF272C36)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF80CBB5),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = googleAccount?.displayName ?: "Google 使用者",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Text(
                                    text = googleAccount?.email ?: "",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Text(
                            text = "• 🔄 自動背景同步已啟用：系統將自動備份念頭。\n• 💻 您可以使用電腦網頁 Companion App 進行念頭同步與排序！",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { viewModel.triggerAutoSync() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBB5)),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFF121418),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color(0xFF121418),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "手動立即雙向同步",
                                        color = Color(0xFF121418),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = { viewModel.signOutGoogle() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "登出 Google 帳號",
                                color = Color(0xFFEF5350),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncDialog = false }) {
                    Text("關閉", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1B1E24),
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF121418), Color(0xFF1B1E24))
                    )
                )
                .nestedScroll(nestedScrollConnection)
        ) {
            // Calm App Title Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isInputVisible = !isInputVisible }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "落地",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    val pendingCount = thoughts.count { it.status == "PENDING" }
                    val (brainLoadText, loadColor) = when {
                        pendingCount == 0 -> Pair("大腦已清空，安然入眠 🧘", Color(0xFF81C784))
                        pendingCount in 1..5 -> Pair("大腦微載，正是整理思緒的好時機 ✨", Color(0xFF80CBB5))
                        pendingCount in 6..10 -> Pair("大腦中載，將念頭安放到時間縫隙吧 🧠", Color(0xFFFFA000))
                        else -> Pair("大腦超載！深呼吸，把它們排入課表吧 🌊", Color(0xFFEF5350))
                    }
                    Text(
                        text = brainLoadText,
                        color = loadColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Google Cloud Sync Status / Button
                    IconButton(
                        onClick = { showSyncDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF80CBB5),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (googleAccount != null) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                                contentDescription = "雲端同步",
                                tint = if (googleAccount != null) Color(0xFF80CBB5) else Color(0xFF94A3B8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Today's Progress Ring
                    val todayThoughts = thoughts.filter { it.placedDayIndex == todayIndex }
                    val todayCompleted = todayThoughts.count { it.status == "COMPLETED" }
                    val todayTotal = todayThoughts.size
                    ProgressRing(
                        completedCount = todayCompleted,
                        totalCount = todayTotal
                    )
                }
            }

            // Zero-Friction Thought Input Card
            AnimatedVisibility(
                visible = isInputVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222B)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF2D333F))
                ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Title Input Box
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newThoughtTitle,
                            onValueChange = { newThoughtTitle = it },
                            placeholder = { Text("寫下當下的念頭...", color = Color(0xFF64748B)) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .testTag("thought_title_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF80CBB5),
                                unfocusedBorderColor = Color(0xFF2C323E),
                                focusedContainerColor = Color(0xFF13161C),
                                unfocusedContainerColor = Color(0xFF13161C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                if (newThoughtTitle.isNotEmpty()) {
                                    IconButton(onClick = { newThoughtTitle = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清除", tint = Color(0xFF64748B))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Row of selectors: Type & Urgency
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Type Selector (零碎型 / 需專注)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1.0f)
                        ) {
                            Text("類型: ", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            val isReview = newThoughtType == "REVIEW"
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF13161C))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isReview) Color(0xFFFFA000).copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { newThoughtType = "REVIEW" }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "零碎",
                                        color = if (isReview) Color(0xFFFFA000) else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isReview) Color(0xFF43A047).copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { newThoughtType = "FOCUS" }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "專注",
                                        color = if (!isReview) Color(0xFF43A047) else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Urgency Toggle Selector (普通 / 卡死線)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1.0f),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text("時限: ", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF13161C))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isDeadline) Color(0xFF2C323E) else Color.Transparent)
                                        .clickable { isDeadline = false }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "普通",
                                        color = if (!isDeadline) Color.White else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isDeadline) Color(0xFFEF5350).copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { isDeadline = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "死線",
                                        color = if (isDeadline) Color(0xFFEF5350) else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Optional Deadline picker
                    AnimatedVisibility(
                        visible = isDeadline,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF13161C), shape = RoundedCornerShape(10.dp))
                                .clickable {
                                    val calendar = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            dueDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = "選擇死線日期",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (dueDate.isEmpty()) "點此選擇死線日期 (可留空)" else "死線日期：$dueDate",
                                    color = if (dueDate.isEmpty()) Color(0xFF94A3B8) else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (dueDate.isNotEmpty()) {
                                IconButton(
                                    onClick = { dueDate = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清除日期",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Submit Button
                    Button(
                        onClick = {
                            if (newThoughtTitle.isBlank()) {
                                Toast.makeText(context, "請輸入念頭內容", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            viewModel.addThought(
                                title = newThoughtTitle,
                                type = newThoughtType,
                                isDeadline = isDeadline,
                                dueDate = if (isDeadline && dueDate.isNotEmpty()) dueDate else null
                            )
                            // Reset
                            newThoughtTitle = ""
                            isDeadline = false
                            dueDate = ""
                            Toast.makeText(context, "已丟入腦海待排", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("add_thought_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBB5)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color(0xFF0F2C24),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "丟進待排清單",
                            color = Color(0xFF0F2C24),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Buttons: 今天 | 整週
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E222B))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (activeTab == 0) Color(0xFF2C323E) else Color.Transparent)
                        .clickable { activeTab = 0 }
                        .testTag("tab_today")
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "今天縫隙",
                        color = if (activeTab == 0) Color.White else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (activeTab == 1) Color(0xFF2C323E) else Color.Transparent)
                        .clickable { activeTab = 1 }
                        .testTag("tab_week")
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "整週縫隙",
                        color = if (activeTab == 1) Color.White else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Gaps Area
            Box(modifier = Modifier.weight(1.0f)) {
                if (dayConfigs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF80CBB5))
                    }
                } else {
                    if (activeTab == 0) {
                        // TODAY VIEW
                        val todayConfig = dayConfigs.find { it.dayIndex == todayIndex } ?: dayConfigs[0]
                        val todayDate = getFullDateStringForIndex(todayConfig.dayIndex, 0)
                        TodayView(
                            dayConfig = todayConfig,
                            thoughts = thoughts,
                            selectedThought = selectedThoughtForPlacement,
                            onSlotClick = { slot ->
                                val thought = selectedThoughtForPlacement ?: return@TodayView
                                handleSlotClick(
                                    thought = thought,
                                    dayIndex = todayConfig.dayIndex,
                                    gap = slot,
                                    onPlaceDirectly = {
                                        viewModel.placeThought(
                                            thoughtId = thought.id,
                                            dayIndex = todayConfig.dayIndex,
                                            slotId = slot.id,
                                            placedDate = todayDate
                                        )
                                        selectedThoughtForPlacement = null
                                        Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                                    },
                                    onWarn = { msg ->
                                        warningDialogMessage = msg
                                        pendingPlacementTarget = Pair(todayConfig.dayIndex, slot.id)
                                        showWarningDialog = true
                                    },
                                    onFail = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            onThoughtComplete = { viewModel.completeThought(it) },
                            onThoughtUndo = { viewModel.returnThoughtToPending(it) },
                            onChangeDayType = { editingDayConfigIndex = todayConfig.dayIndex },
                            scrollState = todayScrollState,
                            onDragStart = { selectedThoughtForPlacement = it },
                            onReorderPlaced = { list, fromIdx, toIdx ->
                                viewModel.movePlacedThought(list, fromIdx, toIdx)
                            },
                            onDropPlaced = { targetThought, dayIdx, slotId ->
                                val config = dayConfigs.find { it.dayIndex == dayIdx }
                                if (config != null) {
                                    val gaps = getGapsForDayType(config.dayType, config.dayIndex)
                                    val slot = gaps.find { it.id == slotId }
                                    if (slot != null) {
                                        handleSlotClick(
                                            thought = targetThought,
                                            dayIndex = dayIdx,
                                            gap = slot,
                                            onPlaceDirectly = {
                                                viewModel.placeThought(
                                                    thoughtId = targetThought.id,
                                                    dayIndex = dayIdx,
                                                    slotId = slot.id,
                                                    placedDate = getFullDateStringForIndex(dayIdx, 0)
                                                )
                                                selectedThoughtForPlacement = null
                                                Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                                            },
                                            onWarn = { msg ->
                                                warningDialogMessage = msg
                                                pendingPlacementTarget = Pair(dayIdx, slot.id)
                                                selectedThoughtForPlacement = targetThought
                                                showWarningDialog = true
                                            },
                                            onFail = { err ->
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    } else {
                        // WEEK VIEW (7 DAYS)
                        WeeklyView(
                            dayConfigs = dayConfigs,
                            thoughts = thoughts,
                            selectedThought = selectedThoughtForPlacement,
                            weekOffset = weekOffset,
                            onWeekOffsetChange = { weekOffset = it },
                            onSlotClick = { dayIdx, slot ->
                                val thought = selectedThoughtForPlacement ?: return@WeeklyView
                                handleSlotClick(
                                    thought = thought,
                                    dayIndex = dayIdx,
                                    gap = slot,
                                    onPlaceDirectly = {
                                        viewModel.placeThought(
                                            thoughtId = thought.id,
                                            dayIndex = dayIdx,
                                            slotId = slot.id,
                                            placedDate = getFullDateStringForIndex(dayIdx, weekOffset)
                                        )
                                        selectedThoughtForPlacement = null
                                        Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                                    },
                                    onWarn = { msg ->
                                        warningDialogMessage = msg
                                        pendingPlacementTarget = Pair(dayIdx, slot.id)
                                        showWarningDialog = true
                                    },
                                    onFail = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            onThoughtComplete = { viewModel.completeThought(it) },
                            onThoughtUndo = { viewModel.returnThoughtToPending(it) },
                            onChangeDayType = { editingDayConfigIndex = it },
                            scrollState = weeklyScrollState,
                            onDragStart = { selectedThoughtForPlacement = it },
                            onReorderPlaced = { list, fromIdx, toIdx ->
                                viewModel.movePlacedThought(list, fromIdx, toIdx)
                            },
                            onDropPlaced = { targetThought, dayIdx, slotId ->
                                val config = dayConfigs.find { it.dayIndex == dayIdx }
                                if (config != null) {
                                    val gaps = getGapsForDayType(config.dayType, config.dayIndex)
                                    val slot = gaps.find { it.id == slotId }
                                    if (slot != null) {
                                        handleSlotClick(
                                            thought = targetThought,
                                            dayIndex = dayIdx,
                                            gap = slot,
                                            onPlaceDirectly = {
                                                viewModel.placeThought(
                                                    thoughtId = targetThought.id,
                                                    dayIndex = dayIdx,
                                                    slotId = slot.id,
                                                    placedDate = getFullDateStringForIndex(dayIdx, weekOffset)
                                                )
                                                selectedThoughtForPlacement = null
                                                Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                                            },
                                            onWarn = { msg ->
                                                warningDialogMessage = msg
                                                pendingPlacementTarget = Pair(dayIdx, slot.id)
                                                selectedThoughtForPlacement = targetThought
                                                showWarningDialog = true
                                            },
                                            onFail = { err ->
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            },
                            viewModel = viewModel
                        )
                    }
                }
            }

            // Pending thoughts title divider
            val pendingThoughts = thoughts.filter { it.status == "PENDING" }.sortedWith(
                compareBy<Thought> { it.customOrder }
                    .thenByDescending { it.isDeadline }
                    .thenBy { calculateDaysRemaining(it.dueDate) }
                    .thenByDescending { it.createdAt }
            )

            // FeasibilityIndicatorBanner removed as requested

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161920)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = BorderStroke(1.dp, Color(0xFF2C323E))
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🧠 腦海中的念頭",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF2D3342))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${pendingThoughts.size}",
                                    color = Color(0xFF80CBB5),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        // Compact button for "清空無用" on the right side
                        Button(
                            onClick = {
                                viewModel.clearExpiredPendingThoughts()
                                Toast.makeText(context, "已清空過期待排念頭", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2424)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "一鍵清空過期待排",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "清空無用",
                                    color = Color(0xFFEF5350),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (pendingThoughts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "完全清空",
                                    tint = Color(0xFF80CBB5).copy(alpha = 0.3f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "腦中空無一物，是一片好地方",
                                    color = Color(0xFF64748B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = pendingScrollState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(pendingThoughts, key = { it.id }) { thought ->
                                DraggableThought(
                                    thought = thought,
                                    onDragStart = {
                                        selectedThoughtForPlacement = thought
                                    },
                                    onReorder = { fromId, toId ->
                                        val fromIdx = pendingThoughts.indexOfFirst { it.id == fromId }
                                        val toIdx = pendingThoughts.indexOfFirst { it.id == toId }
                                        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                            viewModel.movePendingThought(fromIdx, toIdx)
                                        }
                                    },
                                    onDrop = { dayIdx, slotId ->
                                        val config = dayConfigs.find { it.dayIndex == dayIdx }
                                        if (config != null) {
                                            val gaps = getGapsForDayType(config.dayType, config.dayIndex)
                                            val slot = gaps.find { it.id == slotId }
                                            if (slot != null) {
                                                handleSlotClick(
                                                    thought = thought,
                                                    dayIndex = dayIdx,
                                                    gap = slot,
                                                    onPlaceDirectly = {
                                                        val targetOffset = if (activeTab == 0) 0 else weekOffset
                                                         viewModel.placeThought(
                                                             thoughtId = thought.id,
                                                             dayIndex = dayIdx,
                                                             slotId = slot.id,
                                                             placedDate = getFullDateStringForIndex(dayIdx, targetOffset)
                                                         )
                                                        selectedThoughtForPlacement = null
                                                        Toast.makeText(context, "念頭已安放", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onWarn = { msg ->
                                                        warningDialogMessage = msg
                                                        pendingPlacementTarget = Pair(dayIdx, slot.id)
                                                        showWarningDialog = true
                                                    },
                                                    onFail = { err ->
                                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    PendingThoughtCard(
                                        thought = thought,
                                        isSelected = selectedThoughtForPlacement?.id == thought.id,
                                        onClick = {
                                            selectedThoughtForPlacement = if (selectedThoughtForPlacement?.id == thought.id) {
                                                null
                                            } else {
                                                thought
                                            }
                                        },
                                        onDelete = {
                                            viewModel.removeThought(thought)
                                            if (selectedThoughtForPlacement?.id == thought.id) {
                                                selectedThoughtForPlacement = null
                                            }
                                            Toast.makeText(context, "念頭已清除", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Floating drag preview
    val draggedThought = dragDropState.draggedThought
    if (draggedThought != null) {
        val topLeft = dragDropState.dragPosition - dragDropState.dragStartOffset
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        topLeft.x.roundToInt(),
                        topLeft.y.roundToInt()
                    )
                }
                .width(300.dp)
                .graphicsLayer {
                    alpha = 0.85f
                    scaleX = 1.05f
                    scaleY = 1.05f
                    shadowElevation = 12.dp.toPx()
                }
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222530)),
                border = BorderStroke(1.5.dp, Color(0xFF80CBB5)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (draggedThought.type == "REVIEW") Color(0xFFFFA000) else Color(0xFF43A047))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = draggedThought.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
}
}

// ==========================================
// COMPONENT: TODAY VIEW
// ==========================================

@Composable
fun TodayView(
    dayConfig: DayConfig,
    thoughts: List<Thought>,
    selectedThought: Thought?,
    onSlotClick: (TimeGap) -> Unit,
    onThoughtComplete: (Thought) -> Unit,
    onThoughtUndo: (Thought) -> Unit,
    onChangeDayType: () -> Unit,
    scrollState: LazyListState,
    onDragStart: (Thought) -> Unit,
    onReorderPlaced: (List<Thought>, Int, Int) -> Unit,
    onDropPlaced: (Thought, Int, String) -> Unit
) {
    val gaps = getGapsForDayType(dayConfig.dayType, dayConfig.dayIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Today Title Panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = getDateStringForIndex(dayConfig.dayIndex),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = DayType.valueOf(dayConfig.dayType).label,
                    color = Color(0xFF80CBB5),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFF1E2623), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            
            TextButton(
                onClick = onChangeDayType,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC2B2F0)),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("day_type_selector_day_${dayConfig.dayIndex}")
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("改日型", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(gaps) { gap ->
                val targetDate = getFullDateStringForIndex(dayConfig.dayIndex, 0)
                val gapThoughts = thoughts.filter {
                    (it.status == "PLACED" || it.status == "COMPLETED") &&
                    (it.placedDate == targetDate || (it.placedDate == null && it.placedDayIndex == dayConfig.dayIndex)) &&
                    it.placedSlotId == gap.id
                }.sortedBy { it.customOrder }

                TimeGapCard(
                    dayIndex = dayConfig.dayIndex,
                    gap = gap,
                    placedThoughts = gapThoughts,
                    selectedThought = selectedThought,
                    onSlotClick = { onSlotClick(gap) },
                    onThoughtComplete = onThoughtComplete,
                    onThoughtUndo = onThoughtUndo,
                    onDragStart = onDragStart,
                    onReorder = { fromIdx, toIdx -> onReorderPlaced(gapThoughts, fromIdx, toIdx) },
                    onDrop = { targetThought, dayIdx, slotId -> onDropPlaced(targetThought, dayIdx, slotId) }
                )
            }
        }
    }
}

// ==========================================
// COMPONENT: WEEKLY VIEW
// ==========================================

@Composable
fun DayTabItem(
    config: DayConfig,
    isSelected: Boolean,
    placedCount: Int,
    isToday: Boolean,
    weekOffset: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dragDropState = LocalDragDropState.current
    val key = "tab_${config.dayIndex}"

    if (dragDropState != null) {
        DisposableEffect(key) {
            onDispose {
                dragDropState.removeBounds(key)
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                if (dragDropState != null && coordinates.isAttached) {
                    val position = coordinates.positionInRoot()
                    val size = coordinates.size
                    dragDropState.updateBounds(
                        key,
                        LayoutBounds(
                            left = position.x,
                            top = position.y,
                            right = position.x + size.width,
                            bottom = position.y + size.height
                        )
                    )
                }
            }
    ) {
        // Tab background and text container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) Color(0xFF80CBB5) else Color(0xFF1E222B))
                .border(
                    width = 1.dp,
                    color = if (isSelected) Color(0xFF80CBB5) else if (isToday) Color(0xFF80CBB5).copy(alpha = 0.5f) else Color(0xFF2C323E),
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = config.dayName,
                    color = if (isSelected) Color(0xFF0F2C24) else if (isToday) Color(0xFF80CBB5) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = getShortDateStringForIndex(config.dayIndex, weekOffset),
                    color = if (isSelected) Color(0xFF0F2C24).copy(alpha = 0.8f) else Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Placed count badge floating on top-right
        if (placedCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF0F2C24) else Color(0xFF80CBB5)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = placedCount.toString(),
                    color = if (isSelected) Color(0xFF80CBB5) else Color(0xFF0F2C24),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    )
                )
            }
        }
    }
}

@Composable
fun WeeklyView(
    dayConfigs: List<DayConfig>,
    thoughts: List<Thought>,
    selectedThought: Thought?,
    weekOffset: Int,
    onWeekOffsetChange: (Int) -> Unit,
    onSlotClick: (Int, TimeGap) -> Unit,
    onThoughtComplete: (Thought) -> Unit,
    onThoughtUndo: (Thought) -> Unit,
    onChangeDayType: (Int) -> Unit,
    scrollState: LazyListState,
    onDragStart: (Thought) -> Unit,
    onReorderPlaced: (List<Thought>, Int, Int) -> Unit,
    onDropPlaced: (Thought, Int, String) -> Unit,
    viewModel: LuodiViewModel
) {
    var selectedTabDayIndex by remember { mutableStateOf(getTodayIndex()) }
    val dragDropState = LocalDragDropState.current
    var totalDragX by remember { mutableStateOf(0f) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val swipeThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }
    val currentWeekOffset by androidx.compose.runtime.rememberUpdatedState(weekOffset)
    val currentOnWeekOffsetChange by androidx.compose.runtime.rememberUpdatedState(onWeekOffsetChange)

    if (dragDropState != null && dragDropState.draggedThought != null) {
        val hoveredTab = dragDropState.findHoveredTab()
        LaunchedEffect(hoveredTab) {
            if (hoveredTab != null && hoveredTab != selectedTabDayIndex) {
                selectedTabDayIndex = hoveredTab
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Horizontal tab row for 7 days - Swipe gestures applied strictly here
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (totalDragX > swipeThresholdPx) {
                                currentOnWeekOffsetChange(currentWeekOffset - 1)
                            } else if (totalDragX < -swipeThresholdPx) {
                                currentOnWeekOffsetChange(currentWeekOffset + 1)
                            }
                            totalDragX = 0f
                        },
                        onDragCancel = {
                            totalDragX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDragX += dragAmount
                        }
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            dayConfigs.forEach { config ->
                val dayIndex = config.dayIndex
                val targetDate = getFullDateStringForIndex(dayIndex, weekOffset)
                val placedCount = thoughts.count {
                    it.status == "PLACED" && (
                        it.placedDate == targetDate || (it.placedDate == null && weekOffset == 0 && it.placedDayIndex == dayIndex)
                    )
                }
                DayTabItem(
                    config = config,
                    isSelected = selectedTabDayIndex == dayIndex,
                    placedCount = placedCount,
                    isToday = dayIndex == getTodayIndex() && weekOffset == 0,
                    weekOffset = weekOffset,
                    onClick = { selectedTabDayIndex = dayIndex },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Show the selected day's content card
        val selectedConfig = dayConfigs.find { it.dayIndex == selectedTabDayIndex } ?: dayConfigs.getOrNull(0)
        if (selectedConfig != null) {
            val config = selectedConfig
            val dayIndex = config.dayIndex
            val gaps = getGapsForDayType(config.dayType, config.dayIndex)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222B)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (dayIndex == getTodayIndex() && weekOffset == 0) Color(0xFF80CBB5).copy(alpha = 0.5f) else Color(0xFF2C323E))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header of Day Card
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = DayType.valueOf(config.dayType).label,
                                color = if (dayIndex == getTodayIndex() && weekOffset == 0) Color(0xFF80CBB5) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            if (dayIndex == getTodayIndex() && weekOffset == 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "今天",
                                    color = Color(0xFF0F2C24),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .background(Color(0xFF80CBB5), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            if (weekOffset != 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                val weekLabel = when (weekOffset) {
                                    -1 -> "上週"
                                    1 -> "下週"
                                    else -> if (weekOffset < 0) "前 ${-weekOffset} 週" else "後 ${weekOffset} 週"
                                }
                                Text(
                                    text = weekLabel,
                                    color = Color(0xFFC2B2F0),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .background(Color(0xFFC2B2F0).copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (weekOffset != 0) {
                                Text(
                                    text = "返回本週",
                                    color = Color(0xFF0F2C24),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xFF80CBB5), shape = RoundedCornerShape(4.dp))
                                        .clickable { onWeekOffsetChange(0) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            // Change day type action button
                            TextButton(
                                onClick = { onChangeDayType(dayIndex) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC2B2F0)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("day_type_selector_day_$dayIndex")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("更改日型", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        state = scrollState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 450.dp)
                    ) {
                        items(gaps) { gap ->
                            val targetDate = getFullDateStringForIndex(dayIndex, weekOffset)
                            val gapThoughts = thoughts.filter {
                                (it.status == "PLACED" || it.status == "COMPLETED") &&
                                (it.placedDate == targetDate || (it.placedDate == null && weekOffset == 0 && it.placedDayIndex == dayIndex)) &&
                                it.placedSlotId == gap.id
                            }.sortedBy { it.customOrder }

                            TimeGapCard(
                                dayIndex = dayIndex,
                                gap = gap,
                                placedThoughts = gapThoughts,
                                selectedThought = selectedThought,
                                onSlotClick = { onSlotClick(dayIndex, gap) },
                                onThoughtComplete = onThoughtComplete,
                                onThoughtUndo = onThoughtUndo,
                                onDragStart = onDragStart,
                                onReorder = { fromIdx, toIdx -> onReorderPlaced(gapThoughts, fromIdx, toIdx) },
                                onDrop = { targetThought, dayIdx, slotId -> onDropPlaced(targetThought, dayIdx, slotId) }
                            )
                        }
                    }
                }
            }
        }
    }
}



// ==========================================
// COMPONENT: TIME GAP CARD
// ==========================================

@Composable
fun TimeGapCard(
    dayIndex: Int,
    gap: TimeGap,
    placedThoughts: List<Thought>,
    selectedThought: Thought?,
    onSlotClick: () -> Unit,
    onThoughtComplete: (Thought) -> Unit,
    onThoughtUndo: (Thought) -> Unit,
    onDragStart: (Thought) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDrop: (Thought, Int, String) -> Unit
) {
    val dragDropState = LocalDragDropState.current
    val key = "${dayIndex}_${gap.id}"

    if (dragDropState != null) {
        DisposableEffect(key) {
            onDispose {
                dragDropState.removeBounds(key)
            }
        }
    }

    val isPlacementMode = selectedThought != null
    
    // Check compatibility
    val isEligibleGlow = if (isPlacementMode) {
        if (selectedThought!!.type == "REVIEW") {
            gap.semantic == GapSemantic.AMBER || gap.semantic == GapSemantic.GREEN
        } else {
            gap.semantic == GapSemantic.GREEN
        }
    } else {
        false
    }

    val isWarnSlot = gap.semantic == GapSemantic.PURPLE || gap.isTight
    val isLocked = gap.semantic == GapSemantic.GREY_LOCKED

    // Pulsing glow border for compatible slots
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Check if hovered
    val isHovered = dragDropState?.findHoveredTarget()?.let { (dayIdx, slotId) ->
        dayIdx == dayIndex && slotId == gap.id
    } ?: false

    val borderStroke = when {
        isHovered && isEligibleGlow -> BorderStroke(3.dp, gap.semantic.color)
        isPlacementMode && isEligibleGlow -> BorderStroke(2.dp, gap.semantic.color.copy(alpha = glowAlpha))
        isPlacementMode && isWarnSlot -> BorderStroke(1.dp, gap.semantic.color.copy(alpha = 0.4f))
        else -> BorderStroke(1.dp, Color(0xFF2C323E))
    }

    val containerColor = when {
        isHovered && isEligibleGlow -> gap.semantic.color.copy(alpha = 0.25f)
        isPlacementMode && isEligibleGlow -> gap.semantic.color.copy(alpha = 0.15f)
        isPlacementMode && isLocked -> Color(0xFF13151A).copy(alpha = 0.4f)
        else -> Color(0xFF1B1E24)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slot_click_${dayIndex}_${gap.id.split("_").last()}")
            .onGloballyPositioned { coordinates ->
                if (dragDropState != null && coordinates.isAttached) {
                    val position = coordinates.positionInRoot()
                    val size = coordinates.size
                    dragDropState.updateBounds(
                        key,
                        LayoutBounds(
                            left = position.x,
                            top = position.y,
                            right = position.x + size.width,
                            bottom = position.y + size.height
                        )
                    )
                }
            }
            .clickable(enabled = isPlacementMode && !isLocked, onClick = onSlotClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = borderStroke,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Time and Name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Semantic Color Indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(gap.semantic.color)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = gap.timeRange,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = gap.name,
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Right: Semantic Tag or Action icon
                if (isPlacementMode) {
                    if (isEligibleGlow) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("可安放", color = gap.semantic.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = gap.semantic.color,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (isWarnSlot) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (gap.semantic == GapSemantic.PURPLE) "睡眠警告" else "緊湊警告",
                                color = gap.semantic.color,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = gap.semantic.color,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else {
                        Text(
                            text = if (isLocked) "鎖定" else "不符類型",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                } else {
                    // Regular Mode semantic tag
                    Text(
                        text = gap.semantic.label,
                        color = gap.semantic.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(gap.semantic.color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Placed thoughts nested inside
            if (placedThoughts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    placedThoughts.forEach { thought ->
                        val isCompleted = thought.status == "COMPLETED"
                        DraggableThought(
                            thought = thought,
                            onDragStart = { onDragStart(thought) },
                            onReorder = { fromId, toId ->
                                val fromIdx = placedThoughts.indexOfFirst { it.id == fromId }
                                val toIdx = placedThoughts.indexOfFirst { it.id == toId }
                                if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                    onReorder(fromIdx, toIdx)
                                }
                            },
                            onDrop = { dayIdx, slotId ->
                                onDrop(thought, dayIdx, slotId)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val bg = if (isCompleted) Color(0xFF132A1F) else Color(0xFF272C36)
                            val border = if (isCompleted) BorderStroke(1.dp, Color(0xFF2E7D32)) else BorderStroke(1.dp, Color(0xFF373F4F))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("placed_thought_item_${thought.id}")
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .border(border, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1.0f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (thought.type == "REVIEW") Icons.Default.Search else Icons.Default.Create,
                                        contentDescription = null,
                                        tint = if (thought.type == "REVIEW") Color(0xFFFFA000) else Color(0xFF43A047),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = thought.title,
                                        color = if (isCompleted) Color(0xFF94A3B8) else Color.White,
                                        fontSize = 13.sp,
                                        textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isCompleted) {
                                        // Mark complete icon button
                                        IconButton(
                                            onClick = { onThoughtComplete(thought) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "標記完成",
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }

                                    // Undo/Return to pending button
                                    IconButton(
                                        onClick = { onThoughtUndo(thought) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "退回待排",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isPlacementMode && placedThoughts.isEmpty() && !isLocked) {
                // Show clean hint
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "（點待排清單安放念頭於此縫隙）",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
        }
    }
}

// ==========================================
// COMPONENT: PENDING THOUGHT CARD
// ==========================================

@Composable
fun PendingThoughtCard(
    thought: Thought,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val remainingDays = if (thought.isDeadline) calculateDaysRemaining(thought.dueDate) else Long.MAX_VALUE
    val isOverdue = remainingDays < 0
    val isDueToday = remainingDays == 0L

    val borderStroke = if (isSelected) {
        BorderStroke(2.dp, Color(0xFF80CBB5))
    } else if (thought.isDeadline) {
        BorderStroke(1.5.dp, Color(0xFFEF5350)) // Red border for deadlines
    } else {
        BorderStroke(1.dp, Color(0xFF2C323E))
    }

    val backgroundColor = if (isSelected) Color(0xFF23302C) else Color(0xFF1B1E24)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("pending_thought_item_${thought.id}")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = borderStroke,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type indicator dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (thought.type == "REVIEW") Color(0xFFFFA000) else Color(0xFF43A047))
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = thought.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Type tag
                    Text(
                        text = if (thought.type == "REVIEW") "零碎型" else "需專注",
                        color = if (thought.type == "REVIEW") Color(0xFFFFA000) else Color(0xFF43A047),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                color = if (thought.type == "REVIEW") Color(0xFFFFA000).copy(alpha = 0.15f) else Color(0xFF43A047).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    
                    if (thought.isDeadline) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val deadlineText = when {
                            isOverdue -> "已過期 ${-remainingDays} 天"
                            isDueToday -> "今天到期"
                            remainingDays == Long.MAX_VALUE -> "卡死線 (未設日期)"
                            else -> "剩 ${remainingDays} 天"
                        }
                        Text(
                            text = deadlineText,
                            color = Color(0xFFEF5350),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0xFFEF5350).copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "刪除念頭",
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ==========================================
// INTERACTIVE ACTION HANDLER
// ==========================================

fun handleSlotClick(
    thought: Thought,
    dayIndex: Int,
    gap: TimeGap,
    onPlaceDirectly: () -> Unit,
    onWarn: (String) -> Unit,
    onFail: (String) -> Unit
) {
    val isLocked = gap.semantic == GapSemantic.GREY_LOCKED
    if (isLocked) {
        onFail("此時間段已被鎖定，無法安放任務喔。")
        return
    }

    // Check Type compatibility
    val isTypeCompatible = if (thought.type == "REVIEW") {
        gap.semantic == GapSemantic.AMBER || gap.semantic == GapSemantic.GREEN || gap.semantic == GapSemantic.PURPLE || gap.isTight
    } else {
        gap.semantic == GapSemantic.GREEN || gap.semantic == GapSemantic.PURPLE || gap.isTight
    }

    if (!isTypeCompatible) {
        onFail("這個縫隙不適合此類型的念頭（零碎型只收琥珀與綠色縫隙，專注型只收綠色縫隙）")
        return
    }

    // Check Warnings
    if (gap.semantic == GapSemantic.PURPLE) {
        onWarn("這會吃到你的睡眠，換一格／還是要放？")
    } else if (gap.isTight) {
        onWarn("這個縫隙行程非常緊湊，換一格／還是要放？")
    } else {
        onPlaceDirectly()
    }
}

// ==========================================
// COMPONENT: FEASIBILITY INDICATOR BANNER
// ==========================================

@Composable
fun FeasibilityIndicatorBanner(
    thoughts: List<Thought>,
    dayConfigs: List<DayConfig>
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val today = LocalDate.now()

    // 1. 取出所有「未放入、未完成、卡死線、且有 due」的念頭
    val deadlineThoughts = thoughts.filter {
        it.status == "PENDING" && it.isDeadline && !it.dueDate.isNullOrEmpty()
    }

    if (deadlineThoughts.isEmpty()) return

    // 2. 找出其中最近的 due（nearest）
    val nearestDate = deadlineThoughts.mapNotNull {
        try {
            LocalDate.parse(it.dueDate, formatter)
        } catch (e: Exception) {
            null
        }
    }.minOrNull() ?: return

    val dleft = ChronoUnit.DAYS.between(today, nearestDate)

    // 3. 算出 due 在 nearest 當天或之前的件數（dueByNearest）
    val dueByNearest = deadlineThoughts.count {
        try {
            val date = LocalDate.parse(it.dueDate, formatter)
            !date.isAfter(nearestDate)
        } catch (e: Exception) {
            false
        }
    }

    // Check if nearest is beyond current Sunday (下週以後)
    val currentSunday = today.plusDays((7 - today.dayOfWeek.value).toLong())
    val isBeyondThisWeek = nearestDate.isAfter(currentSunday)

    if (dleft < 0) {
        // Overdue -> Red Light
        TrafficLightBanner(
            backgroundColor = Color(0xFFEF5350).copy(alpha = 0.12f),
            borderColor = Color(0xFFEF5350),
            iconColor = Color(0xFFEF5350),
            title = "最近的死線已經過了",
            message = "有 ${dueByNearest} 件還沒落地",
            hypothesis = "註：計算採用每件卡死線念頭佔用一個縫隙的簡化假設。"
        )
    } else if (isBeyondThisWeek) {
        // Beyond this week -> Yellow/Orange Light
        TrafficLightBanner(
            backgroundColor = Color(0xFFFFA000).copy(alpha = 0.12f),
            borderColor = Color(0xFFFFA000),
            iconColor = Color(0xFFFFA000),
            title = "死線在下週",
            message = "先把本週能做的排進去（共 ${deadlineThoughts.size} 件待排死線）",
            hypothesis = "註：計算採用每件卡死線念頭佔用一個縫隙的簡化假設。"
        )
    } else {
        // Calculate freeSlots
        var freeSlots = 0
        var currentDate = today
        while (!currentDate.isAfter(nearestDate)) {
            val dayIndex = currentDate.dayOfWeek.value - 1
            val dayConfig = dayConfigs.find { it.dayIndex == dayIndex }
            if (dayConfig != null) {
                val gaps = getGapsForDayType(dayConfig.dayType, dayIndex)
                for (gap in gaps) {
                    val isEligible = gap.semantic == GapSemantic.GREEN || gap.semantic == GapSemantic.AMBER
                    if (isEligible) {
                        val hasPlacedThought = thoughts.any {
                            (it.status == "PLACED" || it.status == "COMPLETED") &&
                            it.placedDayIndex == dayIndex &&
                            it.placedSlotId == gap.id
                        }
                        if (!hasPlacedThought) {
                            freeSlots++
                        }
                    }
                }
            }
            currentDate = currentDate.plusDays(1)
        }

        if (freeSlots >= dueByNearest) {
            // Green Light
            TrafficLightBanner(
                backgroundColor = Color(0xFF4CAF50).copy(alpha = 0.12f),
                borderColor = Color(0xFF4CAF50),
                iconColor = Color(0xFF4CAF50),
                title = "到 ${nearestDate.monthValue}/${nearestDate.dayOfMonth} 前來得及",
                message = "剩 ${dleft} 天、還有 ${freeSlots} 個可放縫隙、卡死線 ${dueByNearest} 件",
                hypothesis = "註：計算採用每件卡死線念頭佔用一個縫隙的簡化假設。"
            )
        } else {
            // Red Light
            TrafficLightBanner(
                backgroundColor = Color(0xFFEF5350).copy(alpha = 0.12f),
                borderColor = Color(0xFFEF5350),
                iconColor = Color(0xFFEF5350),
                title = "縫隙快不夠了！",
                message = "剩 ${dleft} 天只有 ${freeSlots} 格但有 ${dueByNearest} 件，要嘛某天加做、要嘛動睡眠窗、要嘛跟案主談交期",
                hypothesis = "註：計算採用每件卡死線念頭佔用一個縫隙的簡化假設。"
            )
        }
    }
}

@Composable
fun TrafficLightBanner(
    backgroundColor: Color,
    borderColor: Color,
    iconColor: Color,
    title: String,
    message: String,
    hypothesis: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("feasibility_banner"),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.5.dp, borderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (borderColor == Color(0xFF4CAF50)) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = hypothesis,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

// ==========================================
// DRAG AND DROP STATE & UTILITIES
// ==========================================

data class LayoutBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

class DragDropState {
    var draggedThought by mutableStateOf<Thought?>(null)
    var dragOffset by mutableStateOf(Offset.Zero)
    var dragPosition by mutableStateOf(Offset.Zero)
    var dragStartOffset by mutableStateOf(Offset.Zero)
    val dropTargets = mutableStateMapOf<String, LayoutBounds>()

    fun updateBounds(key: String, bounds: LayoutBounds) {
        dropTargets[key] = bounds
    }

    fun removeBounds(key: String) {
        dropTargets.remove(key)
    }

    fun findHoveredTarget(): Pair<Int, String>? {
        val pos = dragPosition
        for ((key, bounds) in dropTargets) {
            if (pos.x >= bounds.left && pos.x <= bounds.right &&
                pos.y >= bounds.top && pos.y <= bounds.bottom) {
                val parts = key.split("_")
                if (parts.size >= 2) {
                    val dayIndex = parts[0].toIntOrNull() ?: continue
                    val slotId = parts.subList(1, parts.size).joinToString("_")
                    return Pair(dayIndex, slotId)
                }
            }
        }
        return null
    }

    fun findHoveredTab(): Int? {
        val pos = dragPosition
        for ((key, bounds) in dropTargets) {
            if (key.startsWith("tab_")) {
                val id = key.substringAfter("tab_").toIntOrNull() ?: continue
                if (pos.x >= bounds.left && pos.x <= bounds.right &&
                    pos.y >= bounds.top && pos.y <= bounds.bottom) {
                    return id
                }
            }
        }
        return null
    }

    fun findHoveredThoughtTarget(currentDraggedId: Int): Int? {
        val pos = dragPosition
        for ((key, bounds) in dropTargets) {
            if (key.startsWith("thought_")) {
                val id = key.substringAfter("thought_").toIntOrNull() ?: continue
                if (id != currentDraggedId) {
                    if (pos.x >= bounds.left && pos.x <= bounds.right &&
                        pos.y >= bounds.top && pos.y <= bounds.bottom) {
                        return id
                    }
                }
            }
        }
        return null
    }
}

val LocalDragDropState = staticCompositionLocalOf<DragDropState?> { null }

@Composable
fun DraggableThought(
    thought: Thought,
    onDrop: (Int, String) -> Unit,
    onDragStart: () -> Unit,
    onReorder: ((Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dragDropState = LocalDragDropState.current ?: return Box(modifier) { content() }
    var cardPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(thought.id) {
        onDispose {
            dragDropState.removeBounds("thought_${thought.id}")
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) {
                    cardPositionInRoot = coordinates.positionInRoot()
                    val bounds = LayoutBounds(
                        left = cardPositionInRoot.x,
                        top = cardPositionInRoot.y,
                        right = cardPositionInRoot.x + coordinates.size.width,
                        bottom = cardPositionInRoot.y + coordinates.size.height
                    )
                    dragDropState.updateBounds("thought_${thought.id}", bounds)
                }
            }
            .pointerInput(thought.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragDropState.dragStartOffset = offset
                        dragDropState.draggedThought = thought
                        dragDropState.dragOffset = Offset.Zero
                        dragDropState.dragPosition = cardPositionInRoot + offset
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragDropState.dragOffset += dragAmount
                        dragDropState.dragPosition = cardPositionInRoot + change.position
                        
                        if (onReorder != null) {
                            dragDropState.findHoveredThoughtTarget(thought.id)?.let { hoveredId ->
                                onReorder(thought.id, hoveredId)
                            }
                        }
                    },
                    onDragEnd = {
                        val target = dragDropState.findHoveredTarget()
                        if (target != null) {
                            onDrop(target.first, target.second)
                        }
                        dragDropState.draggedThought = null
                        dragDropState.dragOffset = Offset.Zero
                    },
                    onDragCancel = {
                        dragDropState.draggedThought = null
                        dragDropState.dragOffset = Offset.Zero
                    }
                )
            }
    ) {
        val alpha = if (dragDropState.draggedThought?.id == thought.id) 0.4f else 1.0f
        Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
            content()
        }
    }
}
