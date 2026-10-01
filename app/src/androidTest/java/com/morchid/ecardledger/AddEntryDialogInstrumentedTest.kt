package com.morchid.ecardledger

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.morchid.ecardledger.ui.AddEntryDialog
import com.morchid.ecardledger.ui.EcardLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「手动记一笔」对话框的 **instrumented 测试**（跑在真机或模拟器上的真实 Android 运行时）。
 *
 * 为什么这些用例不在 test/（JVM + Robolectric）里：
 * 「含输入框的对话框」在 Robolectric 下 Compose 永远达不到 idle
 * （AppNotIdleException: Compose did not get idle，见 robolectric#7055），
 * 同一个输入框放在对话框外面却完全正常 —— 属于测试环境限制，不是 App 缺陷。
 * 所以这块只能放到 androidTest 里跑：
 *
 *   gradle connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AddEntryDialogInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 对话框渲染出全部输入项与按钮() {
        compose.setContent {
            EcardLedgerTheme { AddEntryDialog(onDismiss = {}, onConfirm = { _, _, _, _, _, _ -> }) }
        }
        compose.onNodeWithText("手动记一笔").assertExists()
        compose.onNodeWithText("支出").assertExists()
        compose.onNodeWithText("收入").assertExists()
        compose.onNodeWithText("金额（元）").assertExists()
        compose.onNodeWithText("商户 / 说明").assertExists()
        compose.onNodeWithText("分类").assertExists()
        compose.onNodeWithText("备注（可选）").assertExists()
        // 保存/取消在内容底部，小屏上需要先滚动过去
        compose.onNodeWithText("保存").performScrollTo().assertExists()
        compose.onNodeWithText("取消").performScrollTo().assertExists()
    }

    @Test
    fun 填好内容点保存会把值回调出去() {
        var captured: List<Any?>? = null
        compose.setContent {
            EcardLedgerTheme {
                AddEntryDialog(
                    onDismiss = {},
                    onConfirm = { amount, income, merchant, category, note, accountId ->
                        captured = listOf(amount, income, merchant, category, note, accountId)
                    },
                )
            }
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("12.5")
        fields[1].performTextInput("打印店")
        fields[2].performTextInput("打印资料")

        compose.onNodeWithText("学习").performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()

        assertEquals(
            listOf("12.5", false, "打印店", "学习", "打印资料"),
            captured,
        )
    }

    @Test
    fun 切到收入后回调的_isIncome_为_true() {
        var captured: List<Any?>? = null
        compose.setContent {
            EcardLedgerTheme {
                AddEntryDialog(
                    onDismiss = {},
                    onConfirm = { amount, income, merchant, category, note, accountId ->
                        captured = listOf(amount, income, merchant, category, note, accountId)
                    },
                )
            }
        }
        compose.onNodeWithText("收入").performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        assertEquals(true, captured?.get(1))
    }

    @Test
    fun 点取消触发_onDismiss_而不是_onConfirm() {
        var dismissed = false
        var confirmed = false
        compose.setContent {
            EcardLedgerTheme {
                AddEntryDialog(
                    onDismiss = { dismissed = true },
                    onConfirm = { _, _, _, _, _, _ -> confirmed = true },
                )
            }
        }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        assertEquals(true, dismissed)
        assertEquals(false, confirmed)
    }
}
