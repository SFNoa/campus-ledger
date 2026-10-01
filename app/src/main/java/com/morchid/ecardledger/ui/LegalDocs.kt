package com.morchid.ecardledger.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 两份法律文档。
 *
 * 文件放在 `assets/legal/`，由构建任务 syncLegalDocs 从项目根目录自动同步 ——
 * 根目录那两份是唯一事实来源（GitHub 上展示的也是它们），不会写歪。
 */
enum class LegalDoc(val fileName: String, val title: String, val repoPath: String) {
    TERMS("legal/TERMS.md", "用户协议", "TERMS.md"),
    PRIVACY("legal/PRIVACY.md", "隐私政策", "PRIVACY.md"),
}

/** 一行渲染结果：level 0 = 正文，1~3 = 标题级别 */
internal data class LegalLine(val level: Int, val text: String)

/**
 * 极简 Markdown 预处理。
 *
 * 为什么不为两份说明文字引一个 Markdown 渲染库：不划算。
 * 只处理实际用到的那几种写法（标题、粗体、引用、表格），
 * 表格行压成 `a · b` 一行 —— 手机小屏上横排反而更好读。
 */
internal fun formatLegalMarkdown(raw: String): List<LegalLine> {
    val out = mutableListOf<LegalLine>()
    for (line in raw.lines()) {
        val t = line.trim()
        when {
            t.isEmpty() -> out += LegalLine(0, "")
            t.matches(Regex("^\\|[\\s\\-:|]+\\|$")) -> Unit
            t.startsWith("|") -> {
                val cells = t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                out += LegalLine(0, cells.joinToString(" · "))
            }
            t.startsWith("# ") -> out += LegalLine(1, cleanInline(t.removePrefix("# ")))
            t.startsWith("## ") -> out += LegalLine(2, cleanInline(t.removePrefix("## ")))
            t.startsWith("### ") -> out += LegalLine(3, cleanInline(t.removePrefix("### ")))
            t.startsWith("#### ") -> out += LegalLine(3, cleanInline(t.removePrefix("#### ")))
            t.startsWith("> ") -> out += LegalLine(0, cleanInline(t.removePrefix("> ")))
            else -> out += LegalLine(0, cleanInline(t))
        }
    }
    return out
}

/** 去掉行内标记（**粗体**、`代码`），文字本身保留 —— 手机上不必看那些符号 */
private fun cleanInline(s: String): String = s.replace("**", "").replace("`", "")

/** 协议全文（离线可读；另给一个"在 GitHub 上查看"的出口） */
@Composable
fun LegalDialog(doc: LegalDoc, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines by produceState<List<LegalLine>?>(initialValue = null, doc) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(doc.fileName).bufferedReader().use {
                    formatLegalMarkdown(it.readText())
                }
            }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val body = lines
                if (body == null) {
                    Text("读取中…", style = MaterialTheme.typography.bodySmall)
                } else {
                    body.forEach { line ->
                        when (line.level) {
                            1 -> Text(line.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            2 -> Text(line.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            3 -> Text(line.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            else -> Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL + "/blob/main/" + doc.repoPath)),
                    )
                }
            }) { Text("在 GitHub 上查看") }
        },
    )
}

/** 项目仓库地址 */
const val REPO_URL = "https://github.com/SFNoa/campus-ledger"