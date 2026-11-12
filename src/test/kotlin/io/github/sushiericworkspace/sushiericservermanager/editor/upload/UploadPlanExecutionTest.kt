package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class UploadPlanExecutionTest {
    @TempDir lateinit var root: File
    private class Remote(val files: LocalEditorDataStore) : EditorDataStore by files {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("profile", "host/root")
        var writes = 0
        var unknown = false
        var readFailed = false
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++; val result = files.save(descriptor, id, data)
            if (!unknown) return result
            readFailed = true
            return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR))
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> =
            if (readFailed) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR)) else files.load(descriptor, id)
    }
    private fun prepare(server: EditorDataService): Pair<UploadPlan, SavedUploadOperation> {
        val local = server.localService
        val item = local.items.createDefault("one")
        val text = local.items.encodeDocument(item)
        val record = assertIs<StoreResult.Success<LocalDocumentRecord>>(local.items.updateLocalDocument(item.id) {
            it.copy(editing = text, original = text, saved = text)
        }).value
        val plans = UploadPlanService(server)
        val group = assertIs<StoreResult.Success<UploadPlanGroup>>(plans.createGroup(listOf(UploadPlanRoot(record.key)), "event")).value
        val plan = assertIs<StoreResult.Success<UploadPlan>>(plans.save(
            assertIs<StoreResult.Success<UploadPlan>>(plans.read()).value.add(group))).value
        val planner = SavedUploadPlanner(local, server)
        val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(planner.preview(
            assertIs<StoreResult.Success<List<SavedUploadSelection>>>(plans.selections(group)).value)).value
        val request = assertIs<StoreResult.Success<SavedUploadOperation>>(planner.build(preview,
            preview.candidates.associate { it.input.key to it.merge.conflicts.mapTo(mutableSetOf()) { c -> c.path } }, true)).value
        return plan to request
    }
    private fun service(): Pair<Remote, EditorDataService> {
        val remote = Remote(LocalEditorDataStore(root.resolve("remote")))
        return remote to EditorDataService(remote, root.resolve("app"))
    }
    @Test fun `確認前の取消は予約も台帳も結合せず作用0`() {
        val (remote, server) = service()
        try {
            val (plan, request) = prepare(server)
            assertIs<StoreResult.Failure>(UploadPlanExecution(server).execute(plan, plan.groups.single().id, request, { true }, {}))
            assertEquals(plan, assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).read()).value)
            assertTrue(assertIs<StoreResult.Success<LocalDocumentSnapshot>>(server.documents.read()).value.uploads.isEmpty())
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }
    @Test fun `結合後の未実行取消は固定台帳を残して予約を正規削除できる`() {
        val (remote, server) = service()
        try {
            val (plan, request) = prepare(server); var checks = 0
            val result = assertIs<StoreResult.Success<SavedUploadOperation>>(UploadPlanExecution(server).execute(
                plan, plan.groups.single().id, request, { ++checks > 1 }, {})).value
            assertEquals(SavedUploadState.CANCELLED, result.steps.single().state)
            val bound = assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).read()).value
            assertEquals(request.id, bound.groups.single().operationId)
            assertTrue(assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).remove(bound, bound.groups.single().id)).value.groups.isEmpty())
            assertEquals(request.id, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(server.documents.read()).value.uploads.single().id)
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }
    @Test fun `UNKNOWN予約は再生成再送せず再読込後も同要求の読戻しだけ行う`() {
        val (remote, server) = service()
        try {
            val (plan, request) = prepare(server); remote.unknown = true
            val result = assertIs<StoreResult.Success<SavedUploadOperation>>(UploadPlanExecution(server).execute(
                plan, plan.groups.single().id, request, { false }, {})).value
            assertTrue(result.needsReadback); assertEquals(1, remote.writes)
            val restored = assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).read()).value
            assertIs<StoreResult.Failure>(UploadPlanService(server).remove(restored, restored.groups.single().id))
            assertIs<StoreResult.Failure>(UploadPlanExecution(server).execute(restored, restored.groups.single().id, request, { false }, {}))
            remote.readFailed = false
            val confirmed = assertIs<StoreResult.Success<SavedUploadOperation>>(UploadPlanExecution(server).readback(restored.groups.single())).value
            assertTrue(confirmed.complete); assertEquals(request.id, confirmed.id); assertEquals(1, remote.writes)
        } finally { server.closeIo() }
    }
    @Test fun `正規実行は予約を同要求へ固定しLocal原文書を保持する`() {
        val (remote, server) = service()
        try {
            val (plan, request) = prepare(server)
            val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(server.localService.documents.read()).value.documents
            val result = assertIs<StoreResult.Success<SavedUploadOperation>>(UploadPlanExecution(server).execute(
                plan, plan.groups.single().id, request, { false }, {})).value
            assertTrue(result.complete); assertEquals(1, remote.writes)
            assertEquals(before, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(server.localService.documents.read()).value.documents)
            assertEquals(request.id, assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).read()).value.groups.single().operationId)
        } finally { server.closeIo() }
    }
}
