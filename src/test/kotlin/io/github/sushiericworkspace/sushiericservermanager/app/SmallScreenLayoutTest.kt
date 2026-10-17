package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.detail.MutablePotionData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.MoneyHistoryController
import io.github.sushiericworkspace.sushiericservermanager.editor.component.DropItemEditorDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.component.PotionEffectEditorDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.OfflineUploadDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadDataCategory
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadScanResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.*
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import javafx.scene.layout.StackPane
import javafx.stage.Window
import kotlin.test.assertEquals
import io.github.sushiericworkspace.sushiericservermanager.feature.console.ConsoleController
import io.github.sushiericworkspace.sushiericservermanager.feature.simulator.SimulatorController
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Screen
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SmallScreenLayoutTest {
    private val visited = mutableSetOf<String>()
    @Test fun `縮小した実FXMLでも操作列と入力へスクロールで到達する`() {
        onFx { Screen.getScreens().forEach { println("#293 screen bounds=${it.bounds} visualBounds=${it.visualBounds} outputScale=${it.outputScaleX}x${it.outputScaleY} dpi=${it.dpi}") } }
        val screens = listOf(AppScreen.MODE_SELECT, AppScreen.HOME, AppScreen.SIMULATOR,
            AppScreen.CONSOLE, AppScreen.DASHBOARD, AppScreen.SERVER_CONTROL, AppScreen.MOD_CONFIG,
            AppScreen.MONEY_HISTORY, AppScreen.SERVER_CREATE, AppScreen.SERVER_EDIT, AppScreen.SERVER_SELECT)
        for (screen in screens) {
            val (loader, stage) = onFx {
                val loader = FXMLLoader(javaClass.getResource(screen.fxml!!))
                val root = loader.load<Parent>()
                if (screen == AppScreen.MONEY_HISTORY) loader.getController<MoneyHistoryController>().initialize(InMemoryEditorDataStore(), ServerManagementClient())
                loader to Stage().apply {
                    title = "小画面 #293 ${screen.name}"
                    scene = Utility.createScene(screen, 900.0, 460.0, root)
                    show()
                }
            }
            try {
                for ((width, height) in listOf(900.0 to 460.0, 600.0 to 400.0, 1300.0 to 680.0)) {
                    onFx { stage.width = width; stage.height = height }
                    settle()
                    val focusTarget = onFx {
                        val root = stage.scene.root
                        root.lookupAll(".titled-pane").filterIsInstance<TitledPane>().forEach { it.isAnimated = false; it.isExpanded = true }
                        root.applyCss(); root.layout()
                        println("#293 ${screen.name} stage=${stage.width}x${stage.height} scene=${stage.scene.width}x${stage.scene.height} " +
                            "visualBounds=${Screen.getPrimary().visualBounds} outputScale=${stage.outputScaleX}x${stage.outputScaleY}")
                        visited.clear()
                        checkActions(root, root)
                        root.lookupAll(".tab-pane").filterIsInstance<TabPane>().forEach { tabs ->
                            tabs.tabs.forEach { tab ->
                                tabs.selectionModel.select(tab)
                                root.applyCss(); root.layout()
                                checkActions(root, root)
                            }
                        }
                        val required = when (screen) {
                            AppScreen.MODE_SELECT -> setOf("onlineButton", "offlineButton")
                            AppScreen.SIMULATOR -> setOf("localRoot", "localPart", "cancelButton", "detailsArea", "previousComparisonButton", "nextComparisonButton")
                            AppScreen.CONSOLE -> setOf("commandField", "reconnectButton", "logLevelSlider")
                            AppScreen.SERVER_CONTROL -> setOf("startButton", "stopButton", "restartButton", "saveButton", "workingDirectoryField", "startCommandField", "stopCommandField", "restartCommandField")
                            AppScreen.MOD_CONFIG -> setOf("saveButton", "reloadButton", "contentArea")
                            AppScreen.MONEY_HISTORY -> setOf("fromDate", "fromTime", "toDate", "toTime", "historyTable")
                            AppScreen.SERVER_CREATE, AppScreen.SERVER_EDIT -> setOf("nameField", "hostField", "cancelButton", "saveButton")
                            else -> emptySet()
                        }
                        assertTrue(visited.containsAll(required), "${screen.name}の必須操作が未確認です: ${required - visited}")
                        val inputId = when (screen) {
                            AppScreen.SIMULATOR -> "localRoot"
                            AppScreen.CONSOLE -> "commandField"
                            AppScreen.SERVER_CONTROL -> "workingDirectoryField"
                            AppScreen.MONEY_HISTORY -> "fromTime"
                            AppScreen.SERVER_CREATE, AppScreen.SERVER_EDIT -> "nameField"
                            else -> null
                        }
                        if (inputId == null) null else {
                            if (screen == AppScreen.MONEY_HISTORY) {
                                root.lookupAll(".tab-pane").filterIsInstance<TabPane>().forEach { it.selectionModel.selectFirst() }
                                root.applyCss(); root.layout()
                            }
                            val target = loader.namespace[inputId] as? TextInputControl
                                ?: error("${screen.name}: 必須入力 $inputId がありません")
                            assertReachable(root, target)
                            if (screen == AppScreen.CONSOLE) {
                                assertTrue(target is TextArea && target.isDisabled, "未接続のConsole入力は無効である必要があります")
                                null
                            } else {
                                assertTrue(generateSequence(target as Node?) { it.parent }.all { it.isVisible && !it.isDisabled }, "${screen.name}: 入力 $inputId または祖先が非表示・無効です")
                                assertTrue(target.isEditable && target.isFocusTraversable, "${screen.name}: 入力 $inputId がフォーカスを受け取れません")
                                target.requestFocus()
                                println("#293 focus t0 screen=${screen.name} id=${target.id} classes=${target.styleClass} stageFocused=${stage.isFocused} focusOwner=${stage.scene.focusOwner} visible=${target.isVisible} disabled=${target.isDisabled} bounds=${target.localToScene(target.boundsInLocal)} focused=${target.isFocused}")
                                target
                            }
                        }
                    }
                    focusTarget?.let { target ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!onFx { stage.scene.focusOwner === target } && System.nanoTime() < deadline) settle()
                        onFx {
                            println("#293 focus final screen=${screen.name} id=${target.id} classes=${target.styleClass} stageFocused=${stage.isFocused} focusOwner=${stage.scene.focusOwner} visible=${target.isVisible} disabled=${target.isDisabled} bounds=${target.localToScene(target.boundsInLocal)} focused=${target.isFocused}")
                            assertTrue(stage.scene.focusOwner === target, "${screen.name}の入力にフォーカスできません")
                        }
                    }
                }
            } finally {
                onFx {
                    when (val controller = loader.getController<Any>()) {
                        is ConsoleController -> controller.dispose()
                        is SimulatorController -> controller.dispose()
                        is MoneyHistoryController -> controller.dispose()
                    }
                    stage.close()
                }
            }
        }
    }

    @Test fun `Editorの左右領域と上部操作は外枠スクロールから到達する`() = onFx {
        val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
        val root = loader.load<Parent>()
        val sidebar = loader.namespace["sidebarContainer"] as VBox
        sidebar.children.add(VBox(*Array(20) { Button("サイドバー操作$it") }))
        val main = loader.namespace["mainContentContainer"] as HBox
        main.children.add(VBox(*Array(20) { Button("編集操作$it") }).apply { minWidth = 700.0 })
        (loader.namespace["actionButtonContainer"] as HBox).children.addAll(
            MenuButton("ファイル(F)").apply { styleClass.addAll("editor-menu-button", "editor-menu-button-first") },
            MenuButton("編集(E)").apply { styleClass.addAll("editor-menu-button", "editor-menu-button-last") })
        val stage = Stage().apply { scene = Utility.createScene(AppScreen.BASE, 600.0, 400.0, root); width = 600.0; height = 400.0; show() }
        try {
            root.applyCss(); root.layout()
            checkActions(root, root)
        } finally { stage.close() }
    }

    @Test fun `実4種別Editorの一覧と中央編集を縮小後に表示できる`() {
        checkEditor(EditorDataDescriptors.item) { main, service -> ItemEditorLogic(main, service) }
        checkEditor(EditorDataDescriptors.ore) { main, service -> OreEditorLogic(main, service) }
        checkEditor(EditorDataDescriptors.shop) { main, service -> ShopEditorLogic(main, service) }
        checkEditor(EditorDataDescriptors.recipe) { main, service -> RecipeEditorLogic(main, service) }
    }

    private fun <T : ManagedData<T, *>> checkEditor(
        descriptor: EditorDataDescriptor<T>, factory: (MainController, EditorDataService) -> EditorView<*>
    ) {
        val directory = createTempDirectory("small-editor-293").toFile()
        val memory = InMemoryEditorDataStore()
        val id = if (descriptor == EditorDataDescriptors.shop) "tab.saved" else "saved"
        memory.save(descriptor, id, descriptor.createDefault(id))
        val service = EditorDataService(memory, directory)
        val (main, stage, view) = onFx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply {
                title = "実Editor 小画面 #293 $id"
                scene = Utility.createScene(AppScreen.BASE, 900.0, 460.0, root)
                width = 900.0
                height = 460.0
                show()
            }
            val view = factory(main, service)
            main.switchView(view)
            Triple(main, stage, view)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!onFx { !main.loadingRunning && main.mainContentContainer.children.isNotEmpty() }) {
                assertTrue(System.nanoTime() < deadline, "実Editorの読み込みが完了しません")
                Thread.sleep(25)
            }
            settle()
            onFx {
                val root = stage.scene.root
                root.applyCss(); root.layout()
                println("#293 実Editor ${view.javaClass.simpleName} stage=${stage.width}x${stage.height} scene=${stage.scene.width}x${stage.scene.height}")
                assertTrue(main.sidebarContainer.children.isNotEmpty())
                checkActions(root, root)
                val file = main.actionButtonContainer.children.filterIsInstance<MenuButton>().first()
                assertReachable(root, file)
                assertTrue(file.items.any { it.text.startsWith("保存") })
                file.show()
                assertTrue(file.isShowing, "保存メニューを開けません")
                file.hide()
            }
        } finally {
            val closed = CompletableFuture<Boolean>()
            onFx { view.onClose { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            onFx { stage.close() }
            service.itemReferences.close()
            service.closeIo()
            assertTrue(directory.canonicalFile.parentFile == java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test fun `小さい補助ダイアログで本文と決定取消へ到達し取消で元データを変えない`() {
        val owner = onFx { Stage().apply { scene = javafx.scene.Scene(StackPane(), 600.0, 400.0); show() } }
        try {
            checkDialog("ドロップアイテム編集", "キャンセル") { DropItemEditorDialog.show(owner, mutableListOf(), emptyList()) {} }
            val potion = MutablePotionData()
            checkDialog("ポーション効果編集", "OK") { PotionEffectEditorDialog.show(potion) {} }
            assertTrue(potion.effects.isEmpty())
            checkDialog("アイテムを選択", ButtonType.CANCEL.text) { ItemTreeSelectionDialog.show(owner, emptyList(), null) }
            checkDialog("保存競合の解決", ButtonType.CANCEL.text) {
                MergeConflictDialog.show(owner, "test", listOf(DataConflict(DataFieldPath.property("name", "名前"), "名前", "元", "ローカル", "サーバー")))
            }
            checkDialog("ローカルデータをアップロード", ButtonType.CANCEL.text) {
                OfflineUploadDialog.select(owner, "test", UploadDataCategory.ITEM, UploadScanResult.Success(emptyList(), emptyMap()))
            }
            checkDialog("アップデート確認", "閉じる") {
                UpdateDialog.show(UpdateCheckResult.Manual("9.0.0", "変更内容\n".repeat(50), "https://example.com", "手動確認"), {}, null)
            }
            val result = checkDialog("入力 #293", "決定") { ValidatedInputDialog.show("入力 #293") { ValidationResult.Success } }
            assertEquals("", result)
        } finally { onFx { owner.close() } }
    }

    private fun checkDialog(title: String, action: String, show: () -> Any?): Any? {
        val existing = onFx { Window.getWindows().toSet() }
        val completed = FutureTask(show)
        onFx { Platform.runLater(completed) }
        settle()
        val stage = onFx { Window.getWindows().filterIsInstance<Stage>().single { it !in existing && it.title == title } }
        try {
            onFx { stage.width = 600.0; stage.height = 400.0 }
            settle()
            onFx {
                val root = stage.scene.root
                root.applyCss(); root.layout()
                checkActions(root, root)
                val button = root.lookupAll(".button").filterIsInstance<Button>().single { it.text == action }
                assertReachable(root, button)
                button.fire()
            }
            return completed.get(10, TimeUnit.SECONDS)
        } finally { onFx { if (stage.isShowing) stage.close() } }
    }

    private fun checkActions(root: Parent, node: Node) {
        if (!node.isVisible || !node.isManaged) return
        node.id?.let(visited::add)
        when (node) {
            is ButtonBar -> node.buttons.forEach { checkActions(root, it) }
            is ButtonBase, is TextInputControl, is ComboBoxBase<*>, is Spinner<*>, is Slider, is TableView<*>, is TreeView<*> -> assertReachable(root, node)
            is ScrollPane -> node.content?.let { checkActions(root, it) }
            is TitledPane -> node.content?.takeIf { node.isExpanded }?.let { checkActions(root, it) }
            is TabPane -> node.selectionModel.selectedItem?.content?.let { checkActions(root, it) }
            is Control -> Unit
            is Parent -> node.childrenUnmodifiable.toList().forEach { checkActions(root, it) }
        }
    }

    private fun assertReachable(root: Parent, node: Node) {
        val scrolls = generateSequence(node.parent) { it.parent }.filterIsInstance<ScrollPane>().toList()
        for (scroll in scrolls) {
            val bounds = scroll.content.sceneToLocal(node.localToScene(node.boundsInLocal))
            val content = scroll.content.layoutBounds
            val viewport = scroll.viewportBounds
            if (content.width > viewport.width) scroll.hvalue =
                ((bounds.minX + bounds.width / 2 - viewport.width / 2) / (content.width - viewport.width)).coerceIn(0.0, 1.0)
            if (content.height > viewport.height) scroll.vvalue =
                ((bounds.minY + bounds.height / 2 - viewport.height / 2) / (content.height - viewport.height)).coerceIn(0.0, 1.0)
            root.layout()
        }
        val bounds = node.localToScene(node.boundsInLocal)
        val x = bounds.minX + bounds.width / 2
        val y = bounds.minY + bounds.height / 2
        assertTrue(x >= -1 && x <= root.scene.width + 1 && y >= -1 && y <= root.scene.height + 1,
            "${node.id ?: (node as? Labeled)?.text ?: node.javaClass.simpleName}へ到達できません: $bounds / ${root.scene.width}x${root.scene.height}")
        if (node is ButtonBase) assertTrue(bounds.minX >= -1 && bounds.maxX <= root.scene.width + 1 &&
            bounds.minY >= -1 && bounds.maxY <= root.scene.height + 1,
            "操作ボタンの全体が表示できません: ${(node as? Labeled)?.text} $bounds")
        for (scroll in scrolls) {
            val viewport = scroll.lookup(".viewport")
            val visible = viewport.localToScene(viewport.boundsInLocal)
            if (node is ButtonBase) assertTrue(bounds.minX >= visible.minX - 1 && bounds.maxX <= visible.maxX + 1 &&
                bounds.minY >= visible.minY - 1 && bounds.maxY <= visible.maxY + 1,
                "操作ボタンの全体がスクロール領域に入りません: ${(node as? Labeled)?.text} $bounds / $visible")
            assertTrue(x >= visible.minX - 1 && x <= visible.maxX + 1 && y >= visible.minY - 1 && y <= visible.maxY + 1,
                "${node.id ?: (node as? Labeled)?.text}がスクロール領域に隠れています: $bounds / $visible")
        }
    }

    private fun settle() { Thread.sleep(250); onFx {} }

    private fun <T> onFx(action: () -> T): T {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(90, TimeUnit.SECONDS)
    }
}
