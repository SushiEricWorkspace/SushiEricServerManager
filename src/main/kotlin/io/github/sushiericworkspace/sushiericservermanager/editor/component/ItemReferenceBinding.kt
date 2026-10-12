package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceCache
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform

/** 共有キャッシュの通知をFXスレッドへ届け、画面終了後の通知を抑止します。 */
internal class ItemReferenceBinding(
    private val cache: ItemReferenceCache,
    private val onChanged: () -> Unit,
    private val onFailed: (StoreError) -> Unit
) : AutoCloseable {
    private var closed = false
    private val listener: (ItemReferenceSnapshot) -> Unit = {
        Platform.runLater { if (!closed) onChanged() }
    }

    init {
        cache.addListener(listener)
    }

    fun load(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        if (closed) return
        if (!force && cache.snapshot.status == ItemReferenceStatus.READY) {
            onLoaded?.invoke()
            return
        }
        cache.load(force).thenAccept { result ->
            Platform.runLater {
                if (!closed) {
                    when (result) {
                        is StoreResult.Success -> onLoaded?.invoke()
                        is StoreResult.Failure -> onFailed(result.error)
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
