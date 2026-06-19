package com.qingji.ledger

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

@Entity(tableName = "projects")
data class PlanProject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val plannedFen: Long,
    val emoji: String = "✨"
)

@Entity(tableName = "records")
data class ExpenseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountFen: Long,
    val category: String,
    val projectId: Long?,
    val note: String,
    val date: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM projects ORDER BY id ASC")
    fun projects(): Flow<List<PlanProject>>

    @Query("SELECT * FROM records ORDER BY date DESC, createdAt DESC")
    fun records(): Flow<List<ExpenseRecord>>

    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): AppSetting?

    @Insert suspend fun insertProject(project: PlanProject)
    @Insert suspend fun insertRecord(record: ExpenseRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setSetting(setting: AppSetting)
    @Delete suspend fun deleteProject(project: PlanProject)
    @Delete suspend fun deleteRecord(record: ExpenseRecord)
}

@Database(entities = [PlanProject::class, ExpenseRecord::class, AppSetting::class], version = 1, exportSchema = false)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        @Volatile private var INSTANCE: LedgerDb? = null
        fun get(app: Application): LedgerDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(app, LedgerDb::class.java, "qingji-ledger.db").build().also { INSTANCE = it }
        }
    }
}

data class UiState(
    val budgetFen: Long = 300_000,
    val projects: List<PlanProject> = emptyList(),
    val records: List<ExpenseRecord> = emptyList()
)

class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = LedgerDb.get(app).dao()
    private val budget = MutableStateFlow(300_000L)
    val state: StateFlow<UiState> = combine(budget, dao.projects(), dao.records()) { b, p, r -> UiState(b, p, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch {
            budget.value = dao.getSetting("budgetFen")?.value?.toLongOrNull() ?: 300_000L
            if (state.value.projects.isEmpty()) {
                dao.insertProject(PlanProject(name = "餐饮", plannedFen = 100_000, emoji = "🍜"))
                dao.insertProject(PlanProject(name = "交通", plannedFen = 30_000, emoji = "🚌"))
                dao.insertProject(PlanProject(name = "购物", plannedFen = 80_000, emoji = "🛍️"))
            }
        }
    }

    fun setBudget(yuan: String) = viewModelScope.launch {
        val fen = yuanToFen(yuan)
        budget.value = fen
        dao.setSetting(AppSetting("budgetFen", fen.toString()))
    }

    fun addProject(name: String, planned: String, emoji: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        dao.insertProject(PlanProject(name = name.trim(), plannedFen = yuanToFen(planned), emoji = emoji.ifBlank { "✨" }))
    }

    fun deleteProject(project: PlanProject) = viewModelScope.launch { dao.deleteProject(project) }

    fun addRecord(amount: String, category: String, projectId: Long?, note: String, date: String) = viewModelScope.launch {
        val fen = yuanToFen(amount)
        if (fen <= 0) return@launch
        dao.insertRecord(ExpenseRecord(amountFen = fen, category = category, projectId = projectId, note = note.trim(), date = date))
    }

    fun deleteRecord(record: ExpenseRecord) = viewModelScope.launch { dao.deleteRecord(record) }
}

class MainActivity : ComponentActivity() {
    private val vm by viewModels<LedgerViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QingjiApp(vm) }
    }
}

@Composable
fun QingjiApp(vm: LedgerViewModel) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var showRecord by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Mint, background = Bg, surface = Color.White)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("🏠") }, label = { Text("首页") })
                    NavigationBarItem(selected = false, onClick = { showRecord = true }, icon = { Text("＋", fontSize = 24.sp, fontWeight = FontWeight.Black) }, label = { Text("记一笔") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🎯") }, label = { Text("预算") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("📒") }, label = { Text("账单") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> HomeScreen(state, onRecord = { showRecord = true })
                    1 -> BudgetScreen(state, vm)
                    else -> RecordsScreen(state, vm)
                }
            }
        }
        if (showRecord) RecordSheet(state, vm, onDismiss = { showRecord = false })
    }
}

@Composable
fun HomeScreen(state: UiState, onRecord: () -> Unit) {
    val spent = state.records.sumOf { it.amountFen }
    val remaining = state.budgetFen - spent
    val projectSpent = state.records.groupBy { it.projectId }.mapValues { it.value.sumOf { r -> r.amountFen } }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header() }
        item {
            HeroCard(spent = spent, budget = state.budgetFen, remaining = remaining)
        }
        item { SectionTitle("花钱计划") }
        items(state.projects) { p ->
            val used = projectSpent[p.id] ?: 0
            ProjectCard(project = p, used = used)
        }
        item { SectionTitle("最近账单") }
        if (state.records.isEmpty()) item { EmptyCard("还没有记录，记第一笔吧 →", onRecord) }
        else items(state.records.take(5)) { RecordRow(it, state.projects, null) }
    }
}

@Composable
fun BudgetScreen(state: UiState, vm: LedgerViewModel) {
    var budget by remember(state.budgetFen) { mutableStateOf(fenPlain(state.budgetFen)) }
    var name by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("✨") }
    val spent = state.records.sumOf { it.amountFen }
    val projectSpent = state.records.groupBy { it.projectId }.mapValues { it.value.sumOf { r -> r.amountFen } }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header() }
        item {
            CardBox {
                Text("这个月打算花多少？", fontWeight = FontWeight.Bold)
                OutlinedTextField(budget, { budget = it }, prefix = { Text("¥") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.setBudget(budget) }, modifier = Modifier.fillMaxWidth()) { Text("保存预算") }
            }
        }
        item { SummaryRow(state.budgetFen, spent) }
        item { SectionTitle("添加花钱计划") }
        item {
            CardBox {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(2) }, modifier = Modifier.width(72.dp), singleLine = true)
                    OutlinedTextField(name, { name = it }, placeholder = { Text("项目") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(planned, { planned = it }, placeholder = { Text("计划金额") }, prefix = { Text("¥") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { vm.addProject(name, planned, emoji); name=""; planned="" }, modifier = Modifier.fillMaxWidth()) { Text("添加计划") }
            }
        }
        items(state.projects) { p -> ProjectCard(project = p, used = projectSpent[p.id] ?: 0, onDelete = { vm.deleteProject(p) }) }
    }
}

@Composable
fun RecordsScreen(state: UiState, vm: LedgerViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header() }
        item { SectionTitle("账单回顾 · ${state.records.size} 笔") }
        if (state.records.isEmpty()) item { EmptyCard("还没有记录，记第一笔吧 →", null) }
        else items(state.records) { RecordRow(it, state.projects) { vm.deleteRecord(it) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordSheet(state: UiState, vm: LedgerViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("餐饮") }
    var projectId by remember(state.projects) { mutableStateOf(state.projects.find { it.name == category }?.id) }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("记一笔", fontSize = 24.sp, fontWeight = FontWeight.Black)
            OutlinedTextField(amount, { amount = it }, label = { Text("花了多少钱？") }, prefix = { Text("¥") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("花在哪里？", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c; projectId = state.projects.find { it.name == c }?.id }, label = { Text("${emojiFor(c)} $c") }) }
            }
            DropdownProject(state.projects, projectId) { projectId = it }
            OutlinedTextField(date, { date = it }, label = { Text("日期") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("备注，可不填") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { val finalProjectId = state.projects.find { it.name == category }?.id ?: projectId; vm.addRecord(amount, category, finalProjectId, note, date); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("记好啦") }
        }
    }
}

@Composable
fun Header() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("清新记账", color = MintDark, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text("轻记", fontSize = 34.sp, fontWeight = FontWeight.Black) }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) { Text("✨", fontSize = 22.sp) }
    }
}

@Composable
fun HeroCard(spent: Long, budget: Long, remaining: Long) {
    val pct = if (budget <= 0) 0 else ((spent * 100) / budget).toInt()
    val danger = remaining < 0
    val heroBrush = if (danger) Brush.linearGradient(listOf(Color(0xFFFFF5F5), Color(0xFFFFE3E3))) else Brush.linearGradient(listOf(Mint, Color(0xFFE6FFFA)))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(heroBrush).padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("这个月已经花了", fontWeight = FontWeight.Bold); Text(fen(spent), fontSize = 34.sp, fontWeight = FontWeight.Black) }
            Box(Modifier.size(82.dp).clip(CircleShape).background(Color.White.copy(alpha = .82f)), contentAlignment = Alignment.Center) { Text("$pct%", fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { (pct.coerceIn(0,100)) / 100f }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)), color = if (danger) Red else TextDark, trackColor = Color.White.copy(alpha=.55f))
        Spacer(Modifier.height(12.dp))
        Text(if (danger) "本月预算花超啦，已超 ${fen(-remaining)}" else "还剩 ${fen(remaining)}，心里有数就不慌。", fontWeight = FontWeight.Bold)
    }
}

@Composable fun SectionTitle(text: String) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Black) }

@Composable
fun ProjectCard(project: PlanProject, used: Long, onDelete: (() -> Unit)? = null) {
    val over = used > project.plannedFen
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFEDFDF8)), contentAlignment = Alignment.Center) { Text(project.emoji, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) { Text(project.name, fontWeight = FontWeight.Black); Text("已花 ${fen(used)} / 计划 ${fen(project.plannedFen)}", color = Muted, fontSize = 12.sp) }
            Text(if (over) "超 ${fen(used - project.plannedFen)}" else "剩 ${fen(project.plannedFen - used)}", color = if (over) Red else Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删") }
        }
        LinearProgressIndicator(progress = { if (project.plannedFen <= 0) 0f else (used.toFloat() / project.plannedFen).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)), color = if (over) Red else Mint, trackColor = Color(0xFFEDF2F7))
        Text(if (over) "已经超支 ${fen(used-project.plannedFen)}，看看哪里可以省一点。" else "还剩 ${fen(project.plannedFen-used)}。", color = if (over) Red else Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SummaryRow(budget: Long, spent: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCell("总预算", fen(budget), Modifier.weight(1f))
        SummaryCell("已花", fen(spent), Modifier.weight(1f))
        SummaryCell("剩余", fen(budget-spent), Modifier.weight(1f), if (budget-spent < 0) Red else TextDark)
    }
}
@Composable fun SummaryCell(label: String, value: String, modifier: Modifier, color: Color = TextDark) { CardBox(modifier) { Text(label, color = Muted, fontSize = 12.sp); Text(value, color = color, fontWeight = FontWeight.Black) } }

@Composable
fun RecordRow(record: ExpenseRecord, projects: List<PlanProject>, onDelete: (() -> Unit)?) {
    val p = projects.find { it.id == record.projectId }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(emojiFor(record.category), fontSize = 24.sp)
            Column(Modifier.weight(1f)) { Text(record.category + (p?.let { " · ${it.name}" } ?: ""), fontWeight = FontWeight.Bold); Text("${record.date}${if(record.note.isNotBlank()) " · ${record.note}" else ""}", color = Muted, fontSize = 12.sp) }
            Text("-${fen(record.amountFen)}", color = Red, fontWeight = FontWeight.Black)
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删") }
        }
    }
}

@Composable
fun DropdownProject(projects: List<PlanProject>, selected: Long?, onSelected: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = projects.find { it.id == selected }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(current?.let { "${it.emoji} ${it.name}" } ?: "不关联计划") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("不关联计划") }, onClick = { onSelected(null); expanded=false })
            projects.forEach { p -> DropdownMenuItem(text = { Text("${p.emoji} ${p.name}") }, onClick = { onSelected(p.id); expanded=false }) }
        }
    }
}

@Composable fun EmptyCard(text: String, onClick: (() -> Unit)?) { CardBox { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text("✍️", fontSize = 34.sp); Text(text, color = Muted); if (onClick != null) Button(onClick = onClick) { Text("记一笔") } } } }

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

val categories = listOf("餐饮", "交通", "购物", "娱乐", "学习", "医疗", "房租", "其他")
fun emojiFor(c: String) = mapOf("餐饮" to "🍜", "交通" to "🚌", "购物" to "🛍️", "娱乐" to "🎮", "学习" to "📚", "医疗" to "💊", "房租" to "🏠", "其他" to "✨")[c] ?: "✨"
fun yuanToFen(s: String): Long = ((s.toDoubleOrNull() ?: 0.0) * 100).toLong()
fun fenPlain(fen: Long) = if (fen % 100 == 0L) (fen / 100).toString() else String.format(Locale.US, "%.2f", fen / 100.0)
fun fen(fen: Long): String = "¥" + NumberFormat.getNumberInstance(Locale.CHINA).format(fen / 100.0)

val Mint = Color(0xFF7DD3C0)
val MintDark = Color(0xFF39A891)
val Bg = Color(0xFFF7FAFC)
val TextDark = Color(0xFF2D3748)
val Muted = Color(0xFFA0AEC0)
val Red = Color(0xFFF56565)
val SoftRed = Color(0xFFFFF5F5)
val Green = Color(0xFF48BB78)
