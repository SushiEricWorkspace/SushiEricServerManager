package io.github.sushiericworkspace.sushiericservermanager.serverselect

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppFrame
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailure
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailureCode
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.SshResult
import io.github.sushiericworkspace.sushiericservermanager.config.*
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.TreeRow
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.serverselect.controller.ServerSelector
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonBase
import javafx.scene.control.DialogPane
import javafx.scene.control.TextField
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TreeView
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS as TestOS
import org.mockito.AdditionalAnswers
import org.mockito.Answers
import org.mockito.MockedConstruction
import org.mockito.Mockito
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@EnabledOnOs(TestOS.WINDOWS, TestOS.MAC)
class NormalHomeWorkspaceTest {
    @Test
    fun 通常接続の初回Homeで配置とIDを復元し未保存入力を再接続まで保持する() = withFixture { f ->
        val first = f.connect(f.a)
        f.phase = "A-tabs"
        awaitTabs(first, listOf(f.beta, f.alpha), f.alpha)
        f.phase = "A-input"
        showDisplay(first)
        awaitFx { first.lookupAll(".text-field").filterIsInstance<TextField>().any { it.text == "Alpha" } }
        val input = fx { first.lookupAll(".text-field").filterIsInstance<TextField>().single { it.text == "Alpha" } }
        fx { input.requestFocus() }
        awaitFx { input.scene.focusOwner === input }
        fx {
            assertEquals(listOf(f.beta, f.alpha), first.layout.sourceDocuments.getValue(f.localTabs).ids)
            assertEquals(f.alpha, first.layout.sourceDocuments.getValue(f.localTabs).selected)
            input.replaceText(0, input.length, "未保存の入力")
            assertTrue(first.move(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.HOME, DockSide.BOTTOM))
            assertEquals("未保存の入力", input.text)
            assertSame(first, (first.scene.root as AppFrame).center)
        }
        f.closeHome()
        val layoutA = fx { AppSettingsManager.load().workspaceLayouts.getValue(f.a.documentSource().directory) }
        val second = f.connect(f.b)
        f.phase = "B-tabs"
        awaitTabs(second, listOf(f.beta, f.alpha), f.beta)
        f.phase = "B-input"
        showDisplay(second)
        awaitFx { second.lookupAll(".text-field").filterIsInstance<TextField>().any { it.text == "Beta" } }
        fx {
            assertEquals(f.beta, second.layout.sourceDocuments.getValue(f.localTabs).selected)
            assertEquals(SplitDirection.HORIZONTAL, (second.layout.root as WorkspaceNode.Split).direction)
            assertEquals(layoutA, AppSettingsManager.load().workspaceLayouts.getValue(f.a.documentSource().directory))
        }
        f.closeHome()
        val reopened = f.connect(f.a)
        f.phase = "A-reopen-tabs"
        awaitTabs(reopened, listOf(f.beta, f.alpha), f.alpha)
        f.phase = "A-reopen-input"
        showDisplay(reopened)
        awaitFx { reopened.lookupAll(".text-field").filterIsInstance<TextField>().any { it.text == "未保存の入力" } }
        fx {
            assertEquals(f.alpha, reopened.layout.sourceDocuments.getValue(f.localTabs).selected)
            assertEquals(SplitDirection.VERTICAL, (reopened.layout.root as WorkspaceNode.Split).direction)
            val original = assertIs<StoreResult.Success<MutableItemBaseData>>(f.local.store.load(EditorDataDescriptors.item, f.alpha)).value
            assertEquals("Alpha", original.display.displayName)
            assertEquals(1, reopened.titles().count { it == WorkspaceFeature.ITEM_EDITOR })
        }
    }

    @Test
    fun 通常接続の認証失敗とホスト鍵取消ではHomeとworkspaceを生成しない() = withFixture { f ->
        for (code in listOf(SshFailureCode.PUBLIC_KEY_AUTHENTICATION_FAILED, SshFailureCode.HOST_KEY_NOT_APPROVED)) {
            val snapshot = fx { AppSettingsManager.load() }
            val replied = CompletableFuture<Unit>()
            var active = true
            val timer = fx {
                Timeline(KeyFrame(Duration.millis(30.0), {
                    Platform.runLater {
                        if (!active) return@runLater
                        val pane = Window.getWindows().filter { it.isShowing }.mapNotNull { it.scene?.root as? DialogPane }
                            .singleOrNull { it.headerText == "SSH処理を完了できませんでした" && (it.scene.window as? Stage)?.title == SshFailure(code).title } ?: return@runLater
                        val type = pane.buttonTypes.single { it.buttonData == ButtonBar.ButtonData.OK_DONE }
                        (pane.lookupButton(type) as ButtonBase).fire()
                        replied.complete(Unit)
                    }
                })).apply { cycleCount = Timeline.INDEFINITE; play() }
            }
            try {
                f.result.set(SshResult.Failure(SshFailure(code)))
                val selector = f.select(f.a)
                replied.get(10, TimeUnit.SECONDS)
                awaitFx { !selector.scene.root.isDisable && selector.isShowing && WorkspaceManager.current == null }
                fx {
                    assertNull(EditorSession.dataService)
                    assertEquals(snapshot, AppSettingsManager.load())
                    assertTrue(selector.isShowing)
                }
            } finally { fx { active = false; timer.stop() } }
        }
    }

    private fun awaitTabs(view: WorkspaceView, ids: List<String>, selected: String) {
        awaitFx {
            val strip = view.lookupAll(".workspace-id-tabs").filterIsInstance<ScrollPane>().singleOrNull()
                ?: return@awaitFx false
            val row = strip.content as? HBox ?: return@awaitFx false
            val actual = row.children.filterIsInstance<HBox>().map { tab ->
                tab.children.filterIsInstance<Label>().single().text.removeSuffix(" *") to
                    tab.pseudoClassStates.any { it.pseudoClassName == "selected" }
            }
            actual == ids.map { "ローカル / $it" to (it == selected) }
        }
    }

    private fun showDisplay(view: WorkspaceView) {
        awaitFx {
            view.lookupAll(".tree-view").filterIsInstance<TreeView<*>>().any { tree ->
                tree.root?.children?.any { it.value == TreeRow.Folder.Display } == true
            }
        }
        fx {
            val tree = view.lookupAll(".tree-view").filterIsInstance<TreeView<*>>().single { tree ->
                tree.root?.children?.any { it.value == TreeRow.Folder.Display } == true
            }
            tree.root.children.single { it.value == TreeRow.Folder.Display }.isExpanded = true
            view.applyCss()
            view.layout()
        }
    }

    private class Fixture {
        val token = UUID.randomUUID().toString().replace("-", "")
        val alpha = "entry_alpha_$token"
        val beta = "entry_beta_$token"
        val localTabs = WorkspaceFeature.ITEM_EDITOR.name + ":" + EditorDocumentSource.Local.directory
        val a = profile("A")
        val b = profile("B")
        val result = AtomicReference<SshResult<Unit>>(SshResult.Success(Unit))
        private val connected = AtomicReference<ServerProfile?>()
        private val previousSsh = EditorSession.sshManager
        private val settings = AppSettingsManager.load()
        private val profiles = SettingConfigManager.load()
        val local = EditorDataService(LocalEditorDataStore(FilePath.OFFLINE_DIR.toFile()),
            localDirectory = FilePath.OFFLINE_DIR.toFile())
        var phase = "prepare"
        private val originalWindows = Window.getWindows().toSet()
        private var sessionPrepared = false
        private var storeMock: MockedConstruction<RemoteEditorDataStore>? = null
        private var selector: Stage? = null
        private var home: Stage? = null
        private lateinit var controller: ServerSelector
        private val ssh = Mockito.mock(SshManager::class.java, Mockito.withSettings().defaultAnswer { invocation ->
            when (invocation.method.name) {
                "connect" -> result.get().also { if (it is SshResult.Success) connected.set(invocation.getArgument(0)) }
                "disconnect" -> { connected.set(null); null }
                "isConnected", "isSftpActive" -> connected.get() != null
                "getCurrentProfile" -> connected.get()
                "getCompatibility" -> ManagerCompatibility.Unchecked
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        fun prepare() {
            assertNull(EditorSession.dataService)
            assertNull(WorkspaceManager.current)
            sessionPrepared = true
            EditorSession.prepareOnlineMode()
            EditorSession.sshManager = ssh
            val remote = object : EditorDataStore by InMemoryEditorDataStore() {
                override val kind = EditorDataStoreKind.REMOTE
                override val identity get() = connected.get()!!.name
                override val documentSource get() = connected.get()!!.documentSource()
            }
            storeMock = Mockito.mockConstruction(RemoteEditorDataStore::class.java,
                Mockito.withSettings().defaultAnswer(AdditionalAnswers.delegatesTo<RemoteEditorDataStore>(remote)))
            for ((id, name) in listOf(alpha to "Alpha", beta to "Beta")) {
                val data = MutableItemBaseData(id = id).apply { display.displayName = name }
                assertIs<StoreResult.Success<Unit>>(local.store.save(EditorDataDescriptors.item, id, data))
            }
            val layouts = listOf(a to alpha, b to beta).associate { (profile, selected) ->
                profile.documentSource().directory to WorkspaceLayout(
                    root = WorkspaceNode.Split(SplitDirection.HORIZONTAL, WorkspaceNode.Feature(WorkspaceFeature.HOME), WorkspaceNode.Feature(WorkspaceFeature.ITEM_EDITOR), 0.35),
                    active = WorkspaceFeature.ITEM_EDITOR,
                    sourceDocuments = mapOf(localTabs to IdTabsState(listOf(beta, alpha), selected)),
                    documentSources = mapOf(WorkspaceFeature.ITEM_EDITOR to EditorDocumentSource.Local.directory))
            }
            AppSettingsManager.save(settings.copy(workspaceLayouts = settings.workspaceLayouts + layouts))
            assertTrue(SettingConfigManager.saveAndVerify(ServerConfig(listOf(a, b))))
        }

        fun select(profile: ServerProfile): Stage = fx {
            phase = if (profile == a) "select-A" else "select-B"
            selector?.close()
            val loader = FXMLLoader(javaClass.getResource(AppScreen.SERVER_SELECT.fxml!!))
            val root = loader.load<Parent>()
            controller = loader.getController()
            Stage().apply { scene = Utility.createScene(AppScreen.SERVER_SELECT, customRoot = root); show(); selector = this }
                .also { ServerSelector::class.java.getDeclaredMethod("handleServerSelection", ServerProfile::class.java).apply { isAccessible = true }.invoke(controller, profile) }
        }

        fun connect(profile: ServerProfile): WorkspaceView {
            result.set(SshResult.Success(Unit))
            val selection = select(profile)
            awaitFx { WorkspaceManager.current != null }
            return fx {
                val view = WorkspaceManager.requireWorkspace()
                home = view.scene.window as Stage
                assertFalse(selection.isShowing)
                assertSame(view, (home!!.scene.root as AppFrame).center)
                assertEquals(profile.documentSource().directory, EditorSession.dataService!!.cacheIdentity)
                view
            }
        }

        fun closeHome() {
            if (!sessionPrepared) return
            if (home == null) home = fx { WorkspaceManager.current?.scene?.window as? Stage }
            if (home != null) {
                val done = CompletableFuture<Boolean>()
                fx { EditorWindowManager.closeAll { done.complete(it) } }
                assertTrue(done.get(15, TimeUnit.SECONDS))
                fx { home?.close(); home = null }
            }
            EditorSession.resetMode()
            fx { assertNull(WorkspaceManager.current) }
        }

        fun close() {
            try { closeHome() } finally {
                try {
                    fx {
                        Window.getWindows().filterIsInstance<Stage>().filter { it !in originalWindows }.toList().forEach(Stage::close)
                        storeMock?.close()
                        EditorSession.sshManager = previousSsh
                        AppSettingsManager.save(settings)
                        assertTrue(SettingConfigManager.saveAndVerify(profiles))
                    }
                } finally { local.closeIo() }
            }
        }

        fun diagnostic(): String {
            val view = WorkspaceManager.current?.takeIf { it.scene?.window === home }
            val strip = view?.lookupAll(".workspace-id-tabs")?.filterIsInstance<ScrollPane>()?.singleOrNull()
            val tabs = (strip?.content as? HBox)?.children?.filterIsInstance<HBox>().orEmpty()
            val visible = tabs.take(8).map { tab ->
                val text = tab.children.filterIsInstance<Label>().singleOrNull()?.text?.removeSuffix(" *")
                val own = when (text) {
                    "ローカル / $alpha" -> "local:alpha"
                    "ローカル / $beta" -> "local:beta"
                    else -> "other"
                }
                own + ":selected=" + tab.pseudoClassStates.any { it.pseudoClassName == "selected" }
            }
            val saved = local.items.localDocuments()
            val resources = (saved as? StoreResult.Success)?.value.orEmpty()
            val cached = EditorWindowManager.openDocumentIds(EditorDocumentSource.Local, "items")
            val profile = when (EditorSession.sshManager.currentProfile) { a -> "A"; b -> "B"; null -> "none"; else -> "other" }
            val source = when (view?.layout?.documentSources?.get(WorkspaceFeature.ITEM_EDITOR)) {
                EditorDocumentSource.Local.directory -> "local"
                a.documentSource().directory -> "A"
                b.documentSource().directory -> "B"
                null -> "none"
                else -> "other"
            }
            return ("HOME_RESTORE_DIAGNOSTIC phase=$phase profile=$profile source=$source ownedView=${view != null} " +
                "tabCount=${tabs.size} tabs=$visible " +
                "savedAlpha=${resources.any { it.key.id == alpha && it.saved != null && !it.savedDeleted }} savedBeta=${resources.any { it.key.id == beta && it.saved != null && !it.savedDeleted }} " +
                "savedReadSuccess=${saved is StoreResult.Success} cachedAlpha=${alpha in cached} cachedBeta=${beta in cached}")
                .also { check(it.toByteArray(Charsets.UTF_8).size <= 4096) }
        }

        private fun profile(name: String) = ServerProfile("entry-$name-$token", "fixture.invalid", 22, "fixture", "/fixture/$name", "unused", profileId = UUID.randomUUID().toString())
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        startFx()
        val fixture = fx { Fixture() }
        var failure: Throwable? = null
        try { fx { fixture.prepare() }; action(fixture) }
        catch (error: Throwable) {
            failure = error
            try { println(fx { fixture.diagnostic() }) }
            catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        }
        finally {
            try { fixture.close() }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    companion object {
        private fun startFx() {
            val root = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
            assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(root).toAbsolutePath().normalize()))
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
        }
        private fun <T> fx(action: () -> T): T {
            val task = FutureTask(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS)
        }
        private fun awaitFx(predicate: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!fx(predicate)) { assertTrue(System.nanoTime() < end); Thread.sleep(20) }
        }
    }
}
