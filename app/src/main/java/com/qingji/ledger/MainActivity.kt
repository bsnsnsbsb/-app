package com.qingji.ledger

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
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

data class OcrBill(
    val amount: String,
    val category: String,
    val merchant: String,
    val date: String,
    val source: String,
    val raw: String
)

data class OcrBatch(
    val source: String,
    val bills: List<OcrBill>,
    val raw: String
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

    fun addOcrBill(bill: OcrBill) = viewModelScope.launch {
        val fen = yuanToFen(bill.amount)
        if (fen <= 0) return@launch
        val projectId = state.value.projects.find { it.name == bill.category }?.id
        val note = "${bill.source}识别：${bill.merchant}".trim()
        dao.insertRecord(ExpenseRecord(amountFen = fen, category = bill.category, projectId = projectId, note = note, date = bill.date))
    }

    fun addOcrBills(bills: List<OcrBill>) = viewModelScope.launch {
        bills.forEach { bill ->
            val fen = yuanToFen(bill.amount)
            if (fen > 0) {
                val projectId = state.value.projects.find { it.name == bill.category }?.id
                val note = "${bill.source}识别：${bill.merchant}".trim()
                dao.insertRecord(ExpenseRecord(amountFen = fen, category = bill.category, projectId = projectId, note = note, date = bill.date))
            }
        }
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
    var ocrBatch by remember { mutableStateOf<OcrBatch?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognizeBillImage(context, uri, onSuccess = { ocrBatch = it }, onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Mint, background = Bg, surface = Color.White)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("🏠") }, label = { Text("首页") })
                    NavigationBarItem(selected = false, onClick = { showRecord = true }, icon = { Text("＋", fontSize = 24.sp, fontWeight = FontWeight.Black) }, label = { Text("记一笔") })
                    NavigationBarItem(selected = false, onClick = { imagePicker.launch("image/*") }, icon = { Text("📷") }, label = { Text("识图") })
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
        ocrBatch?.let { batch -> OcrBatchConfirmDialog(batch = batch, onDismiss = { ocrBatch = null }, onSave = { vm.addOcrBills(it); Toast.makeText(context, "批量记账成功：${it.size} 笔", Toast.LENGTH_SHORT).show(); ocrBatch = null }) }
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


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrBatchConfirmDialog(batch: OcrBatch, onDismiss: () -> Unit, onSave: (List<OcrBill>) -> Unit) {
    val bills = remember(batch) { mutableStateListOf<OcrBill>().also { it.addAll(batch.bills) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别到账单明细") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源：" + batch.source + " · 共 " + bills.size + " 笔", fontWeight = FontWeight.Bold)
                Text("会按商户关键词自动分类。请确认后批量保存。", color = Muted, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bills.size) { idx ->
                        val bill = bills[idx]
                        CardBox {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(bill.merchant.ifBlank { "账单明细" }, fontWeight = FontWeight.Bold)
                                    Text("¥" + bill.amount + " · " + bill.date, color = Red, fontWeight = FontWeight.Black)
                                }
                                TextButton(onClick = { bills.removeAt(idx) }) { Text("删") }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                categories.forEach { c ->
                                    FilterChip(selected = bill.category == c, onClick = { bills[idx] = bill.copy(category = c) }, label = { Text(emojiFor(c) + " " + c) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(bills.toList()) }, enabled = bills.isNotEmpty()) { Text("保存 " + bills.size + " 笔") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

fun recognizeBillImage(context: Context, uri: Uri, onSuccess: (OcrBatch) -> Unit, onError: (String) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val raw = result.text
                val batch = parseWechatAlipayBills(raw)
                if (batch.bills.isEmpty()) onError("没识别到账单明细，请换微信/支付宝账单列表截图") else onSuccess(batch)
            }
            .addOnFailureListener { onError("图片识别失败：" + (it.message ?: "未知错误")) }
    } catch (e: Exception) {
        onError("无法读取图片：" + (e.message ?: "未知错误"))
    }
}

fun parseWechatAlipayBills(raw: String): OcrBatch {
    val text = raw.replace("，", ",").replace("￥", "¥")
    val source = when {
        text.contains("微信") || text.contains("零钱") || text.contains("微信支付") -> "微信"
        text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝") -> "支付宝"
        else -> "图片"
    }
    val defaultDate = Regex("""(20[0-9]{2})[-/.年]([01]?[0-9])[-/.月]([0-3]?[0-9])""").find(text)?.let {
        val y = it.groupValues[1]
        val m = it.groupValues[2].padStart(2, '0')
        val d = it.groupValues[3].padStart(2, '0')
        y + "-" + m + "-" + d
    } ?: LocalDate.now().toString()
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val bills = mutableListOf<OcrBill>()
    val amountRegex = Regex("""[-−]?\s*(?:¥|￥)?\s*([0-9]+(?:\.[0-9]{1,2})?)\s*(?:元)?""")
    for (i in lines.indices) {
        val line = lines[i]
        if (line.contains("余额") || line.contains("合计") || line.contains("总计") || line.contains("收入") || line.contains("退款")) continue
        val amountMatch = amountRegex.findAll(line).map { it.groupValues[1] }.lastOrNull { v ->
            val n = v.toDoubleOrNull() ?: 0.0
            n > 0.0 && n < 100000.0
        } ?: continue
        if (!line.contains("¥") && !line.contains("￥") && !line.contains("元") && !line.contains("-") && line.length < 8) continue
        val prev = lines.getOrNull(i - 1).orEmpty()
        val next = lines.getOrNull(i + 1).orEmpty()
        val merchant = cleanMerchant(chooseMerchant(line, prev, next, amountMatch))
        if (merchant.isBlank()) continue
        if (bills.any { it.amount == amountMatch && it.merchant == merchant }) continue
        val blob = merchant + " " + line + " " + prev + " " + next
        bills.add(OcrBill(amount = amountMatch, category = guessCategory(blob), merchant = merchant.take(30), date = defaultDate, source = source, raw = line))
    }
    if (bills.isEmpty()) parseSingleBill(raw)?.let { bills.add(it) }
    return OcrBatch(source, bills.take(30), raw)
}

fun parseSingleBill(raw: String): OcrBill? {
    val text = raw.replace("，", ",").replace("￥", "¥")
    val source = when {
        text.contains("微信") || text.contains("零钱") || text.contains("微信支付") -> "微信"
        text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝") -> "支付宝"
        else -> "图片"
    }
    val amountPatterns = listOf(
        Regex("""(?:¥|￥|人民币|金额|付款|支付|支出|消费)\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元"""),
        Regex("""-\s*(?:¥|￥)?\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
        Regex("""(?:¥|￥)\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    )
    val amount = amountPatterns.asSequence().mapNotNull { it.find(text)?.groupValues?.getOrNull(1) }.firstOrNull { it.toDoubleOrNull()?.let { v -> v > 0 } == true } ?: return null
    val date = LocalDate.now().toString()
    val lines = raw.lines().map { it.trim() }.filter { it.length >= 2 }
    val merchant = lines.firstOrNull { line ->
        !line.contains("支付") && !line.contains("付款") && !line.contains("收款") && !line.contains("金额") && !line.contains("成功") && !line.contains("账单") && !line.matches(Regex(".*[0-9]{2,}.*"))
    } ?: "微信/支付宝账单"
    val category = guessCategory(merchant + " " + raw)
    return OcrBill(amount = amount, category = category, merchant = merchant.take(30), date = date, source = source, raw = raw)
}

fun chooseMerchant(line: String, prev: String, next: String, amount: String): String {
    val stripped = line.replace(amount, "").replace("¥", "").replace("￥", "").replace("元", "").replace("-", "").trim()
    return listOf(stripped, prev, next).firstOrNull { candidate ->
        val c = candidate.trim()
        c.length >= 2 && !c.contains("支付") && !c.contains("账单") && !c.contains("余额") && !c.matches(Regex(".*[0-9]{4,}.*"))
    }.orEmpty()
}

fun cleanMerchant(s: String): String = s
    .replace(Regex("""[¥￥]?[0-9]+(?:\.[0-9]{1,2})?元?"""), "")
    .replace("支出", "")
    .replace("付款", "")
    .replace("消费", "")
    .trim()

fun guessCategory(s: String): String {
    val t = s.lowercase(Locale.CHINA)
    return when {
        listOf("餐", "饭", "外卖", "美团", "饿了", "肯德基", "麦当劳", "咖啡", "奶茶", "超市", "便利店").any { t.contains(it) } -> "餐饮"
        listOf("滴滴", "打车", "地铁", "公交", "高铁", "火车", "机票", "停车", "加油").any { t.contains(it) } -> "交通"
        listOf("淘宝", "京东", "拼多多", "购物", "商城", "服饰", "手机", "数码").any { t.contains(it) } -> "购物"
        listOf("电影", "游戏", "ktv", "会员", "娱乐", "音乐", "视频").any { t.contains(it) } -> "娱乐"
        listOf("书", "课程", "学习", "培训", "考试", "文具").any { t.contains(it) } -> "学习"
        listOf("医院", "药", "诊所", "挂号", "医疗").any { t.contains(it) } -> "医疗"
        listOf("房租", "物业", "水费", "电费", "燃气").any { t.contains(it) } -> "房租"
        else -> "其他"
    }
}

val Mint = Color(0xFF7DD3C0)
val MintDark = Color(0xFF39A891)
val Bg = Color(0xFFF7FAFC)
val TextDark = Color(0xFF2D3748)
val Muted = Color(0xFFA0AEC0)
val Red = Color(0xFFF56565)
val SoftRed = Color(0xFFFFF5F5)
val Green = Color(0xFF48BB78)
