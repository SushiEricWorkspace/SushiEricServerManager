package io.github.sushiericworkspace.sushiericservermanager.app.workspace

import io.github.sushiericworkspace.sushiericservermanager.app.AppFrame
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.HomeController
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import javafx.scene.Parent

/** 接続中の一つのworkspaceを機能の起動・終了経路へ接続します。 */
internal object WorkspaceManager {
    var current: WorkspaceView? = null
        private set

    fun install(frame: AppFrame, home: Parent, controller: HomeController): WorkspaceView = WorkspaceView(
        frame, home, EditorSession.dataService?.cacheIdentity,
        controller::openWorkspaceFeature, controller::workspaceFeatureAvailable, controller::setWorkspaceVisible
    )

    fun acquire(view: WorkspaceView) {
        check(current == null || current === view) { "接続ごとのworkspaceは一つです。" }
        current = view
    }

    fun release(view: WorkspaceView) { if (current === view) current = null }
    fun requireWorkspace(): WorkspaceView = checkNotNull(current) { "機能は表示中のworkspaceから開いてください。" }
    fun focus(feature: WorkspaceFeature): Boolean = requireWorkspace().focus(feature)
    fun close(feature: WorkspaceFeature) { current?.close(feature) }
    fun completeClose(feature: WorkspaceFeature) { current?.completeClose(feature) }
}
