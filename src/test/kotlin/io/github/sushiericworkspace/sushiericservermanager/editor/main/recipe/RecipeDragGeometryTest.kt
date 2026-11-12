package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import javafx.geometry.BoundingBox
import javafx.geometry.Point2D
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class RecipeDragGeometryTest {
    private val geometry = RecipeDragGeometry(listOf(1.0, 2.0, 900.0, 700.0, 890.0, 660.0, 1.0, 1.0), Point2D(10.0, 20.0), Point2D(30.0, 40.0), listOf(BoundingBox(0.0, 0.0, 100.0, 100.0)))
    private val positions = (1..8).map { Point2D(it * 20.0, 10.0) }
    private fun safety() = RecipeNativeSafety(1, geometry, true, true, 0)
    private fun plan(source: Any) = RecipeNativeAwtPlan(1, source, positions, geometry).also { it.publishInitial(safety()) }

    @Test fun 失効済みまたは期限切れの遅着初期化は作用前に拒否する() {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        var effects = 0
        for ((enabled, arm, now) in listOf(Triple(false, true, 9L), Triple(true, false, 9L), Triple(true, true, 10L))) {
            active.set(enabled); armed.set(arm)
            assertFailsWith<IllegalStateException> {
                requireRecipeNativePreparation(active, armed, 10, now); effects++
            }
        }
        assertEquals(0, effects)
    }
    @Test fun 取得中に期限へ達したtupleは公開しない() {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        requireRecipeNativePreparation(active, armed, 10, 9)
        val captured = geometry.copy()
        var published: RecipeDragGeometry? = null
        assertFailsWith<IllegalStateException> {
            requireRecipeNativePreparation(active, armed, 10, 10); published = captured
        }
        assertNull(published)
    }

    private fun releaseReadyPlan(): RecipeNativeAwtPlan {
        val source = Any(); val plan = plan(source)
        assertNotNull(plan.consume(1, 0)); assertTrue(plan.bootstrap(0)); assertNotNull(plan.consume(2, 0))
        for (step in 2..7) { assertTrue(plan.over(source, positions[step - 1], safety())); assertNotNull(plan.consume(step + 1, 0)) }
        assertTrue(plan.over(source, positions.last(), safety())); assertTrue(plan.reserveCue())
        return plan
    }
    @Test fun 正常releaseの前途中後の観測は無値で終わり許可を復活しない() {
        for (phase in 0..3) {
            val plan = releaseReadyPlan(); var reads = 0; var published = 0; var rejected = 0
            if (phase == 0 || phase == 3) { plan.releasing(safety(), 0); if (phase == 3) plan.closeNormally() }
            val value = reevaluateRecipeNativeSafety(geometry, { plan.moving }, java.util.concurrent.atomic.AtomicBoolean(),
                { reads++; if (phase == 1) plan.releasing(safety(), 0); safety() },
                { plan.requireSafety(it, 0); if (phase == 2) plan.releasing(safety(), 0) }, { published++ },
                { _, _, _ -> rejected++ }, { plan.normalStopping })
            assertNull(value); assertEquals(if (phase == 0 || phase == 3) 0 else 1, reads)
            assertEquals(0, published); assertEquals(0, rejected); assertNull(plan.consume(8, 0)); assertFalse(plan.moving)
        }
    }
    @Test fun 失敗invalidは正常closeや後続失敗で上書きされない() {
        val plan = releaseReadyPlan(); plan.releasing(safety(), 0)
        val original = IllegalArgumentException("first"); val later = IllegalStateException("later")
        assertSame(original, plan.invalidate(original))
        assertSame(original, assertFailsWith<IllegalArgumentException> { plan.closeNormally() })
        assertSame(original, plan.failure); assertSame(original, plan.invalidate(later))
        assertFalse(plan.normalStopping); assertFalse(plan.active); assertNull(plan.consume(8, 0))
        var reads = 0; var published = 0
        val caught = assertFailsWith<RecipeNativeSafetyRejected> {
            reevaluateRecipeNativeSafety(geometry, { plan.moving }, java.util.concurrent.atomic.AtomicBoolean(), { reads++; safety() }, {}, { published++ }, { _, _, error -> plan.invalidate(error) }, { plan.normalStopping })
        }
        assertEquals(RecipeNativeSafetyReason.INACTIVE, caught.reason); assertSame(original, plan.failure)
        assertEquals(0, reads); assertEquals(0, published)
    }
    @Test fun 正常releaseと競合してもgetter実値異常や期限超過を隠さない() {
        for (kind in 0..2) {
            val plan = releaseReadyPlan(); val original = IllegalArgumentException("getter"); var published = 0; var rejected = 0
            val caught = assertFails {
                    reevaluateRecipeNativeSafety(geometry, { plan.moving }, java.util.concurrent.atomic.AtomicBoolean(), {
                        plan.releasing(safety(), 0)
                        if (kind == 0) throw original
                        if (kind == 1) safety().copy(geometry = geometry.copy(source = Point2D(11.0, 20.0))) else safety()
                    }, { plan.requireSafety(it, if (kind == 2) 250_000_001 else 0) }, { published++ },
                        { _, _, error -> rejected++; plan.invalidate(error) }, { plan.normalStopping })
            }
            if (kind == 0) assertSame(original, caught) else assertIs<IllegalStateException>(caught)
            assertSame(caught, plan.failure)
            assertEquals(1, rejected); assertEquals(0, published); assertFalse(plan.normalStopping)
        }
    }
    @Test fun arm停止や既firstFailureは正常終了として無視しない() {
        for (arm in listOf(false, true)) {
            val plan = releaseReadyPlan(); plan.releasing(safety(), 0)
            val original = IllegalArgumentException("first"); val first = java.util.concurrent.atomic.AtomicReference<Throwable?>(if (arm) original else null)
            var reads = 0
            assertFailsWith<RecipeNativeSafetyRejected> {
                reevaluateRecipeNativeSafety(geometry, { plan.moving && arm }, java.util.concurrent.atomic.AtomicBoolean(), { reads++; safety() }, {}, {},
                    { _, _, error -> first.compareAndSet(null, error) }, { plan.normalStopping && arm && first.get() == null })
            }
            assertEquals(0, reads); if (arm) assertSame(original, first.get()) else assertNotNull(first.get())
        }
    }

    @Test fun 正常releaseと競合する評価再入も無値終了にしない() {
        val plan = releaseReadyPlan(); plan.releasing(safety(), 0)
        val evaluating = java.util.concurrent.atomic.AtomicBoolean(true)
        val trace = RecipeNativeInputTrace(Thread.currentThread()); var reads = 0; var published = 0
        val caught = assertFailsWith<RecipeNativeSafetyRejected> {
            reevaluateRecipeNativeSafety(geometry, { plan.moving }, evaluating, { reads++; safety() }, {}, { published++ },
                { reason, current, error -> rejectRecipeNativeSafety(plan, trace.armed, error) { trace.recordGeometry(reason, geometry, current, error) } },
                { plan.normalStopping && trace.armed.get() })
        }
        assertEquals(RecipeNativeSafetyReason.REENTRANT, caught.reason)
        assertEquals(0, reads); assertEquals(0, published); assertFalse(trace.armed.get()); assertSame(caught, plan.failure)
        assertTrue(evaluating.get()); assertFalse(plan.normalStopping); assertNull(plan.consume(8, 0))
    }
    @Test fun 完了後の解除失敗でも遅着した元失敗を保持する() {
        for (kind in 0..3) {
            val original = IllegalArgumentException("original"); val cleanup = IllegalStateException("cleanup")
            val caught = preserveRecipeNativeCleanupFailure(if (kind == 0) original else null,
                if (kind == 1) original else null, if (kind == 2) original else null, cleanup)
            assertSame(if (kind == 3) cleanup else original, caught)
            if (kind == 3) assertTrue(cleanup.suppressed.isEmpty()) else assertSame(cleanup, original.suppressed.single())
        }
    }

    @Test fun 一致する自己Stageと対象だけ次の注入へ進む() {
        requireRecipeDragGeometry(geometry, geometry.copy())
    }
    @Test fun 座標やviewport変更は次の注入を止める() {
        listOf(geometry.copy(stage = geometry.stage.map { it + 1 }), geometry.copy(source = Point2D(11.0, 20.0)),
            geometry.copy(viewports = listOf(BoundingBox(0.0, 0.0, 101.0, 100.0)))).forEach {
            assertFailsWith<IllegalStateException> { requireRecipeDragGeometry(geometry, it) }
        }
    }
    @Test fun 同じ世代stepの許可は競合しても一度だけ消費する() {
        val plan = plan(Any()); val start = CountDownLatch(1); val taken = AtomicInteger()
        val workers = (1..2).map { thread { assertTrue(start.await(5, TimeUnit.SECONDS)); if (plan.consume(1, 0) != null) taken.incrementAndGet() } }
        start.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, taken.get()); assertNull(plan.consume(1, 0))
    }
    @Test fun 遅着重複旧gestureや誤座標は次stepを再発行しない() {
        val source = Any(); val plan = plan(source)
        assertNotNull(plan.consume(1, 0)); assertTrue(plan.bootstrap(50_000_000)); assertNotNull(plan.consume(2, 50_000_000))
        assertFalse(plan.over(Any(), positions[1], safety()))
        assertFalse(plan.over(source, positions[0], safety()))
        assertTrue(plan.over(source, positions[1], safety()))
        assertFalse(plan.over(source, positions[1], safety()))
        assertNotNull(plan.consume(3, 50_000_000)); assertNull(plan.consume(3, 50_000_000))
        assertFalse(plan.over(source, positions[1], safety()))
    }
    @Test fun 消費直後に失効した許可も新入力には使えない() {
        val source = Any(); val plan = plan(source); val permit = assertNotNull(plan.consume(1, 0))
        plan.invalidate()
        assertFailsWith<IllegalStateException> { plan.requireCurrent(permit, safety(), 0) }
        assertNull(plan.consume(1, 0)); assertFalse(plan.over(source, positions[0], safety()))
        assertFailsWith<IllegalStateException> { plan.requireSafety(safety().copy(generation = 2), 0) }
    }
    @Test fun bootstrapは初期snapshot250ms以内だけを使う() {
        val plan = plan(Any()); assertNotNull(plan.consume(1, 0))
        assertFailsWith<IllegalStateException> { plan.bootstrap(250_000_001) }
        assertTrue(plan.bootstrap(250_000_000)); assertNotNull(plan.consume(2, 250_000_000))
    }
    @Test fun 最終実OVERとcueは全8step後に一度だけ確定する() {
        val source = Any(); val plan = plan(source)
        assertFalse(plan.reserveCue()); assertNotNull(plan.consume(1, 0)); assertTrue(plan.bootstrap(0)); assertNotNull(plan.consume(2, 0))
        for (step in 2..7) {
            assertTrue(plan.over(source, positions[step - 1], safety())); assertFalse(plan.reserveCue())
            assertNotNull(plan.consume(step + 1, 0))
        }
        assertFalse(plan.reserveCue()); assertTrue(plan.over(source, positions.last(), safety()))
        assertTrue(plan.reserveCue()); assertFalse(plan.reserveCue()); plan.releasing(safety(), 0); assertFalse(plan.moving)
        assertFalse(plan.over(source, positions.last(), safety()))
    }
    @Test fun 自己所有入力は一度解放し再cleanupで入力を増やさない() {
        val owned = RecipeNativeOwnedInput(); var mouse = 0; var keys = 0
        owned.pressPrimary(); owned.pressKey(1)
        repeat(2) { owned.releaseAll({ mouse++ }, { keys++ }) }
        assertTrue(owned.released); assertEquals(1, mouse); assertEquals(1, keys)
    }
    @Test fun 解放例外は元例外と未解放を保持し同じ入力を再試行しない() {
        val owned = RecipeNativeOwnedInput(); val mouseError = IllegalStateException("mouse"); val keyError = IllegalArgumentException("key")
        var mouse = 0; var keys = 0; owned.pressPrimary(); owned.pressKey(1)
        val actual = assertFailsWith<IllegalStateException> { owned.releaseAll({ mouse++; throw mouseError }, { keys++; throw keyError }) }
        assertSame(mouseError, actual); assertSame(keyError, actual.suppressed.single()); assertFalse(owned.released)
        owned.releaseAll({ mouse++ }, { keys++ }); assertEquals(1, mouse); assertEquals(1, keys); assertFalse(owned.released)
    }
    @Test fun device座標と整数化点が一致しない入力は拒否する() {
        val display = RecipeNativeDisplay(0.0, 0.0, 1600.0, 1000.0, true)
        assertEquals(0, requireRecipeNativeDisplay(display, 1.0, 1.0, listOf(display), geometry, positions))
        assertFails { requireRecipeNativeDisplay(display, 2.0, 1.0, listOf(display), geometry, positions) }
        assertFails { requireRecipeNativeDisplay(display, 1.0, 1.0, listOf(display.copy(identity = false)), geometry, positions) }
        assertFails { requireRecipeNativeDisplay(display, 1.0, 1.0, List(9) { display }, geometry, positions) }
        assertFails { requireRecipeNativeDisplay(display, 1.0, 1.0, listOf(display), geometry, listOf(Point2D(1599.9, 20.0))) }
    }
    @Test fun 同時失敗でも最初の診断だけを公開し採取中は未公開を保つ() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fxThread = object : Thread() { override fun getStackTrace(): Array<StackTraceElement> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return emptyArray() } }
        val trace = RecipeNativeInputTrace(fxThread); val first = thread { trace.fail(IllegalStateException("first")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); val second = thread { trace.fail(IllegalArgumentException("second")) }
            second.join(5000); assertFalse(second.isAlive); assertNull(trace.diagnostic); assertFalse(trace.armed.get())
        } finally { release.countDown(); first.join(5000) }
        assertFalse(first.isAlive); val diagnostic = assertNotNull(trace.diagnostic)
        assertEquals(IllegalStateException::class.java.name, diagnostic["exception"]?.jsonPrimitive?.content)
        trace.fail(IllegalArgumentException("late")); assertSame(diagnostic, trace.diagnostic)
    }
    @Test fun 診断採取失敗は未公開のまま元例外へ残す() {
        val captureFailure = IllegalStateException("capture failure")
        val fxThread = object : Thread() { override fun getStackTrace(): Array<StackTraceElement> = throw captureFailure }
        val trace = RecipeNativeInputTrace(fxThread); val original = IllegalArgumentException("original")
        trace.fail(original); assertNull(trace.diagnostic); assertFalse(trace.armed.get()); assertSame(captureFailure, original.suppressed.single())
        trace.fail(IllegalStateException("late")); assertNull(trace.diagnostic)
    }
    @Test fun callbackは総数を数え最初16件だけ保持する() {
        val source = Any(); val plan = plan(source); val trace = RecipeNativeInputTrace(Thread.currentThread()); var built = 0
        repeat(1000) { i -> val kind = if (i % 2 == 0) RecipeNativeCallbackKind.OVER else RecipeNativeCallbackKind.ENTERED_TARGET
            trace.observeCallback(kind) { built++; plan.observation(kind, source, positions.first(), true) } }
        val record = trace.callbacksJson(); assertEquals(16, built); assertEquals(16, record.getValue("samples").jsonArray.size)
        assertEquals("1000", record.getValue("totalCount").jsonPrimitive.content); assertTrue(record.toString().toByteArray().size < 16384)
    }

    @Test fun 失効と消費の競合後は必ず次入力が閉じる() {
        val plan = plan(Any()); val start = CountDownLatch(1)
        val permit = java.util.concurrent.atomic.AtomicReference<RecipeNativePermit?>()
        val taker = thread { start.await(); permit.set(plan.consume(1, 0)) }
        val invalidator = thread { start.await(); plan.invalidate() }
        start.countDown(); listOf(taker, invalidator).forEach { it.join(5000); assertFalse(it.isAlive) }
        assertFalse(plan.active); assertNull(plan.consume(1, 0))
        permit.get()?.let { value -> assertFailsWith<IllegalStateException> { plan.requireCurrent(value, safety(), 0) } }
    }

    @Test fun 背景終端済みなら保持せず保存後に有限返却する() {
        var published = 0; var awaited = 0
        retainRecipeNativeOwner(true, { published++ }, { awaited++ })
        assertEquals(1, published); assertEquals(0, awaited)
    }
    @Test fun 保存失敗しても背景自然終端までは通常返却しない() {
        listOf<Throwable?>(null, IllegalStateException("publish failure")).forEach { original ->
            val entered = CountDownLatch(1); val natural = CountDownLatch(1)
            val returned = CountDownLatch(1); val caught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val owner = thread {
                try { retainRecipeNativeOwner(false, { original?.let { throw it } }, { entered.countDown(); check(natural.await(5, TimeUnit.SECONDS)) }) }
                catch (error: Throwable) { caught.set(error) }
                finally { returned.countDown() }
            }
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(owner.isAlive); assertEquals(1L, returned.count) }
            finally { natural.countDown(); owner.join(5000) }
            assertFalse(owner.isAlive); assertSame(original, caught.get())
        }
    }

    @Test fun 同値の再評価はSafetyだけ更新して消費済許可を復活しない() {
        val plan = plan(Any()); val permit = assertNotNull(plan.consume(1, 0)); var reads = 0; var latest = safety()
        val value = safety().copy(at = 200_000_000)
        reevaluateRecipeNativeSafety(geometry, { plan.moving }, java.util.concurrent.atomic.AtomicBoolean(),
            { reads++; value }, { plan.requireSafety(it, 200_000_000) }, { latest = it }, { _, _, error -> throw error })
        assertEquals(1, reads); assertSame(value, latest); assertNull(plan.consume(1, 200_000_000))
        assertFailsWith<IllegalStateException> { plan.requireCurrent(permit, latest, 250_000_001) }
    }
    @Test fun 実値差は拒否し最初の有界数値差分だけを保持する() {
        val trace = RecipeNativeInputTrace(Thread.currentThread()); var published = 0
        val changed = geometry.copy(stage = geometry.stage.map { it + 1 }, source = Point2D(11.0, 21.0), target = Point2D(31.0, 41.0))
        assertFailsWith<IllegalStateException> {
            reevaluateRecipeNativeSafety(geometry, { true }, java.util.concurrent.atomic.AtomicBoolean(),
                { safety().copy(geometry = changed) }, { requireRecipeDragGeometry(geometry, it.geometry) }, { published++ },
                { reason, current, error -> trace.recordGeometry(reason, geometry, current, error) })
        }
        assertEquals(0, published); assertTrue(trace.geometryDiagnosticAttempted)
        val record = assertNotNull(trace.geometryDiagnostic)
        assertEquals("GEOMETRY_NOT_EQUAL", record.getValue("reason").jsonPrimitive.content)
        assertEquals(8, record.getValue("differences").jsonArray.size); assertTrue(record.toString().toByteArray().size <= 2048)
        trace.recordGeometry(RecipeNativeSafetyReason.UNAVAILABLE, geometry, null, IllegalStateException())
        assertSame(record, trace.geometryDiagnostic)
        val old = geometry.copy(viewports = List(20) { BoundingBox(0.0, 0.0, 100.0, 100.0) })
        val new = old.copy(viewports = old.viewports.mapIndexed { i, bounds -> if (i == 19) BoundingBox(1.0, 0.0, 100.0, 100.0) else bounds })
        val bounded = recipeNativeGeometryDiagnostic(RecipeNativeSafetyReason.GEOMETRY_NOT_EQUAL, old, new)
        assertTrue(bounded.getValue("differences").jsonArray.isEmpty()); assertEquals("true", bounded.getValue("truncated").jsonPrimitive.content)
    }
    @Test fun getter例外と診断失敗は元例外を保持する() {
        val original = IllegalArgumentException("getter"); val diagnostic = IllegalStateException("diagnostic")
        val evaluating = java.util.concurrent.atomic.AtomicBoolean(); var published = 0
        val caught = assertFailsWith<IllegalArgumentException> {
            reevaluateRecipeNativeSafety(geometry, { true }, evaluating, { throw original }, {}, { published++ }, { reason, current, _ ->
                assertEquals(RecipeNativeSafetyReason.UNAVAILABLE, reason); assertNull(current); throw diagnostic
            })
        }
        assertSame(original, caught); assertSame(diagnostic, original.suppressed.single()); assertFalse(evaluating.get()); assertEquals(0, published)
    }
    @Test fun 再評価の再入は追加getterなしで失効する() {
        val plan = plan(Any()); val evaluating = java.util.concurrent.atomic.AtomicBoolean(); val trace = RecipeNativeInputTrace(Thread.currentThread())
        var reads = 0; var published = 0
        val reject: (RecipeNativeSafetyReason, RecipeDragGeometry?, Throwable) -> Unit = { reason, current, error ->
            trace.recordGeometry(reason, geometry, current, error); plan.invalidate(error)
        }
        val caught = assertFailsWith<RecipeNativeSafetyRejected> {
            reevaluateRecipeNativeSafety(geometry, { plan.moving }, evaluating, {
                reads++
                assertNotNull(reevaluateRecipeNativeSafety(geometry, { plan.moving }, evaluating, { reads++; safety() }, {}, { published++ }, reject))
            }, {}, { published++ }, reject)
        }
        assertEquals(RecipeNativeSafetyReason.REENTRANT, caught.reason); assertEquals(1, reads); assertEquals(0, published)
        assertFalse(plan.active); assertFalse(evaluating.get()); assertNull(plan.consume(1, 0))
        assertEquals("REENTRANT", assertNotNull(trace.geometryDiagnostic).getValue("reason").jsonPrimitive.content)
    }
    @Test fun 失効close後と評価途中の失効は読取とpublishを再開しない() {
        val closed = plan(Any()); closed.invalidate(); var reads = 0; var published = 0
        assertFailsWith<RecipeNativeSafetyRejected> {
            reevaluateRecipeNativeSafety(geometry, { closed.moving }, java.util.concurrent.atomic.AtomicBoolean(), { reads++; safety() }, {}, { published++ }, { _, _, _ -> })
        }
        assertEquals(0, reads); assertEquals(0, published)
        val active = plan(Any())
        assertFailsWith<RecipeNativeSafetyRejected> {
            reevaluateRecipeNativeSafety(geometry, { active.moving }, java.util.concurrent.atomic.AtomicBoolean(), { reads++; active.invalidate(); safety() }, {}, { published++ }, { _, _, _ -> })
        }
        assertEquals(1, reads); assertEquals(0, published); assertNull(active.consume(1, 0))
    }

    @Test fun 拒否確定後は診断採取中でも背景入力許可が閉じている() {
        val plan = plan(Any()); val permit = assertNotNull(plan.consume(1, 0)); val trace = RecipeNativeInputTrace(Thread.currentThread())
        val entered = CountDownLatch(1); val checked = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(); val unexpected = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val background = thread {
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                try { plan.requireCurrent(permit, safety(), 0); accepted.set(true) }
                catch (_: IllegalStateException) { }
            } catch (error: Throwable) { unexpected.set(error) }
            finally { checked.countDown() }
        }
        val changed = geometry.copy(source = Point2D(11.0, 20.0))
        val caught = try {
            assertFailsWith<IllegalStateException> {
                reevaluateRecipeNativeSafety(geometry, { plan.moving }, java.util.concurrent.atomic.AtomicBoolean(),
                    { safety().copy(geometry = changed) }, { requireRecipeDragGeometry(geometry, it.geometry) }, {},
                    { reason, current, error -> rejectRecipeNativeSafety(plan, trace.armed, error) {
                        entered.countDown(); check(checked.await(5, TimeUnit.SECONDS))
                        trace.recordGeometry(reason, geometry, current, error)
                    } })
            }
        } finally { entered.countDown(); background.join(5000) }
        assertFalse(background.isAlive); assertNull(unexpected.get()); assertFalse(accepted.get())
        assertFalse(plan.active); assertFalse(trace.armed.get()); assertTrue(caught.suppressed.isEmpty())
        assertNotNull(trace.geometryDiagnostic)
    }
}
