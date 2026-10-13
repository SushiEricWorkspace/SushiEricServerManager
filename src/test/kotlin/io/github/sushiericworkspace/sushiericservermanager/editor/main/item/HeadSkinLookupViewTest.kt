package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.TreeRow
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HeadSkinLookupViewTest {
    @Test
    fun `取得の状態と入力変更や表示解除後の結果を確認する`() {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))

        for (scenario in listOf("成功", "失敗", "入力変更", "入力復帰", "指定変更", "切替", "再表示", "終了", "再開", "空入力", "不正UUID")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val item = MutableItemBaseData().apply {
                itemDetail.vanillaId = VanillaItemId("player_head")
                itemDetail.mutableHeadSkin = MutableHeadSkinData(
                    if (scenario == "不正UUID") HeadSkinSource.PLAYER_UUID else HeadSkinSource.PLAYER_NAME,
                    if (scenario == "空入力") "" else "SushiEric"
                )
            }
            lateinit var stage: Stage
            lateinit var root: VBox
            lateinit var button: Button
            lateinit var input: TextField
            lateinit var progress: ProgressIndicator
            try {
                fx {
                    val graphic = ItemEditorFactory(item, {}, headSkinTextureLookup = { _, _ ->
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        if (scenario == "失敗") HeadSkinTextureLookupResult.Failure("通信に失敗しました。")
                        else HeadSkinTextureLookupResult.Success("texture-value")
                    }).createGraphic(TreeRow.Editor.DetailContent)
                    root = VBox(graphic)
                    stage = Stage().apply { scene = Scene(root, 1000.0, 720.0); show() }
                    val nodes = nodes(root).toList()
                    button = nodes.filterIsInstance<Button>().single { it.text == "テクスチャ値を取得" }
                    input = nodes.filterIsInstance<TextField>().single { it.promptText == "スキンの値" }
                    progress = nodes.filterIsInstance<ProgressIndicator>().single()
                    button.fire()
                    if (scenario in listOf("空入力", "不正UUID")) {
                        assertFalse(button.isDisable)
                        assertFalse(progress.isVisible)
                        assertEquals(1L, entered.count)
                        assertTrue(nodes.filterIsInstance<Label>().any {
                            it.isVisible && it.text == if (scenario == "空入力") "値を入力してください。" else "UUIDとして解釈できません。"
                        })
                    } else {
                        assertTrue(button.isDisable)
                        assertTrue(progress.isVisible)
                        assertTrue(nodes.filterIsInstance<Label>().any { it.isVisible && it.text == "スキンを取得中" })
                    }
                }
                if (scenario in listOf("空入力", "不正UUID")) continue
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx {
                    when (scenario) {
                        "入力変更" -> input.text = "Other"
                        "入力復帰" -> { input.text = "Other"; input.text = "SushiEric" }
                        "指定変更" -> nodes(root).filterIsInstance<ComboBox<*>>().single {
                            it.items.firstOrNull()?.toString() == "未指定"
                        }.selectionModel.select(2)
                        "切替" -> root.children.clear()
                        "再表示" -> { val graphic = root.children.single(); root.children.clear(); root.children.add(graphic) }
                        "再開" -> { stage.hide(); stage.show() }
                        "終了" -> stage.hide()
                    }
                }
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (fx { button.isDisable } && System.nanoTime() < deadline) Thread.sleep(20)
                fx {
                    assertFalse(button.isDisable)
                    assertFalse(progress.isVisible)
                    if (scenario == "成功") {
                        assertEquals(HeadSkinSource.TEXTURE, item.itemDetail.mutableHeadSkin?.source)
                        assertEquals("texture-value", item.itemDetail.mutableHeadSkin?.value)
                        assertTrue(nodes(root).filterIsInstance<Label>().any { it.isVisible && it.text == "スキンを取得しました。" })
                    } else {
                        assertEquals(if (scenario == "指定変更") HeadSkinSource.PLAYER_UUID else HeadSkinSource.PLAYER_NAME, item.itemDetail.mutableHeadSkin?.source)
                        assertEquals(if (scenario == "入力変更") "Other" else "SushiEric", item.itemDetail.mutableHeadSkin?.value)
                        if (scenario == "失敗") {
                            assertTrue(nodes(root).filterIsInstance<Label>().any { it.isVisible && it.text == "通信に失敗しました。" })
                        }
                    }
                }
            } finally {
                release.countDown()
                fx { stage.hide() }
            }
        }
    }

    private fun nodes(node: Node): Sequence<Node> = sequence {
        yield(node)
        if (node is Parent) node.childrenUnmodifiable.forEach { yieldAll(nodes(it)) }
    }

    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(20, TimeUnit.SECONDS)
    }
}
