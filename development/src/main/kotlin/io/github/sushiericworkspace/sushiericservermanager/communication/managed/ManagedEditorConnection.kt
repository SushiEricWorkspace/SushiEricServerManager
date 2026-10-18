package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericdatamodel.path.SushiEricDataDirectory
import java.io.File
import java.nio.file.Files
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorConnection
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.ManagedEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.update.evaluateManagerCompatibility
import kotlinx.serialization.json.*

/** 登録・互換性・監視socketを固定した管理編集接続です。 */
class ManagedEditorConnection(private val profile: ManagedProfile, private val lease: ManagedLease? = null) : EditorConnection {
    private var session: ManagedSession? = null
    override val contextName = "管理：${profile.target.description} / ${profile.instanceId}" + (lease?.let { " / " + it.responsibility } ?: "")
    override var compatibility: ManagerCompatibility = ManagerCompatibility.Unchecked
        private set

    override fun open(): EditorDataService {
        val active = ManagedSession.open(profile, FilePath.dataDirectory().toPath(), admissionGuard = { lease?.ensureActive() }, onUnknown = { lease?.holdUnknown() })
        session = active
        lease?.watchFailure { active.holdUnknown() }
        compatibility = try {
            val text = active.io(buildJsonObject {
                put("action", "read"); put("path", SushiEricDataDirectory.ManagerCompat().getRawPath())
            }).getValue("text").jsonPrimitive.content
            evaluateManagerCompatibility(text)
        } catch (error: SupervisorFailure) {
            if (error.code == "FILE_NOT_FOUND") ManagerCompatibility.Missing else throw error
        }
        val store = ManagedEditorDataStore(active, compatibility)
        check(EditorSession.managementClient.connect(ManagedManagementTransport(active))) {
            "管理監視接続を確立できません。writer登録は保全されています。"
        }
        val legacyRoots = legacyManagedBackupRoots(FilePath.dataDirectory())
        return EditorDataService(store, active.autoSaveDirectory,
            documentDirectory = FilePath.AUTOSAVE_DIR.toFile().resolve("documents"),
            localDirectory = FilePath.OFFLINE_DIR.toFile(),
            legacyAutoSaveRoots = legacyRoots)
    }

    override fun close() {
        session?.close { EditorSession.managementClient.disconnect() }
        lease?.writerClosed()
    }

    override fun abort() { session?.holdUnknown(); lease?.abort() }
}


/** 既知apphome直下の管理退避だけを候補元にし、別rootへのリンクはたどりません。 */
internal fun legacyManagedBackupRoots(appHome: File): List<File> {
    val common = appHome.resolve("autosave")
    val commonRoots = listOf(common).filter {
        !it.exists() || (!Files.isSymbolicLink(it.toPath()) && it.toPath().toRealPath().parent == appHome.toPath().toRealPath())
    }
    val managed = appHome.resolve("managed")
    if (Files.isSymbolicLink(managed.toPath()) || !managed.isDirectory) return commonRoots
    val homePath = appHome.toPath().toRealPath()
    val managedPath = managed.toPath().toRealPath()
    if (managedPath.parent != homePath) return commonRoots
    return managed.listFiles { file -> !Files.isSymbolicLink(file.toPath()) && file.isDirectory }.orEmpty()
        .filter { it.toPath().toRealPath().parent == managedPath }
        .map { it.resolve("autosave") }
        .filter { !it.exists() || (!Files.isSymbolicLink(it.toPath()) && it.toPath().toRealPath().parent == it.parentFile.toPath().toRealPath()) } + commonRoots
}
