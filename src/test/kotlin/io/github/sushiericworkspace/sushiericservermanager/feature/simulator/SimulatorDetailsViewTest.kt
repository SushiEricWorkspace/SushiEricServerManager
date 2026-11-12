package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageResult
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemDetail
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.SkillType
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import java.awt.image.BufferedImage
import java.nio.file.Path
import javafx.scene.Node
import javafx.scene.image.ImageView
import javax.imageio.ImageIO
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.detail.toMutable
import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SimulatorDetailsViewTest {
    @Test fun `実FXMLから固定取込と期待値詳細と比較基準を接続し旧結果を保持する`() {
        val view=fx { SimulatorView.load() }
        val stage=fx { Stage().apply {
            scene=Utility.createScene(
                AppScreen.SIMULATOR,customRoot=view.root)
            width=1100.0; height=900.0; show()
        } }
        try {
            fx {
                view.controller.workspace.start(view.controller.inputEditor.readInput(),SimulatorMultipleHitMode.Expected)
            }
            await { view.controller.workspace.state is SimulatorRunState.Finished }
            val before=fx { assertIs<SimulatorRunState.Finished>(view.controller.workspace.state) }
            fx {
                assertTrue((view.root.lookup("#detailsArea") as TextArea).text.contains("計算に使った命中評価回数"))
                button(view.root,"基準を保持・更新").fire()
                val model=MutableItemBaseData(id="fixed").apply { display.displayName="固定Item" }
                view.controller.acceptImportedItem(StatsPart.MAIN_HAND,SimulatorImportedItem.fromEdited(model))
                model.display.displayName="外部変更"
                val fixed=view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND)
                assertEquals("固定Item",fixed.baseData.display.displayName)
                assertSame(before,view.controller.workspace.state)
                assertTrue((view.root.lookup("#statusLabel") as Label).text.contains("再計算"))
                view.controller.workspace.start(view.controller.inputEditor.readInput(),SimulatorMultipleHitMode.Expected)
            }
            await { view.controller.workspace.state is SimulatorRunState.Finished }
            fx {
                button(view.root,"比較").fire()
                val text=(view.root.lookup("#comparisonLabel") as Label).text
                assertTrue(text.contains("平均ダメージの比較（厳密期待値）")); assertTrue(text.contains("命中ID="))
                assertTrue(text.contains("純HP")); assertTrue(text.contains("OH"))
                view.root.applyCss(); view.root.layout()
                assertTrue((view.root.lookup("#detailsArea") as TextArea).height >= 140.0)
                val image=view.root.snapshot(null,null)
                val png=BufferedImage(image.width.toInt(),image.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until png.height) for(x in 0 until png.width) png.setRGB(x,y,image.pixelReader.getArgb(x,y))
                ImageIO.write(png,"png",Path.of("build/simulator270.png").toFile())
                button(view.root,"基準を保持・更新").fire()
                val held=(view.root.lookup("#comparisonLabel") as Label).text
                assertTrue(button(view.root,"比較の前ページ").isDisable)
                assertTrue(button(view.root,"比較の次ページ").isDisable)
                javafx.event.Event.fireEvent(button(view.root,"比較の前ページ"),javafx.event.ActionEvent())
                javafx.event.Event.fireEvent(button(view.root,"比較の次ページ"),javafx.event.ActionEvent())
                assertEquals(held,(view.root.lookup("#comparisonLabel") as Label).text)
                button(view.root,"基準解除").fire()
                assertTrue((view.root.lookup("#comparisonLabel") as Label).text.contains("解除"))
            }
        } finally { fx { view.dispose(); stage.close() } }
    }

    @Test fun `sample抽選と未完了を値0にせず表示しページ境界を保持する`() = fx {
        val input=SimulatorInputEditor().readInput()
        val result=assertIs<SimulatorMultipleHitResult.Sample>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1,input,SimulatorMultipleHitMode.Sample(77))))
        val text=result.details()
        assertTrue(text.contains("seed)=77")); assertTrue(text.contains("命中ID=")); assertTrue(text.contains("前:")); assertTrue(text.contains("後:"))
        assertTrue(text.contains("BREAK中")); assertTrue(text.contains("履歴=")); assertTrue(text.contains("抽選"))
        val many=result.copy(result=SameTickDamageResult.Completed(
            List(51) { result.result.rows.first().copy(hitId="stable-$it") },result.result.finalState))
        val snapshot=SimulatorComparisonSnapshot.capture(SimulatorMultipleHitRequest(1,input,SimulatorMultipleHitMode.Sample(77)),result,SimulatorComparisonVersion("core","dm","calc"))
        val zero=SimulatorDamageChange(0.0,0.0)
        val comparison=SimulatorComparisonResult.Sample(snapshot,snapshot,SimulatorDamageChanges(zero,zero,zero,zero),
            List(51) { if (it==50) SimulatorRowComparison.Removed("compare-$it",it,result.result.rows.first()) else SimulatorRowComparison.Added("compare-$it",it,result.result.rows.first()) },
            (0..50).associate { "compare-$it" to emptyList<SimulatorDrawComparison>() })
        assertEquals(2,comparison.detailPages()); assertTrue(comparison.details(1).contains("compare-50"))
        assertFalse(comparison.details(0).contains("compare-50")); assertFalse(comparison.details(1).contains("compare-0:"))
        assertTrue(comparison.details(1).contains("削除")); assertTrue(comparison.details(1).contains("基準:")); assertTrue(comparison.details(1).contains("補正の順序="))
        assertEquals(2,many.detailPages())
        assertTrue(many.details(1).contains("stable-50")); assertFalse(many.details(0).contains("stable-50"))
        for (incomplete in listOf(SimulatorMultipleHitResult.Cancelled(1),SimulatorMultipleHitResult.Unsupported(1,"unsupported"),SimulatorMultipleHitResult.LimitExceeded(1,65536,"limit"))) {
            assertTrue(incomplete.details().contains("ダメージ値なし")); assertFalse(incomplete.details().contains("算出:"))
        }
    }

    @Test fun `画面のローカル読込失敗は直前の固定装備を保持する`() {
        val root=createTempDirectory("simulator-view-local").toFile()
        val model=MutableItemBaseData(id="saved").apply { display.displayName="保存済み" }
        LocalEditorDataStore(root).save(EditorDataDescriptors.item,model.id,model)
        val view=fx { SimulatorView.load().also { Utility.createScene(AppScreen.SIMULATOR,customRoot=it.root); it.root.applyCss(); it.root.layout() } }
        try {
            fx {
                (view.root.lookup("#localRoot") as TextField).text=root.absolutePath
                button(view.root,"装備候補を更新").fire()
            }
            await { view.controller.localChoices.comboBox.items.any { it is SimulatorItemChoice.Saved } }
            fx {
                view.controller.localChoices.comboBox.value = view.controller.localChoices.comboBox.items.filterIsInstance<SimulatorItemChoice.Saved>().single()
                button(view.root,"選択を適用・再読込").fire()
            }
            await { (view.root.lookup("#importLabel") as Label).text.contains("取得版=") }
            fx {
                assertEquals("保存済み",view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData.display.displayName)
                root.resolve("item_data/stats/${model.id}.yml").delete()
                button(view.root,"選択を適用・再読込").fire()
            }
            await { (view.root.lookup("#importLabel") as Label).text.contains("読込失敗") }
            fx { assertEquals("saved",view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData.id) }
        } finally { fx { view.dispose() }; root.deleteRecursively() }
    }

    @Test fun `Running中の取込拒否は要求と装備入力を保全する`() = fx {
        val view=SimulatorView.load()
        try {
            val item=SimulatorImportedItem.fromEdited(MutableItemBaseData(id="first").apply { display.displayName="有効Item" })
            view.controller.acceptImportedItem(StatsPart.MAIN_HAND,item)
            view.controller.workspace.start(view.controller.inputEditor.readInput(),SimulatorMultipleHitMode.Expected)
            val running=assertIs<SimulatorRunState.Running>(view.controller.workspace.state)
            assertFailsWith<IllegalStateException> {
                view.controller.acceptImportedItem(StatsPart.MAIN_HAND,SimulatorImportedItem.fromEdited(MutableItemBaseData(id="rejected")))
            }
            assertSame(running,view.controller.workspace.state)
            assertEquals("first",view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData.id)
        } finally { view.dispose() }
    }

    @Test fun `取込候補で新たに有効になる非対応強化は装備revision結果基準Previewを置換しない`() {
        val view=fx { SimulatorView.load() }
        val stage=fx { Stage().apply { scene=Scene(view.root); show() } }
        try {
            val previous=fx {
                val slot=view.controller.inputEditor.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
                slot.type.value=ItemType.BOW
                slot.levels.fields.getValue(MeleeSushiEricEnchantment.FIRE_ASPECT).text="1"
                val bow=SimulatorImportedItem.fromEdited(item("bow",ItemType.BOW))
                view.controller.acceptImportedItem(StatsPart.MAIN_HAND,bow)
                view.controller.workspace.start(view.controller.inputEditor.readInput(),SimulatorMultipleHitMode.Expected)
                bow
            }
            await { view.controller.workspace.state is SimulatorRunState.Finished }
            fx {
                button(view.root,"基準を保持・更新").fire(); button(view.root,"比較").fire()
                val before=view.controller.workspace.state
                val beforeLabel=(view.root.lookup("#importLabel") as Label).text
                val baseline=(view.root.lookup("#comparisonLabel") as Label).text
                val slot=view.controller.inputEditor.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
                val preview=allNodes(slot.pane.content).filterIsInstance<ImageView>().single().image
                val error=assertFailsWith<IllegalArgumentException> {
                    view.controller.acceptImportedItem(StatsPart.MAIN_HAND,SimulatorImportedItem.fromEdited(item("sword",ItemType.SWORD)))
                }
                assertTrue(error.message!!.contains("非対応"))
                assertSame(before,view.controller.workspace.state)
                assertEquals(beforeLabel,(view.root.lookup("#importLabel") as Label).text)
                assertTrue(beforeLabel.contains(previous.revision))
                assertEquals(baseline,(view.root.lookup("#comparisonLabel") as Label).text)
                assertEquals("bow",slot.readItem()!!.baseData.id)
                assertSame(preview,allNodes(slot.pane.content).filterIsInstance<ImageView>().single().image)
                allNodes(slot.pane.content).filterIsInstance<Button>().single { it.text=="手動装備へ戻す" }.fire()
                slot.type.value=ItemType.SWORD
                assertFailsWith<IllegalArgumentException> {
                    view.controller.acceptImportedItem(StatsPart.HELMET,SimulatorImportedItem.fromEdited(item("helmet",ItemType.HELMET)))
                }
                assertFalse(view.controller.inputEditor.equipmentEditor.slots.getValue(StatsPart.HELMET).enabled.isSelected)
                assertSame(before,view.controller.workspace.state)
            }
        } finally { fx { view.dispose(); stage.close() } }
    }

    @Test fun `offhand候補は近接追撃だけ266で拒否し投射物と技能未達は許可する`(): Unit = fx {
        val view=SimulatorView.load()
        try {
            val form=view.controller.inputEditor
            val dagger=item("dagger",ItemType.SHORT_SWORD)
            form.hits.first().kind.value=SimulatorAttackKind.MELEE
            assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
            val error=assertFailsWith<IllegalArgumentException> {
                view.controller.acceptImportedItem(StatsPart.OFF_HAND,SimulatorImportedItem.fromEdited(dagger))
            }
            assertTrue(error.message!!.contains("追撃予約"))
            assertFalse(form.equipmentEditor.slots.getValue(StatsPart.OFF_HAND).enabled.isSelected)
            dagger.skillLevelRequirements[SkillType.COMBAT]=1
            view.controller.acceptImportedItem(StatsPart.OFF_HAND,SimulatorImportedItem.fromEdited(dagger))
            assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
            dagger.skillLevelRequirements.clear()
            form.hits.first().kind.value=SimulatorAttackKind.PROJECTILE
            view.controller.acceptImportedItem(StatsPart.OFF_HAND,SimulatorImportedItem.fromEdited(dagger))
            assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        } finally { view.dispose() }
    }

    private fun item(id:String,type:ItemType) = MutableItemBaseData(id=id,
        itemDetail=MutableItemDetail(content=type.createContent().toMutable())).apply { display.displayName=id }

    private fun allNodes(node:Node):Sequence<Node> = sequence {
        yield(node); if (node is Parent) for(child in node.childrenUnmodifiable) yieldAll(allNodes(child))
    }

    private fun button(root:Parent,text:String):Button {
        fun nodes(node:Node):Sequence<Node> = sequence {
            yield(node); if (node is Parent) for(child in node.childrenUnmodifiable) yieldAll(nodes(child))
        }
        return nodes(root).filterIsInstance<Button>().single { it.text==text }
    }
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while (!fx(condition)) { assertTrue(System.nanoTime()<deadline); Thread.sleep(10) }
    }
    private fun <T> fx(action:()->T):T {
        val ready=CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } } catch (_:IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20,TimeUnit.SECONDS)); val task=FutureTask(action); Platform.runLater(task); return task.get(30,TimeUnit.SECONDS)
    }
}
