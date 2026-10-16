package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollKind
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.toMutable
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.util.concurrent.*
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SimulatorItemChoicesTest {
    @Test fun `一覧は存在しない保存先を作らず名前と階層IDをtyped読取する`() {
        val root = createTempDirectory("simulator-choices").toFile()
        try {
            val missing = root.resolve("missing")
            assertTrue(assertIs<StoreResult.Success<List<SimulatorItemChoice.Saved>>>(listSimulatorItems(missing)).value.isEmpty())
            assertFalse(missing.exists())
            val model = ItemBaseData(id = "weapons.bow", itemDetail = ItemDetail(content = BowData())).toMutable().apply { display.displayName = "検索用の弓" }
            assertIs<StoreResult.Success<Unit>>(LocalEditorDataStore(root).save(EditorDataDescriptors.item, model.id, model))
            val before = root.walkTopDown().map { it.relativeTo(root).path to if (it.isFile) it.readBytes().toList() else null }.toList()
            val choices = assertIs<StoreResult.Success<List<SimulatorItemChoice.Saved>>>(listSimulatorItems(root)).value
            assertEquals(listOf(SimulatorItemChoice.Saved("weapons.bow", "検索用の弓")), choices)
            assertEquals(before, root.walkTopDown().map { it.relativeTo(root).path to if (it.isFile) it.readBytes().toList() else null }.toList())
            root.resolve("item_data/stats/broken.yml").writeText("not: [valid")
            assertTrue(assertIs<StoreResult.Success<List<SimulatorItemChoice.Saved>>>(listSimulatorItems(root)).value.any { it.id == "broken" && it.name == null })
            assertIs<StoreResult.Failure>(loadSimulatorItem(root, "broken"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `候補と本文の古通知はNone変更と計算世代とsessionとdisposeで反映しない`() {
        val queue = ChoiceExecutor()
        val notifications = ArrayDeque<() -> Unit>()
        val received = mutableListOf<String>()
        var generation = 1L
        var current = true
        val loader = SimulatorItemLoader({ current }, { generation }, { received += "item" }, queue,
            { notifications += it }, { _, _ -> StoreResult.Success(SimulatorImportedItem(ItemBaseData(id="test"), "test")) },
            { root, _ -> received += root.name }, { StoreResult.Success(emptyList()) })
        fun complete() { queue.tasks.removeFirst().run(); notifications.removeFirst()() }
        loader.readChoices(java.io.File("old")); loader.readChoices(java.io.File("new")); complete(); complete()
        assertEquals(listOf("new"), received)
        loader.read(java.io.File("."), "test"); loader.invalidate(); complete()
        loader.readChoices(java.io.File("generation")); generation++; complete()
        loader.readChoices(java.io.File("session")); current=false; complete(); current=true
        loader.readChoices(java.io.File("disposed")); loader.dispose(); complete()
        assertEquals(listOf("new"), received)
    }
}

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SimulatorUsabilityViewTest {
    @Test fun `検索となしは明示適用だけで装備を変え元取込と固定結果を復活させない`() {
        val root = createTempDirectory("simulator-search-view").toFile()
        val model = ItemBaseData(id="search_bow", itemDetail=ItemDetail(content=BowData())).toMutable().apply { display.displayName="選べる弓" }
        LocalEditorDataStore(root).save(EditorDataDescriptors.item,model.id,model)
        val view=fx { SimulatorView.load().also { Utility.createScene(AppScreen.SIMULATOR,customRoot=it.root); it.root.applyCss(); it.root.layout() } }
        try {
            fx {
                (view.root.lookup("#localRoot") as TextField).text=root.absolutePath
                button(view.root,"装備候補を更新").fire()
            }
            await { view.controller.localChoices.comboBox.items.size == 2 }
            fx {
                val choice = view.controller.localChoices
                assertSame(SimulatorItemChoice.None,choice.comboBox.value)
                choice.searchField.text="選べる"
                assertIs<SimulatorItemChoice.Saved>(choice.comboBox.value)
                assertTrue(choice.comboBox.converter.toString(choice.comboBox.value).contains("選べる弓 / search_bow"))
                assertFalse(choice.comboBox.isEditable)
                assertTrue(view.controller.inputEditor.equipmentEditor.readInput().items.isEmpty())
                choice.searchField.text="一致しない任意ID"
                assertTrue((view.root.lookup("#importLabel") as Label).text.contains("一致する装備がありません"))
                assertTrue(view.controller.inputEditor.equipmentEditor.readInput().items.isEmpty())
                choice.searchField.text=""
                choice.comboBox.value=choice.comboBox.items.filterIsInstance<SimulatorItemChoice.Saved>().single()
                button(view.root,"選択を適用・再読込").fire()
            }
            await { view.controller.inputEditor.equipmentEditor.readInput().items.containsKey(StatsPart.MAIN_HAND) }
            fx { view.controller.workspace.start(view.controller.inputEditor.readInput(), SimulatorMultipleHitMode.Expected) }
            await { view.controller.workspace.state is SimulatorRunState.Finished }
            fx {
                val before=view.controller.workspace.state
                button(view.root,"基準を保持・更新").fire()
                view.controller.localChoices.searchField.text="存在しない検索語"
                button(view.root,"選択を適用・再読込").fire()
                assertSame(before,view.controller.workspace.state)
                assertEquals("search_bow",view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData.id)
                assertTrue((view.root.lookup("#importLabel") as Label).text.contains("検索欄を空"))
                view.controller.localChoices.searchField.text=""
                view.controller.localChoices.comboBox.value=SimulatorItemChoice.None
                assertTrue(view.controller.inputEditor.equipmentEditor.readInput().items.containsKey(StatsPart.MAIN_HAND))
                button(view.root,"選択を適用・再読込").fire()
                assertTrue(view.controller.inputEditor.equipmentEditor.readInput().items.isEmpty())
                assertSame(before,view.controller.workspace.state)
                assertTrue((view.root.lookup("#comparisonLabel") as Label).text.contains("比較基準"))
                val slot=view.controller.inputEditor.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
                assertTrue(nodes(slot.pane).filterIsInstance<ImageView>().all { it.image == null })
                slot.enabled.isSelected=true
                assertEquals("manual_main_hand",view.controller.inputEditor.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData.id)
            }
        } finally { fx { view.dispose() }; root.deleteRecursively() }
    }

    @Test fun `弓射撃と不整合になるNoneとRunning変更を拒否し旧入力結果を保持する`(): Unit = fx {
        val view=SimulatorView.load()
        try {
            val form=view.controller.inputEditor
            view.controller.acceptImportedItem(StatsPart.MAIN_HAND,SimulatorImportedItem(ItemBaseData(id="bow",itemDetail=ItemDetail(content=BowData())).toMutable().apply { display.displayName="弓" }.freeze(),"fixture"))
            form.shotProfile.value="BOW"
            val old=form.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData
            val state=view.controller.workspace.state
            assertFailsWith<IllegalArgumentException> { view.controller.unequip(StatsPart.MAIN_HAND) }
            assertEquals(old,form.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData)
            assertSame(state,view.controller.workspace.state)
            view.controller.workspace.start(form.readInput(),SimulatorMultipleHitMode.Expected)
            assertFailsWith<IllegalStateException> { view.controller.unequip(StatsPart.MAIN_HAND) }
            assertEquals(old,form.equipmentEditor.readInput().items.getValue(StatsPart.MAIN_HAND).baseData)
        } finally { view.dispose() }
    }

    @Test fun `なしは除外部位の不正入力を読まず他部位の不正入力では変更しない`(): Unit = fx {
        val view=SimulatorView.load()
        try {
            val slots=view.controller.inputEditor.equipmentEditor.slots
            val main=slots.getValue(StatsPart.MAIN_HAND)
            main.enabled.isSelected=true
            main.stats.fields.values.first().text="未確定"
            view.controller.unequip(StatsPart.MAIN_HAND)
            assertFalse(main.enabled.isSelected)
            main.enabled.isSelected=true
            val other=slots.entries.first { it.key != StatsPart.MAIN_HAND }.value
            other.enabled.isSelected=true
            other.stats.fields.values.first().text="未確定"
            assertFailsWith<IllegalArgumentException> { view.controller.unequip(StatsPart.MAIN_HAND) }
            assertTrue(main.enabled.isSelected)
        } finally { view.dispose() }
    }

    @Test fun `結果の列挙と抽選を訳し4内訳と識別IDを変えない`(): Unit = fx {
        for (value in SameTickDamageStatus.entries + DamagePreparationFailureReason.entries + DamagePreparationPhase.entries + DamageRollKind.entries) {
            assertNotEquals(value.name,simulatorLabel(value))
        }
        val form=SimulatorInputEditor()
        val input=form.readInput()
        val result=assertIs<SimulatorMultipleHitResult.Sample>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1,input,SimulatorMultipleHitMode.Sample(1))))
        val text=result.details()
        assertTrue(text.contains("適用済み"));assertTrue(text.contains("命中ID=${form.hits.single().hitId}"))
        assertTrue(text.contains("純HP減少=100.0"));assertTrue(text.contains("追加HP(OH)吸収=0.0"));assertTrue(text.contains("実適用=100.0"))
        assertFalse(text.contains("SameTickCombatantState"));assertFalse(text.contains("NotReached("));assertFalse(text.contains("DamageFormulaResult("))
        assertTrue(text.contains("判定未到達"));assertTrue(text.contains("補正の順序"))
    }

    @Test fun `通常と最小Stageで16pxと入力詳細比較のスクロール領域を確保する`() {
        val view=fx { SimulatorView.load() }
        val stage=fx { Stage().apply { title="Simulator281受入";scene=Utility.createScene(AppScreen.SIMULATOR,customRoot=view.root);show() } }
        try {
            val normalHeight=fx { minOf(900.0, javafx.stage.Screen.getPrimary().visualBounds.height - 40) }
            fx { println("Screen visualBounds=${javafx.stage.Screen.getPrimary().visualBounds}, outputScale=${javafx.stage.Screen.getPrimary().outputScaleX}x${javafx.stage.Screen.getPrimary().outputScaleY}") }
            for ((name,size) in listOf("normal" to (1100.0 to normalHeight),"minimum" to (850.0 to 600.0))) {
                fx { stage.width=size.first;stage.height=size.second }
                await { stage.scene.width in (size.first-50)..size.first && stage.scene.height in (size.second-100)..size.second }
                fx {
                    view.root.applyCss();view.root.layout()
                    println("$name: Stage=${stage.width}x${stage.height}, Scene=${stage.scene.width}x${stage.scene.height}")
                    val text=view.root.lookup("#detailsArea") as TextArea
                    assertTrue(text.font.size>=16.0)
                    assertTrue(text.height>=200.0)
                    val calculate=button(view.root,"計算")
                    assertTrue(calculate.font.size>=16.0)
                    assertTrue(nodes(view.root).filterIsInstance<ScrollPane>().count()>=3)
                }
                val targets=fx { listOf(button(view.root,"計算"),button(view.root,"比較"),view.root.lookup("#detailsArea")) }
                for (target in targets) {
                    fx {
                        val page=view.root as ScrollPane
                        val contentY=page.content.sceneToLocal(target.localToScene(target.boundsInLocal)).minY
                        page.vvalue=((contentY-16.0) / (page.content.layoutBounds.height-page.viewportBounds.height)).coerceIn(0.0,1.0)
                    }
                    await {
                        val bounds=target.localToScene(target.boundsInLocal)
                        bounds.maxY>=0 && bounds.minY<stage.scene.height
                    }
                }
                fx {
                    val image=view.root.snapshot(null,null)
                    val png=BufferedImage(image.width.toInt(),image.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                    for(y in 0 until png.height) for(x in 0 until png.width) png.setRGB(x,y,image.pixelReader.getArgb(x,y))
                    ImageIO.write(png,"png",java.io.File("build/simulator281-$name.png"))
                }
            }
        } finally { fx { view.dispose();stage.close() } }
    }

    private fun nodes(node:Node):Sequence<Node> = sequence {
        yield(node);if(node is Parent)node.childrenUnmodifiable.forEach { yieldAll(nodes(it)) }
    }
    private fun button(root:Parent,text:String)=nodes(root).filterIsInstance<Button>().single { it.text==text }
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(!fx(condition)){assertTrue(System.nanoTime()<deadline);Thread.sleep(10)}
    }
    private fun <T> fx(action:()->T):T {
        val ready=CountDownLatch(1)
        try{Platform.startup{Platform.setImplicitExit(false);ready.countDown()}}catch(_:IllegalStateException){ready.countDown()}
        assertTrue(ready.await(20,TimeUnit.SECONDS));val task=FutureTask(action);Platform.runLater(task);return task.get(30,TimeUnit.SECONDS)
    }
}

private class ChoiceExecutor : AbstractExecutorService() {
    val tasks=ArrayDeque<Runnable>()
    private var closed=false
    override fun execute(command:Runnable){tasks+=command}
    override fun shutdown(){closed=true}
    override fun shutdownNow():MutableList<Runnable>{closed=true;return mutableListOf()}
    override fun isShutdown()=closed
    override fun isTerminated()=closed
    override fun awaitTermination(timeout:Long,unit:TimeUnit)=closed
}
