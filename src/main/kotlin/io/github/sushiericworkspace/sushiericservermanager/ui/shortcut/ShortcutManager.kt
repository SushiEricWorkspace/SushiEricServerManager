package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import javafx.scene.Scene
import javafx.event.EventHandler
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyEvent
import org.slf4j.LoggerFactory
import java.util.WeakHashMap

/**
 * Sceneに対してショートカットキーを登録、解除するための管理クラス。
 *
 * JavaFXのショートカットはScene単位で登録されるため、
 * 親画面、モーダル画面など、使いたいSceneごとに登録する必要がある。
 */
object ShortcutManager {
    private val logger = LoggerFactory.getLogger(ShortcutManager::class.java)
    private val historyHandlers = WeakHashMap<Scene, EventHandler<KeyEvent>>()

    /**
     * 指定したSceneにショートカットキーを登録する。
     *
     * 同じキーのショートカットが既に登録されている場合は上書きされる。
     *
     * @param scene 登録先のScene。
     * @param shortcut 登録するショートカット種別。
     * @param action ショートカット押下時に実行する処理。
     */
    fun register(scene: Scene, shortcut: EditorShortcut, action: () -> Unit) {
        scene.accelerators[shortcut.combination] = Runnable {
            logger.info("ショートカットキーが検出されました: ${shortcut.displayName}")
            action()
        }
    }

    /**
     * 指定したSceneからショートカットキーを解除する。
     *
     * @param scene 解除対象のScene。nullの場合は何もしない。
     * @param shortcut 解除するショートカット種別。
     */
    fun unregister(scene: Scene?, shortcut: EditorShortcut) {
        scene?.accelerators?.remove(shortcut.combination)
    }

    /**
     * 編集履歴用ショートカットを登録します。
     * テキスト入力中の標準的な取り消し・やり直しはJavaFXへ委譲します。
     */
    fun registerHistoryShortcuts(scene: Scene, onUndo: () -> Boolean, onRedo: () -> Boolean) {
        unregisterHistoryShortcuts(scene)
        val handler = EventHandler<KeyEvent> { event ->
            val undo = EditorShortcut.UNDO.combination.match(event)
            val redo = EditorShortcut.REDO.combination.match(event) ||
                EditorShortcut.REDO_ALTERNATIVE.combination.match(event)
            val textInput = scene.focusOwner as? TextInputControl
            if (textInput != null) {
                if (undo && textInput.isUndoable) return@EventHandler
                if (redo && textInput.isRedoable) return@EventHandler
            }

            val handled = when {
                undo -> onUndo()
                redo -> onRedo()
                else -> false
            }
            if (handled) event.consume()
        }
        historyHandlers[scene] = handler
        scene.addEventFilter(KeyEvent.KEY_PRESSED, handler)
    }

    private fun unregisterHistoryShortcuts(scene: Scene) {
        historyHandlers.remove(scene)?.let { handler ->
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, handler)
        }
    }

    /**
     * 指定したSceneからエディタ用ショートカットをすべて解除する。
     *
     * [EditorShortcut]に定義された項目をまとめて解除する。
     *
     * @param scene 解除対象のScene。nullの場合は何もしない。
     */
    fun unregisterAll(scene: Scene?) {
        if (scene == null) return

        EditorShortcut.entries.forEach { shortcut ->
            scene.accelerators.remove(shortcut.combination)
        }
        unregisterHistoryShortcuts(scene)
    }
}
