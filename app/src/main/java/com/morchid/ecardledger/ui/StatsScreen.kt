package com.morchid.ecardledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morchid.ecardledger.data.LedgerEntry
import java.time.YearMonth
import java.util.Locale

private val SLICE_COLORS = listOf(
    Color(0xFF1E88E5), Color(0xFF26A69A), Color(0xFFFF7043), Color(0xFFAB47BC),
    Color(0xFF66BB6A), Color(0xFFFFCA28), Color(0xFF8D6E63), Color(0xFF42A5F5),
    Color(0xFFEC407A), Color(0xFF78909C),
)

@Composable
fun StatsScreen(entries: List<LedgerEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("还没有数据，先同步一次")
        }
        return
    }

    val months = remember(entries) {
        entries.map { it.timeText.take(7) }
            .filter { it.length == 7 && it[4] == '-' }
            .distinct()
            .sortedDescending()
    }
    var selected by remember(months) { mutableStateOf(months.firstOrNull().orEmpty()) }

    val monthEntries = remember(entries, selected) {
        entries.filter { it.timeText.startsWith(selected) }
    }
    // 内部转账（例如 微信 → 校园卡充值）不计入收支统计：
    // 同一笔钱在两个账户里各出现一次，直接相加会把收入和支出双双虚增。
    val statEntries = remember(monthEntries) { monthEntries.filter { !it.isTransfer } }
    val transferCount = monthEntries.size - statEntries.size
    val expenseCents = statEntries.filter { !it.isIncome }.sumOf { it.amountCents }
    val incomeCents = statEntries.filter { it.isIncome }.sumOf { it.amountCents }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        // 月份选择
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            months.forEach { month ->
                FilterChip(
                    selected = month == selected,
                    onClick = { selected = month },
                    label = { Text(month) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 汇总
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryItem("支出", expenseCents, Color(0xFFC62828))
                SummaryItem("收入", incomeCents, Color(0xFF2E7D32))
                SummaryItem("结余", incomeCents - expenseCents, MaterialTheme.colorScheme.onSurface)
            }
            if (transferCount > 0) {
                Text(
                    "已排除 $transferCount 笔内部转账，避免同一笔钱被算两次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 每日支出
        SectionTitle("每日支出")
        val dailyData = remember(statEntries, selected) {
            val days = runCatching { YearMonth.parse(selected).lengthOfMonth() }.getOrDefault(31)
            val byDay = statEntries.filter { !it.isIncome }.groupBy { it.timeText.drop(8).take(2) }
            (1..days).map { day ->
                val key = String.format(Locale.CHINA, "%02d", day)
                BarDatum(
                    label = day.toString(),
                    value = (byDay[key]?.sumOf { it.amountCents } ?: 0L) / 100f,
                )
            }
        }
        BarChart(
            data = dailyData,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        )

        Spacer(Modifier.height(20.dp))

        // 分类占比
        SectionTitle("分类占比（仅支出）")
        val slices = remember(statEntries) {
            statEntries.filter { !it.isIncome }
                .groupBy { it.category.ifEmpty { "其他" } }
                .entries
                .sortedByDescending { it.value.sumOf { e -> e.amountCents } }
                .mapIndexed { index, e ->
                    Slice(
                        label = e.key,
                        value = e.value.sumOf { it.amountCents } / 100f,
                        color = SLICE_COLORS[index % SLICE_COLORS.size],
                    )
                }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = slices,
                modifier = Modifier
                    .size(150.dp)
                    .padding(8.dp),
            )
            Spacer(Modifier.height(0.dp))
            Column(Modifier.padding(start = 12.dp)) {
                if (slices.isEmpty()) {
                    Text("本月没有支出", style = MaterialTheme.typography.bodySmall)
                }
                slices.take(8).forEach { slice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(slice.color, RoundedCornerShape(2.dp)),
                        )
                        Text(
                            "  ${slice.label}  ${fmtYuan((slice.value * 100).toLong())}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 余额趋势
        SectionTitle("余额趋势")
        val balancePoints = remember(statEntries) {
            statEntries.filter { it.balanceCents > 0 }
                .sortedBy { it.epochMillis }
                .map { it.balanceCents / 100f }
        }
        LineChart(
            points = balancePoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
        )
        if (balancePoints.size >= 2) {
            Text(
                "本月区间：¥ " + fmtYuan((balancePoints.min() * 100).toLong()) +
                    " ~ ¥ " + fmtYuan((balancePoints.max() * 100).toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SummaryItem(label: String, cents: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "¥ " + fmtYuan(cents),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun fmtYuan(cents: Long): String =
    String.format(Locale.CHINA, "%.2f", cents / 100.0)
