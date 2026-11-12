package io.github.sushiericworkspace.sushiericservermanager.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationStateTest {
    @Test fun `別保存元と別対象の通知は上書きしない`() {
        val state = NotificationState()
        val local = state.scope("local", "item", "same")
        val server = state.scope("server-A", "item", "same")
        state.condition(local, "save", "ローカル失敗")
        state.condition(server, "save", "サーバー失敗")
        state.condition(local.copy(target = "other"), "save", "別文書失敗")
        state.resolve(local, "save")
        assertEquals(setOf("サーバー失敗", "別文書失敗"), state.snapshot().map { it.text }.toSet())
    }

    @Test fun `Toastだけ期限で消しBannerは解決まで残す`() {
        var now = 0L
        val state = NotificationState { now }
        val scope = state.scope("local", "item")
        state.toast(scope, "保存済み", seconds = 2.0)
        state.toast(scope, "保存済み", seconds = 2.0)
        state.condition(scope, "backup", "退避失敗")
        assertEquals(2, state.snapshot().size)
        now = 2000
        state.expire()
        assertEquals(listOf("退避失敗"), state.snapshot().map { it.text })
        state.resolve(scope, "backup")
        assertTrue(state.snapshot().isEmpty())
    }

    @Test fun `世代の古い工程更新と完了は新しい工程を消さない`() {
        val state = NotificationState()
        val scope = state.scope("local", "item", "A")
        val old = state.start(scope, "save", "旧工程")
        val current = state.start(scope, "save", "新工程")
        state.update(old, "古い完了", 1, 1)
        state.finish(old)
        assertTrue(state.snapshot().any { it.operation == current && it.text == "新工程" })
        state.finish(current)
        assertFalse(state.snapshot().any { it.progress != null })
    }

    @Test fun `分割の工程件数は別対象ごとに保持する`() {
        val state = NotificationState()
        val first = state.start(state.scope("local", "item", "A"), "save", "保存")
        val second = state.start(state.scope("local", "recipe", "B"), "save", "検証")
        state.update(first, "反映確認", 2, 4)
        state.update(second, "件数未確定")
        assertEquals(0.5, state.snapshot().single { it.operation == first }.progress)
        assertEquals(-1.0, state.snapshot().single { it.operation == second }.progress)
        state.finish(first)
        assertEquals(second, state.snapshot().single().operation)
    }

    @Test fun `不明件数や非有限進捗から完了率を推測しない`() {
        val state = NotificationState()
        val operation = state.start(state.scope("local", "item"), "import", "準備")
        state.update(operation, "準備", 0, 0)
        assertEquals(-1.0, state.snapshot().single().progress)
        state.fraction(operation, "準備", Double.NaN)
        assertEquals(-1.0, state.snapshot().single().progress)
    }

    @Test fun `切断時は通信進捗だけ止め結果不明のBannerを残す`() {
        val state = NotificationState()
        val uploadScope = state.scope("local → server-A", "アップロード", "request-A")
        val upload = state.start(uploadScope, "upload:request-A", "送信中")
        val local = state.start(state.scope("local", "item", "A"), "save", "保存中")
        state.interruptRemote()
        state.update(upload, "古い成功", 1, 1)
        state.finish(upload)
        assertFalse(state.result(upload, "upload:request-A", null))
        assertTrue(state.snapshot().any { it.operation == local })
        val banner = state.snapshot().single { it.scope == uploadScope }
        assertNull(banner.progress)
        assertTrue(banner.text.contains("読戻し"))
        state.resolve(uploadScope.copy(target = "request-B"), "upload:request-A")
        assertTrue(state.snapshot().any { it.scope == uploadScope })
        state.resolve(uploadScope, "upload:request-A")
        assertFalse(state.snapshot().any { it.scope == uploadScope })
    }

    @Test fun `旧接続callbackは現在Bannerを解消せず現在の同要求読戻しだけが旧Bannerも解消する`() {
        val state = NotificationState()
        state.beginSession("接続A")
        val oldScope = state.scope("server-A", "アップロード", "request-A")
        state.start(oldScope, "upload:request-A", "送信中")
        state.beginSession("接続B")
        val currentScope = state.scope("server-A", "アップロード", "request-A")
        state.condition(currentScope, "upload:request-A", "読戻し待ち")
        state.condition(oldScope, "upload:request-A", null)
        state.resolve(oldScope, "upload:request-A")
        state.toast(oldScope, "古い成功")
        assertEquals(2, state.snapshot().size)
        assertTrue(state.snapshot().all { it.progress == null })
        state.resolve(currentScope.copy(source = "server-B"), "upload:request-A")
        assertEquals(2, state.snapshot().size)
        state.resolve(currentScope, "upload:request-A")
        assertTrue(state.snapshot().isEmpty())
    }

    @Test fun `購読解除後はViewへ更新しない`() {
        val state = NotificationState()
        var calls = 0
        val remove = state.observe { calls++ }
        val scope = state.scope("local", "item")
        state.condition(scope, "save", "失敗")
        state.condition(scope, "save", "失敗")
        assertEquals(1, calls)
        remove()
        state.resolve(scope, "save")
        assertEquals(1, calls)
    }
    @Test fun `旧操作の成功は後発の失敗Bannerを消さない`() {
        val state = NotificationState()
        val scope = state.scope("local", "item", "A")
        val old = state.start(scope, "save", "保存")
        state.finish(old)
        val current = state.start(scope, "save", "再保存")
        assertTrue(state.result(current, "save", "新しい失敗"))
        assertFalse(state.result(old, "save", null))
        assertEquals("新しい失敗", state.snapshot().single().text)
        val retry = state.start(scope, "save", "再試行")
        assertTrue(state.result(retry, "save", null))
        assertTrue(state.snapshot().isEmpty())
    }

    @Test fun `保存の成功は同対象でも結果不明の送信要求を解消しない`() {
        val state = NotificationState()
        val scope = state.scope("local", "item", "A")
        state.condition(scope, "upload:request-A", "結果不明")
        val save = state.start(scope, "save", "保存")
        assertTrue(state.result(save, "save", null))
        assertEquals("結果不明", state.snapshot().single().text)
    }

    @Test fun `旧要求の読戻し完了も後発の同要求の失敗を消さない`() {
        val state = NotificationState()
        val scope = state.scope("server-A", "アップロード", "request-A")
        val old = state.start(scope, "upload:request-A", "読戻し")
        state.interruptRemote()
        val current = state.start(scope, "upload:request-A", "再確認")
        state.result(current, "upload:request-A", "未反映")
        assertFalse(state.result(old, "upload:request-A", null))
        assertEquals("未反映", state.snapshot().single().text)
    }

    @Test fun `通常finish後の結果と新しい同要求readbackは受理する`() {
        val state = NotificationState()
        val scope = state.scope("server-A", "アップロード", "request-A")
        val old = state.start(scope, "upload:request-A", "送信")
        state.interruptRemote()
        assertFalse(state.result(old, "upload:request-A", null))
        val readback = state.start(scope, "upload:request-A", "同要求を読戻し")
        state.finish(readback)
        assertTrue(state.result(readback, "upload:request-A", null))
        assertFalse(state.result(readback, "upload:request-A", "遅延した二重結果"))
        state.interruptRemote()
        assertTrue(state.snapshot().isEmpty())
        val save = state.start(scope.copy(source = "local"), "operation", "保存")
        state.finish(save)
        assertTrue(state.result(save, "save", null))
    }

    @Test fun `一括対象の予約は成功IDだけ解決し失敗と別保存元と未実行を保持する`() {
        val state = NotificationState()
        val a = state.scope("local", "item", "A")
        val b = a.copy(target = "B")
        val server = a.copy(source = "server-A")
        val oldSingle = state.start(a, "operation", "保存")
        state.result(oldSingle, "save", "A失敗")
        state.condition(server, "save", "別保存元A失敗")
        val batchA = state.prepare(a, "operation")
        val batchB = state.prepare(b, "operation")
        assertFalse(state.snapshot().any { it.progress != null })
        assertTrue(state.result(batchA, "save", null))
        assertTrue(state.result(batchB, "save", "B失敗"))
        assertEquals(setOf("別保存元A失敗", "B失敗"), state.snapshot().map { it.text }.toSet())
        assertFalse(state.result(oldSingle, "save", "旧単件結果"))
        val retry = state.start(a, "operation", "再保存")
        state.result(retry, "save", "新しいA失敗")
        assertFalse(state.result(batchA, "save", null))
        state.prepare(a, "operation")
        assertTrue(state.snapshot().any { it.scope == a && it.text == "新しいA失敗" })
        assertTrue(state.snapshot().any { it.scope == b && it.text == "B失敗" })
        assertTrue(state.snapshot().any { it.scope == server && it.text == "別保存元A失敗" })
    }

    @Test fun `非表示予約もremote作用とlocal保存を分けて失効し接続終了で両方失効する`() {
        val state = NotificationState()
        val remote = state.scope("server-A", "アップロード", "request-A")
        val upload = state.prepare(remote, "upload:request-A")
        val local = state.prepare(remote.copy(feature = "item", target = "A"), "operation")
        state.interruptRemote()
        assertFalse(state.result(upload, "upload:request-A", null))
        assertTrue(state.result(local, "save", null))
        assertTrue(state.snapshot().any { it.scope == remote && it.key == "upload:request-A" })
        val readback = state.start(remote, "upload:request-A", "読戻し")
        state.finish(readback)
        assertTrue(state.result(readback, "upload:request-A", null))
        assertTrue(state.snapshot().isEmpty())
        val pendingLocal = state.prepare(remote.copy(feature = "item", target = "A"), "operation")
        val pendingRemote = state.prepare(remote, "upload:request-A")
        state.endSession()
        assertFalse(state.result(pendingLocal, "save", null))
        assertFalse(state.result(pendingRemote, "upload:request-A", null))
    }

}
