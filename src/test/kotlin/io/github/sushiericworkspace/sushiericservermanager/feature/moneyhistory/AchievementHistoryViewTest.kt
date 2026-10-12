package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.TableView
import javafx.stage.Stage
import javafx.collections.ListChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 実績履歴画面へダミー履歴を読み込み、取得方法と絞り込みを確認します。 */
class AchievementHistoryViewTest {
    @Test
    fun `実績履歴画面に取得方法と管理者名を表示し取得方法で絞り込む`() {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))

        val playerUuid = "11111111-1111-1111-1111-111111111111"
        val store = InMemoryEditorDataStore()
        store.writeText("usercache.json", """[{"uuid":"$playerUuid","name":"テストプレイヤー"}]""")
        store.writeText(
            "player_data/$playerUuid/achievements.yml",
            """
            achievements:
              monster_hunter_1:
                at: 2026-10-12T10:00:00Z
                source: natural
              mining_beginner_1:
                at: 2026-10-13T10:00:00Z
                source: admin
                executor:
                  uuid: 22222222-2222-2222-2222-222222222222
                  name: テスト管理者
            """.trimIndent()
        )

        val loaded = CountDownLatch(1)
        val setup = FutureTask {
            val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/achievement-history.fxml"))
            val root = loader.load<Parent>()
            val controller = loader.getController<AchievementHistoryController>()
            controller.initialize(store, ServerManagementClient())
            val table = root.lookup("#historyTable") as? TableView<AchievementHistoryRow>
                ?: error("実績履歴テーブルが見つかりませんでした。")
            table.items.addListener(ListChangeListener { if (table.items.size == 2) loaded.countDown() })
            Stage().apply {
                title = "実績履歴の確認"
                scene = Scene(root, 1100.0, 640.0)
                show()
            } to table
        }
        Platform.runLater(setup)
        val (stage, table) = setup.get(20, TimeUnit.SECONDS)
        try {
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "ダミー履歴が画面へ表示されませんでした。")
            val verify = FutureTask {
                assertEquals(2, table.items.size)
                assertEquals("管理者による付与（テスト管理者）", table.items.first().source)
                assertEquals("自然達成", table.items.last().source)
                val sourceMenu = stage.scene.lookup("#sourceMenu") as javafx.scene.control.MenuButton
                val adminFilter = sourceMenu.items
                    .map { (it as javafx.scene.control.CustomMenuItem).content as javafx.scene.control.CheckBox }
                    .first { it.text == AchievementSource.Admin.displayName }
                adminFilter.fire()
                assertEquals(1, table.items.size)
                assertEquals("自然達成", table.items.single().source)
            }
            Platform.runLater(verify)
            verify.get(10, TimeUnit.SECONDS)
        } finally {
            Platform.runLater { stage.close() }
        }
    }
}
