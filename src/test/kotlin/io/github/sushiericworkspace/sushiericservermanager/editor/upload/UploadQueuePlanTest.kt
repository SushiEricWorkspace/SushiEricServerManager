package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRepository
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class UploadQueuePlanTest {
    @TempDir lateinit var root: File
    private val target = EditorDocumentSource.Server("profile", "host/root")
    private fun key(id: String) = EditorDocumentKey(EditorDocumentSource.Local, "items", id)
    private fun record(id: String, body: String = "saved") =
        LocalDocumentRecord(key(id), revision = 2, editing = body, saved = body)
    private fun group(id: String, document: UploadPlanDocument) = UploadPlanGroup(id, "event", listOf(document))
    private fun document(id: String, targetId: String = "event." + id) = UploadPlanDocument(key(id), targetId, 2, "a".repeat(64))

    @Test fun `フォルダーの相対構造を末尾名へ平坦化しない`() {
        val documents = uploadPlanDocuments(listOf(UploadPlanRoot(key("weapons"), true)),
            listOf(record("weapons.fire.sword"), record("weapons.ice.sword")), "event")
        assertEquals(listOf("event.fire.sword", "event.ice.sword"), documents.map { it.targetId })
    }

    @Test fun `親フォルダーと子の重複選択は親rootで一度だけ予約する`() {
        val roots = listOf(UploadPlanRoot(key("weapons"), true), UploadPlanRoot(key("weapons.fire"), true),
            UploadPlanRoot(key("weapons.fire.sword")))
        val documents = uploadPlanDocuments(roots, listOf(record("weapons.fire.sword")), "event")
        assertEquals(listOf("event.fire.sword"), documents.map { it.targetId })
    }

    @Test fun `単一文書とroot全体を型のroot以下へ割り当てる`() {
        assertEquals("target.sword", uploadPlanDocuments(listOf(UploadPlanRoot(key("a.sword"))),
            listOf(record("a.sword")), "target").single().targetId)
        assertEquals("target.a.sword", uploadPlanDocuments(listOf(UploadPlanRoot(key(""), true)),
            listOf(record("a.sword")), "target").single().targetId)
        assertFailsWith<IllegalArgumentException> { relativeUploadTargetId("other.sword", "a", "target") }
    }

    @Test fun `未保存と保存済rawを黙って送信予約しない`() {
        assertFailsWith<IllegalArgumentException> {
            uploadPlanDocuments(listOf(UploadPlanRoot(key("one"))), listOf(record("one").copy(editing = "dirty")), "")
        }
        assertFailsWith<IllegalArgumentException> {
            uploadPlanDocuments(listOf(UploadPlanRoot(key("one"))),
                listOf(record("one").copy(raw = mapOf("value" to "-"), savedRaw = mapOf("value" to "-"))), "")
        }
    }

    @Test fun `単一選択の同名衝突と別保存元を拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            uploadPlanDocuments(listOf(UploadPlanRoot(key("a.same")), UploadPlanRoot(key("b.same"))),
                listOf(record("a.same"), record("b.same")), "")
        }
        assertFailsWith<IllegalArgumentException> {
            uploadPlanDocuments(listOf(UploadPlanRoot(key("one").copy(source = target))), listOf(record("one")), "")
        }
    }

    @Test fun `多重宛先は許可し同じtargetの衝突だけ拒否する`() {
        val first = group("first", document("one", "event.one"))
        val plan = UploadPlan(target).add(first).add(group("second", document("one", "other.one")))
        assertEquals(2, plan.groups.size)
        assertFailsWith<IllegalArgumentException> { plan.add(group("third", document("two", "event.one"))) }
        assertEquals(listOf("second", "first"), plan.move("first", 1).groups.map { it.id })
        assertEquals(listOf("second"), plan.remove("first").groups.map { it.id })
    }

    @Test fun `実行要求に束縛した予約を通常削除しない`() {
        val plan = UploadPlan(target, groups = listOf(group("one", document("one")).copy(operationId = "request")))
        assertFailsWith<IllegalArgumentException> { plan.remove("one") }
    }

    @Test fun `再読込で順序と内部IDと要求IDを保持し復元は作用しない`() {
        val repository = UploadPlanRepository(root, target)
        val current = assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value
        val added = current.add(group("one", document("one")).copy(itemIds = mapOf("source" to "copy"), operationId = "request"))
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(added)).value
        assertEquals(1L, saved.revision)
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(UploadPlanRepository(root, target).read()).value)
    }

    @Test fun `別Repositoryの古いrevisionは最新予約を上書きしない`() {
        val a = UploadPlanRepository(root, target)
        val b = UploadPlanRepository(root, target)
        val original = assertIs<StoreResult.Success<UploadPlan>>(b.read()).value
        val saved = assertIs<StoreResult.Success<UploadPlan>>(a.save(original.add(group("a", document("a"))))).value
        assertIs<StoreResult.Failure>(b.save(original.add(group("b", document("b")))))
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(a.read()).value)
    }

    @Test fun `未知形式で原本を置き換えない`() {
        val repository = UploadPlanRepository(root, target)
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target))).value
        val file = root.resolve(target.directory).resolve("plan.json")
        file.writeText(file.readText().replace("\"version\": 1", "\"version\": 9"))
        val invalid = file.readBytes()
        assertIs<StoreResult.Failure>(repository.read())
        assertIs<StoreResult.Failure>(repository.save(saved))
        assertContentEquals(invalid, file.readBytes())
    }

    @Test fun `add後の元listとmap変更が旧予約を変えない`() {
        val documents = mutableListOf(document("one"))
        val ids = mutableMapOf("old" to "new")
        val added = UploadPlan(target).add(UploadPlanGroup("one", "event", documents, ids))
        documents.clear(); ids["old"] = "changed"
        assertEquals(listOf(document("one")), added.groups.single().documents)
        assertEquals(mapOf("old" to "new"), added.groups.single().itemIds)
    }

    @Test fun `save返却は入力のnested可変参照を保持しない`() {
        val documents = mutableListOf(document("one"))
        val ids = mutableMapOf("old" to "new")
        val groups = mutableListOf(UploadPlanGroup("one", "event", documents, ids))
        val repository = UploadPlanRepository(root, target)
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target, groups = groups))).value
        groups.clear(); documents.clear(); ids.clear()
        assertEquals(listOf(document("one")), saved.groups.single().documents)
        assertEquals(mapOf("old" to "new"), saved.groups.single().itemIds)
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value)
    }

    @Test fun `不正なfullIDを永続化しない`() {
        val repository = UploadPlanRepository(root, target)
        assertIs<StoreResult.Failure>(repository.save(UploadPlan(target,
            groups = listOf(group("one", document("one").copy(targetId = "event..one"))))))
        assertIs<StoreResult.Failure>(repository.save(UploadPlan(target,
            groups = listOf(group("one", document("one").copy(key = key("")))))))
        assertEquals(0L, assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value.revision)
    }

    @Test fun `宛先root外の保存と改竄原本の再保存を拒否する`() {
        val repository = UploadPlanRepository(root, target)
        val saved = assertIs<StoreResult.Success<UploadPlan>>(
            repository.save(UploadPlan(target).add(group("one", document("one"))))).value
        val file = root.resolve(target.directory).resolve("plan.json")
        file.writeText(file.readText().replace("event.one", "outside.one"))
        val original = file.readBytes()
        assertIs<StoreResult.Failure>(repository.read())
        assertIs<StoreResult.Failure>(repository.save(saved))
        assertContentEquals(original, file.readBytes())
    }

    @Test fun `copy経由で固定要求を解除しても保存境界で拒否する`() {
        val repository = UploadPlanRepository(root, target)
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target,
            groups = listOf(group("one", document("one")).copy(operationId = "request"))))).value
        assertIs<StoreResult.Failure>(repository.save(saved.copy(groups = emptyList())))
        assertIs<StoreResult.Failure>(repository.save(saved.copy(groups = saved.groups.map { it.copy(operationId = null) })))
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value)
    }

    @Test fun `実台帳のunknownは除去せず完了確認後だけ予定から外す`() {
        val repository = UploadPlanRepository(root, target)
        val ledger = LocalDocumentRepository(root.resolve("documents"), target)
        val origin = SavedUploadDocument(key("one"), 2, "saved", false, emptyMap())
        val destination = key("event.one").copy(source = target)
        val operation = SavedUploadOperation("request", target, listOf(origin),
            listOf(SavedUploadTargetVersion(destination, 0)),
            listOf(SavedUploadStep(destination, null, "saved", state = SavedUploadState.UNKNOWN)))
        val snapshot = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(
            ledger.publish(emptyMap(), emptyList(), upload = operation)).value
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target,
            groups = listOf(group("one", document("one")).copy(operationId = "request"))))).value
        assertIs<StoreResult.Failure>(repository.removeResolved(saved, "one", ledger))
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value)
        val confirmed = snapshot.uploads.single().copy(steps = operation.steps.map { it.copy(state = SavedUploadState.CONFIRMED) })
        assertIs<StoreResult.Success<*>>(ledger.publish(emptyMap(), emptyList(), upload = confirmed, expectedUploadRevision = confirmed.revision))
        assertTrue(assertIs<StoreResult.Success<UploadPlan>>(repository.removeResolved(saved, "one", ledger)).value.groups.isEmpty())
        assertEquals(1, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(ledger.read()).value.uploads.size)
    }

    @Test fun `未開始READYと台帳未作成を停止済みと推定しない`() {
        val repository = UploadPlanRepository(root, target)
        val ledger = LocalDocumentRepository(root.resolve("documents"), target)
        val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target,
            groups = listOf(group("one", document("one")).copy(operationId = "request"))))).value
        assertIs<StoreResult.Failure>(repository.removeResolved(saved, "one", ledger))
        val destination = key("event.one").copy(source = target)
        val ready = SavedUploadOperation("request", target, listOf(SavedUploadDocument(key("one"), 2, "saved", false, emptyMap())),
            listOf(SavedUploadTargetVersion(destination, 0)), listOf(SavedUploadStep(destination, null, "saved")))
        assertIs<StoreResult.Success<*>>(ledger.publish(emptyMap(), emptyList(), upload = ready))
        assertIs<StoreResult.Failure>(repository.removeResolved(saved, "one", ledger))
        assertEquals(saved, assertIs<StoreResult.Success<UploadPlan>>(repository.read()).value)
    }

    @Test fun `確定失敗と未実行取消は明示削除でき固定台帳は保持する`() {
        for (state in listOf(SavedUploadState.FAILED, SavedUploadState.CANCELLED)) {
            val owned = root.resolve(state.name)
            val repository = UploadPlanRepository(owned, target)
            val ledger = LocalDocumentRepository(owned.resolve("documents"), target)
            val destination = key("event.one").copy(source = target)
            val other = key("event.two").copy(source = target)
            val stopped = SavedUploadOperation("request", target,
                listOf(SavedUploadDocument(key("one"), 2, "saved", false, emptyMap())),
                listOf(SavedUploadTargetVersion(destination, 0), SavedUploadTargetVersion(other, 0)),
                listOf(SavedUploadStep(destination, null, "saved", state = state), SavedUploadStep(other, null, "other")))
            assertIs<StoreResult.Success<*>>(ledger.publish(emptyMap(), emptyList(), upload = stopped))
            val saved = assertIs<StoreResult.Success<UploadPlan>>(repository.save(UploadPlan(target,
                groups = listOf(group("one", document("one")).copy(operationId = "request"))))).value
            val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(ledger.read()).value
            assertTrue(assertIs<StoreResult.Success<UploadPlan>>(repository.removeResolved(saved, "one", ledger)).value.groups.isEmpty())
            assertEquals(before, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(ledger.read()).value)
        }
    }
}
