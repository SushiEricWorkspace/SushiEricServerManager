package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SimulatorItemImportTest {
    @Test fun `typedローカル取得は保存済みの一版を固定し明示再取得まで変えない`() {
        val root = createTempDirectory("simulator-import").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val model = MutableItemBaseData(id = "sword").apply { display.displayName = "before" }
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.item, model.id, model))
            val first = assertIs<StoreResult.Success<SimulatorImportedItem>>(loadSimulatorItem(root, "sword")).value
            model.display.displayName = "after"
            store.save(EditorDataDescriptors.item, model.id, model)
            assertEquals("before", first.item.display.displayName)
            val second = assertIs<StoreResult.Success<SimulatorImportedItem>>(loadSimulatorItem(root, "sword")).value
            assertEquals("after", second.item.display.displayName)
            assertNotEquals(first.revision, second.revision)
            root.resolve("item_data/stats/sword.yml").delete()
            assertEquals(StoreErrorCode.FILE_NOT_FOUND, assertIs<StoreResult.Failure>(loadSimulatorItem(root, "sword")).error.code)
            assertEquals("before", first.item.display.displayName)
        } finally { root.deleteRecursively() }
    }

    @Test fun `未保存copyは元modelとoriginalを変更せず隔離する`() {
        val model = MutableItemBaseData(id = "edited").apply { display.displayName = "fixed" }
        val original = model.deepCopy()
        val item = SimulatorImportedItem.fromEdited(model)
        assertEquals(original, model)
        model.display.displayName = "later"
        assertEquals("fixed", item.item.display.displayName)
        assertTrue(item.source.contains("未保存"))
    }

    @Test fun `不正IDと壊れた保存値を成功へ変換しない`() {
        val root = createTempDirectory("simulator-invalid").toFile()
        try {
            assertEquals(StoreErrorCode.INVALID_ID, assertIs<StoreResult.Failure>(loadSimulatorItem(root,"../outside")).error.code)
            root.resolve("item_data/stats").mkdirs()
            root.resolve("item_data/stats/broken.yml").writeText("not: [valid")
            assertIs<StoreResult.Failure>(loadSimulatorItem(root,"broken"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `古い読込と計算世代変更と終了切替の通知は反映しない`() {
        val executor = QueuedExecutor()
        val notifications = mutableListOf<() -> Unit>()
        val received = mutableListOf<String>()
        var generation = 1L
        var current = true
        val loader = SimulatorItemLoader({ current }, { generation }, { received += assertIs<StoreResult.Success<SimulatorImportedItem>>(it).value.item.id },
            executor, { notifications += it }, { _, id -> StoreResult.Success(SimulatorImportedItem(MutableItemBaseData(id=id).freeze(), "test")) })
        loader.read(java.io.File("."), "old"); loader.read(java.io.File("."), "new")
        executor.tasks.removeFirst().run(); notifications.removeFirst()()
        assertTrue(received.isEmpty())
        executor.tasks.removeFirst().run(); notifications.removeFirst()()
        assertEquals(listOf("new"),received)
        loader.read(java.io.File("."),"generation"); executor.tasks.removeFirst().run(); generation++; notifications.removeFirst()()
        loader.read(java.io.File("."),"session"); executor.tasks.removeFirst().run(); current=false; notifications.removeFirst()()
        assertEquals(listOf("new"),received)
        current=true
        loader.read(java.io.File("."),"disposed"); executor.tasks.removeFirst().run(); loader.dispose(); notifications.removeFirst()()
        assertEquals(listOf("new"),received)
    }
}

private class QueuedExecutor : AbstractExecutorService() {
    val tasks = ArrayDeque<Runnable>()
    private var closed = false
    override fun execute(command: Runnable) { tasks.addLast(command) }
    override fun shutdown() { closed=true }
    override fun shutdownNow(): MutableList<Runnable> { closed=true; return mutableListOf() }
    override fun isShutdown() = closed
    override fun isTerminated() = closed
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
}
