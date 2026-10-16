package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceCache
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform
import java.util.concurrent.atomic.AtomicBoolean

/** 共有キャッシュの通知をFXスレッドへまとめて届けます。背景取得の失敗は状態表示に委ね、明示操作の失敗だけ通知します。 */
internal class ItemReferenceBinding(
    private val cache: ItemReferenceCache,
    private val onChanged: () -> Unit,
    private val onFailed: (StoreError) -> Unit
) : AutoCloseable {
    private var closed = false
    private val notificationPending = AtomicBoolean()
    private val listener: (ItemReferenceSnapshot) -> Unit = {
        if (notificationPending.compareAndSet(false, true)) Platform.runLater {
            notificationPending.set(false)
            if (!closed) onChanged()
        }
    }

    init {
        cache.addListener(listener)
    }

    fun load(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        if (closed) return
        if (!force && onLoaded == null && cache.snapshot.status == ItemReferenceStatus.FAILED) return
        if (!force && cache.snapshot.status == ItemReferenceStatus.READY) {
            onLoaded?.invoke()
            return
        }
        cache.load(force).thenAccept { result ->
            Platform.runLater {
                if (!closed) {
                    when (result) {
                        is StoreResult.Success -> onLoaded?.invoke()
                        is StoreResult.Failure -> if (force || onLoaded != null) onFailed(result.error)
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        cache.removeListener(listener)
    }
}
