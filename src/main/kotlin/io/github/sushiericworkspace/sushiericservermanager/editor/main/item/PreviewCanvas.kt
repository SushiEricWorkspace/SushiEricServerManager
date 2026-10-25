package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewRenderer
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.scene.image.ImageView
import javafx.stage.Screen
import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory

/** 編集対象を変更せず、同梱書体による内容確認用プレビューをImageViewへ反映する。 */
class PreviewCanvas(
    private val itemData: ItemBaseDataView,
    private val imageView: ImageView
) {
    private val renderer = PreviewRenderer()
    private var errorShown = false

    fun refreshPreview() {
        try {
            val window = imageView.scene?.window
            val screen = Screen.getPrimary()
            val rows = listOf(Component.text(itemData.display.displayName)) +
                itemData.display.lore.map { line ->
                    line.fold(Component.empty()) { root, section -> root.append(section.toComponent()) }
                }
            val result = renderer.render(rows, window?.outputScaleX ?: screen.outputScaleX,
                window?.outputScaleY ?: screen.outputScaleY)
            imageView.image = result.image
            imageView.fitWidth = result.width
            imageView.fitHeight = result.height
            imageView.accessibleText = result.description
        } catch (exception: Exception) {
            imageView.image = null
            imageView.accessibleText = "プレビューを表示できません。編集内容は保持されています。"
            LoggerFactory.getLogger(PreviewCanvas::class.java).error("プレビュー描画に失敗しました", exception)
            if (!errorShown) {
                errorShown = true
                CustomDialog.error().title("プレビュー表示エラー")
                    .content("プレビュー素材を確認してください。編集内容の保存は引き続き行えます。")
                    .exception(exception).show()
            }
        }
    }
}
