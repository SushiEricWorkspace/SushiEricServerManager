package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.toMutable
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.BowData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.ItemDetail
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemSimulatorImport
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 計算受入の独立値134.5/139.536を、両接続モードの実取込画面から比較まで運びます。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SimulatorUiAcceptanceTest {
    @Test fun `保存済み装備から条件変更と詳細比較まで両接続モードで接続する`(): Unit = accept(edited = false)
    @Test fun `編集中装備から条件変更と詳細比較まで両接続モードで接続する`(): Unit = accept(edited = true)

    private fun accept(edited: Boolean) {
        val modeField = EditorSession::class.java.getDeclaredField("mode").apply { isAccessible = true }
        val serviceField = EditorSession::class.java.getDeclaredField("dataService").apply { isAccessible = true }
        val previousMode = EditorSession.mode
        val previousService = EditorSession.dataService
        val temporary = createTempDirectory("simulator-ui-acceptance").toFile()
        val accesses = AtomicInteger()
        val blocked = EditorDataStore::class.java.cast(Proxy.newProxyInstance(
            EditorDataStore::class.java.classLoader, arrayOf(EditorDataStore::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getKind" -> EditorDataStoreKind.REMOTE
                "getIdentity" -> "acceptance-remote"
                "getOfflineDirectory" -> null
                "isAvailable" -> false
                else -> { accesses.incrementAndGet(); error("セッションのストアを参照: ${method.name}") }
            }
        })
        val service = EditorDataService(blocked, temporary.resolve("autosave"))
        val model = ItemBaseData(id = "accept_bow", itemDetail = ItemDetail(content = BowData()),
            stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0), statMultipliers = mapOf(
                StatsType.PHYSICS_DAMAGE to listOf(ItemStatMultiplier(StatsPartTarget.Self, 1.5),
                    ItemStatMultiplier(StatsPartTarget.All, 1.1),
                    ItemStatMultiplier(StatsPartTarget.Parts(setOf(StatsPart.MAIN_HAND)), 1.2))))
            .toMutable().apply { display.displayName = "受入弓" }
        assertIs<StoreResult.Success<Unit>>(LocalEditorDataStore(temporary).save(EditorDataDescriptors.item, model.id, model))
        val original = model.deepCopy()
        try {
            for (mode in listOf(AppMode.OFFLINE, AppMode.ONLINE)) {
                lateinit var view: SimulatorView
                var source: Stage? = null
                var capture: ItemSimulatorImport? = null
                try {
                    fx {
                        modeField.set(EditorSession, mode); serviceField.set(EditorSession, service)
                        SimulatorWindowManager.open(null)
                        view = SimulatorWindowManager::class.java.getDeclaredField("activeView").apply { isAccessible = true }
                            .get(SimulatorWindowManager) as SimulatorView
                        val form = view.controller.inputEditor
                        form.baseStats.fields.forEach { (key, field) -> field.text = if (key in setOf(StatsType.PHYSICS_DAMAGE)) "100" else "0" }
                        form.baseStats.fields.getValue(StatsType.MAX_HEALTH).text = "1000"
                        form.mobStats.fields.forEach { (key, field) -> field.text = if (key == EntityStatsType.MAX_HEALTH) "1000" else "0" }
                        button(view.root, "命中行を追加").fire()
                        if (edited) {
                            val trigger = Button("取込"); val message = Label(); val content = VBox(trigger, message)
                            source = Stage().apply { title = "Item編集受入"; scene = Scene(content); show() }
                            capture = ItemSimulatorImport(content, trigger, message, { false },
                                { SimulatorImportedItem.fromEdited(model) },
                                { SimulatorWindowManager.importItem(source, StatsPart.MAIN_HAND, it) })
                            trigger.fire()
                            assertTrue(message.text.contains("取り込み"))
                        } else {
                            (view.root.lookup("#localRoot") as TextField).text = temporary.absolutePath
                            (view.root.lookup("#localId") as TextField).text = model.id
                            button(view.root, "取得・再読込").fire()
                        }
                    }
                    await { view.controller.inputEditor.equipmentEditor.readInput().items.containsKey(StatsPart.MAIN_HAND) }
                    val window = fx { view.root.scene.window }
                    val ids = fx { view.controller.inputEditor.hits.map { it.hitId } }
                    fx { button(view.root, "計算").fire() }
                    await { view.controller.workspace.state is SimulatorRunState.Finished }
                    fx {
                        verify(view, ids, 134.5, 269.0)
                        button(view.root, "基準を保持・更新").fire()
                        view.controller.inputEditor.skills.fields.getValue(SkillType.COMBAT).text = "1"
                        button(view.root, "計算").fire()
                    }
                    await { (view.controller.workspace.state as? SimulatorRunState.Finished)?.request?.generation == 2L }
                    fx {
                        verify(view, ids, 139.536, 279.072)
                        button(view.root, "比較").fire()
                        val comparison = (view.root.lookup("#comparisonLabel") as Label).text
                        for ((label, values) in listOf("算出" to (269.0 to 279.072), "純HP" to (269.0 to 279.072),
                            "OH" to (0.0 to 0.0), "適用" to (269.0 to 279.072))) {
                            val match = assertNotNull(Regex("$label: ([-0-9.Ee]+) → ([-0-9.Ee]+)").find(comparison), comparison)
                            assertEquals(values.first, match.groupValues[1].toDouble(), 1e-9)
                            assertEquals(values.second, match.groupValues[2].toDouble(), 1e-9)
                        }
                        assertSame(window, view.root.scene.window)
                        assertEquals(1, Stage.getWindows().filterIsInstance<Stage>().count { it.title.contains("戦闘シミュレーター") })
                        ids.forEach { assertTrue(comparison.contains("hitId=$it")) }
                        assertEquals(original, model)
                        assertEquals(0, accesses.get(), "ONLINEでも明示local以外のStore I/Oをしない")
                    }
                } finally { fx { capture?.dispose(); source?.close(); SimulatorWindowManager.close() } }
            }
        } finally {
            fx { modeField.set(EditorSession, previousMode); serviceField.set(EditorSession, previousService) }
            service.itemReferences.close(); service.closeIo(); temporary.deleteRecursively()
        }
    }

    private fun verify(view: SimulatorView, ids: List<String>, rowDamage: Double, total: Double) {
        val finished = assertIs<SimulatorRunState.Finished>(view.controller.workspace.state)
        val result = assertIs<SimulatorMultipleHitResult.Expected>(finished.result).result
        assertEquals(ids, result.rows.map { it.hitId })
        result.rows.forEach { row ->
            assertEquals(rowDamage, row.meanCalculatedDamage, 1e-9)
            assertEquals(rowDamage, row.meanHealthDamage, 1e-9)
            assertEquals(0.0, row.meanOverHealDamage)
            assertEquals(rowDamage, row.meanAppliedDamage, 1e-9)
        }
        assertEquals(total, result.meanCalculatedDamage, 1e-9)
        assertEquals(total, result.meanHealthDamage, 1e-9)
        assertEquals(0.0, result.meanOverHealDamage)
        assertEquals(total, result.meanAppliedDamage, 1e-9)
        val details = (view.root.lookup("#detailsArea") as TextArea).text
        ids.forEach { assertTrue(details.contains("hitId=$it")) }
        val summary = (view.root.lookup("#resultLabel") as Label).text
        for ((label, expected) in listOf("算出" to total, "純HP減少" to total, "OH吸収" to 0.0, "適用量" to total)) {
            val match = assertNotNull(Regex("$label: ([-0-9.Ee]+)").find(summary), summary)
            assertEquals(expected, match.groupValues[1].toDouble(), 1e-9)
        }
        for (id in ids) {
            val line = details.lineSequence().single { it.startsWith("行") && it.contains("hitId=$id ") }
            for ((label, expected) in listOf("算出" to rowDamage, "純HP" to rowDamage, "OH" to 0.0, "適用" to rowDamage)) {
                val match = assertNotNull(Regex("$label=([-0-9.Ee]+)").find(line), line)
                assertEquals(expected, match.groupValues[1].toDouble(), 1e-9)
            }
        }
    }

    private fun button(root: Parent, text: String): Button {
        fun nodes(node: Node): Sequence<Node> = sequence {
            yield(node); if (node is Parent) node.childrenUnmodifiable.forEach { yieldAll(nodes(it)) }
        }
        return nodes(root).filterIsInstance<Button>().single { it.text == text }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!fx(condition)) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10) }
    }
    private fun <T> fx(action: () -> T): T {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } } catch (_: IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action); Platform.runLater(task); return task.get(30, TimeUnit.SECONDS)
    }
}
