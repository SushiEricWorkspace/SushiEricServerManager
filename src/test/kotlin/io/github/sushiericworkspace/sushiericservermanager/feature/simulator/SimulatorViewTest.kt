package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.reforge.BowReforge
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageEffectIntervalKey
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageItemCooldownKey
import io.github.sushiericworkspace.combatcore.item.effect.ItemSpecialEffectRole
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPartTarget
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SimulatorViewTest {
    @Test fun `実FXMLと共通CSSで画面を表示し単一Stageへ再フォーカスして終了できる`() = onFxThread {
        SimulatorWindowManager.open(null)
        try {
            val stage = Stage.getWindows().filterIsInstance<Stage>().single { it.title.contains("戦闘シミュレーター") }
            SimulatorWindowManager.open(null)
            assertEquals(1, Stage.getWindows().filterIsInstance<Stage>().count { it.title.contains("戦闘シミュレーター") })
            stage.scene.root.applyCss(); stage.scene.root.layout()
            assertTrue(stage.scene.stylesheets.any { it.endsWith(AppScreen.SIMULATOR.css) })
            val image = stage.scene.root.snapshot(null, null)
            val png = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until png.height) for (x in 0 until png.width) png.setRGB(x, y, image.pixelReader.getArgb(x,y))
            ImageIO.write(png, "png", Path.of("build/simulator269.png").toFile())
        } finally { SimulatorWindowManager.close() }
        assertTrue(Stage.getWindows().filterIsInstance<Stage>().none { it.title.contains("戦闘シミュレーター") })
    }

    @Test fun `未保存の数値テキストをそのまま読み1行と複数行を同じCore経路で計算する`() = onFxThread {
        val form = SimulatorInputEditor()
        form.baseStats.fields.getValue(StatsType.PHYSICS_DAMAGE).text = "95"
        val first = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        val one = assertIs<SimulatorMultipleHitResult.Expected>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1, first, form.readMode())))
        assertEquals(100.0, one.result.meanCalculatedDamage)
        assertEquals(100.0, one.result.meanHealthDamage)
        val originalId = form.hits.first().hitId
        val added = form.addHit()
        form.move(added, -1)
        form.sample.isSelected = true
        form.seed.text = "42"
        val multiple = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(listOf(added.hitId, originalId), multiple.hits.map { it.hitId })
        val sample = assertIs<SimulatorMultipleHitResult.Sample>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(2, multiple, form.readMode())))
        assertEquals(200.0, sample.result.calculatedDamage)
        assertEquals(42L, sample.seed)
        assertTrue(sample.summary().contains("純HP減少: 200.0"))
        form.baseStats.fields.getValue(StatsType.PHYSICS_DAMAGE).text = "-"
        assertFailsWith<IllegalArgumentException> { form.readInput() }
        assertEquals(95.0, first.baseStats[StatsType.PHYSICS_DAMAGE])
    }

    @Test fun `手動装備を一度だけ集計し反対方向でもHPとguardの現在値を維持する`() = onFxThread {
        val form = SimulatorInputEditor()
        val slot = form.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
        slot.enabled.isSelected = true
        slot.type.value = ItemType.BOW
        slot.stats.fields.getValue(StatsType.PHYSICS_DAMAGE).text = "100"
        val prepared = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        val expected = assertIs<SimulatorMultipleHitResult.Expected>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1, prepared, form.readMode())))
        assertEquals(200.0, expected.result.meanCalculatedDamage)
        val shield = form.equipmentEditor.slots.getValue(StatsPart.OFF_HAND)
        shield.enabled.isSelected = true
        shield.type.value = ItemType.SHIELD
        form.playerIsSource.isSelected = false
        form.playerState.health.text = "45"
        form.playerState.overHeal.text = "20"
        form.playerState.stamina.text = "30"
        form.playerState.raising.isSelected = true
        form.playerState.blockUntil.text = "125"
        val reverse = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(45.0, reverse.initialState.target.health)
        assertEquals(20.0, reverse.initialState.target.overHeal)
        assertEquals(30.0, reverse.initialState.target.resources.stamina)
        assertEquals(125L, reverse.initialState.target.blockUntilTick)
        val guard = assertNotNull(reverse.preparedInputs.single().preparation.playerGuard)
        assertEquals(30.0, guard.currentStamina)
        assertTrue(guard.raising)
        assertTrue(reverse.hits.single().attack == SimulatorAttack.MobProjectile)
    }

    @Test fun `FXMLイベントから背景計算を開始し合計表示をCore結果へ一致させる`() {
        lateinit var view: SimulatorView
        onFxThread {
            view = SimulatorView.load()
            Utility.createScene(AppScreen.SIMULATOR, customRoot = view.root)
            view.root.applyCss(); view.root.layout()
            view.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "計算" }.fire()
            assertIs<SimulatorRunState.Running>(view.controller.workspace.state)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var done = false
            while (!done && System.nanoTime() < deadline) {
                onFxThread { done = view.controller.workspace.state is SimulatorRunState.Finished }
                if (!done) Thread.sleep(10)
            }
            assertTrue(done)
            onFxThread {
                val state = assertIs<SimulatorRunState.Finished>(view.controller.workspace.state)
                val label = assertIs<Label>(view.root.lookup("#resultLabel"))
                assertEquals(state.result.summary(), label.text)
                assertTrue(assertIs<Button>(view.root.lookup("#cancelButton")).isDisable)
            }
        } finally { onFxThread { view.dispose() } }
    }

    @Test fun `全Kindと両攻撃方向の手動指定を型対応を保って固定する`() = onFxThread {
        val form = SimulatorInputEditor()
        for (source in listOf(true, false)) for (kind in SimulatorAttackKind.entries) {
            form.playerIsSource.isSelected = source
            form.hits.single().kind.value = kind
            val fixed = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
            val attack = fixed.hits.single().attack
            assertEquals(source, attack.playerIsSource)
            when (kind) {
                SimulatorAttackKind.MELEE -> assertTrue(attack is SimulatorAttack.PlayerMelee || attack == SimulatorAttack.MobMelee)
                SimulatorAttackKind.PROJECTILE -> assertTrue(attack is SimulatorAttack.PlayerProjectile || attack == SimulatorAttack.MobProjectile)
                SimulatorAttackKind.MAGIC -> assertTrue(attack is SimulatorAttack.PlayerMagic || attack == SimulatorAttack.MobMagic)
            }
            assertIs<SimulatorMultipleHitResult.Expected>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1, fixed, form.readMode())))
        }
    }

    @Test fun `非対応強化は画面入力でも計算値を作らず明示理由を保つ`() = onFxThread {
        val form = SimulatorInputEditor()
        val slot = form.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
        slot.enabled.isSelected = true
        slot.type.value = ItemType.SWORD
        slot.levels.fields.getValue(MeleeSushiEricEnchantment.LIFE_STEAL).text = "1"
        val input = assertIs<SimulatorMultipleHitInput.Unsupported>(form.readInput())
        val result = assertIs<SimulatorMultipleHitResult.Unsupported>(SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1, input, form.readMode())))
        assertTrue(result.reason.contains("非対応"))
        assertTrue(result.summary().contains("ダメージ値なし"))
        form.hits.clear()
        assertFailsWith<IllegalArgumentException> { form.readInput() }
    }

    @Test fun `実FXMLの取消操作は完了値を表示せず画面終了で実行参照を解放する`() = onFxThread {
        val view = SimulatorView.load()
        try {
            Utility.createScene(AppScreen.SIMULATOR, customRoot = view.root)
            view.root.applyCss(); view.root.layout()
            view.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "計算" }.fire()
            assertTrue(view.controller.inputEditor.isDisable)
            assertIs<Button>(view.root.lookup("#cancelButton")).fire()
            assertIs<SimulatorMultipleHitResult.Cancelled>(assertIs<SimulatorRunState.Finished>(view.controller.workspace.state).result)
            assertFalse(view.controller.inputEditor.isDisable)
            assertTrue(assertIs<Label>(view.root.lookup("#resultLabel")).text.contains("ダメージ値なし"))
        } finally { view.dispose() }
        assertIs<SimulatorRunState.Idle>(view.controller.workspace.state)
    }

    @Test fun `実Homeの両モードから接続や保存なしでシミュレーターへ入れる`() = onFxThread {
        val modeField = EditorSession::class.java.getDeclaredField("mode").apply { isAccessible = true }
        val originalMode = EditorSession.mode
        try {
            for (mode in AppMode.entries) {
                modeField.set(EditorSession, mode)
                val home = FXMLLoader(javaClass.getResource(AppScreen.HOME.fxml!!)).load<Parent>()
                Utility.createScene(AppScreen.HOME, customRoot = home)
                home.applyCss(); home.layout()
                val button = home.lookupAll(".button").filterIsInstance<Button>().single { it.text == "戦闘シミュレーター" }
                assertTrue(button.isVisible && button.parent.isVisible)
                button.fire()
                assertEquals(1, Stage.getWindows().filterIsInstance<Stage>().count { it.title.contains("戦闘シミュレーター") })
                SimulatorWindowManager.close()
            }
        } finally { SimulatorWindowManager.close(); modeField.set(EditorSession, originalMode) }
    }

    @Test fun `現在HPとリソースは非負の正規化済み値だけ受けMobOHは0に固定する`() = onFxThread {
        val form = SimulatorInputEditor()
        val fields = listOf(form.playerState.health, form.playerState.overHeal, form.playerState.stamina, form.playerState.mana,
            form.mobState.health, form.mobState.stamina, form.mobState.mana)
        for (field in fields) {
            val original = field.text
            field.text = "-1"
            assertFailsWith<IllegalArgumentException> { form.readInput() }
            field.text = "1.2345"
            assertFailsWith<IllegalArgumentException> { form.readInput() }
            field.text = original
        }
        assertTrue(form.mobState.overHeal.isDisable)
        form.mobState.overHeal.text = "40"
        assertFailsWith<IllegalArgumentException> { form.readInput() }
        form.mobState.overHeal.text = "0"
        form.playerState.health.text = "0"
        form.playerState.overHeal.text = "0"
        form.playerState.stamina.text = "0"
        form.playerState.mana.text = "0"
        val zero = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(0.0, zero.initialState.source.health)
        assertEquals(0.0, zero.initialState.source.resources.stamina)
        assertEquals(0.0, zero.initialState.target.overHeal)
        form.playerState.health.text = "1.234000"
        assertEquals(1.234, assertIs<SimulatorMultipleHitInput.Ready>(form.readInput()).initialState.source.health)
    }

    @Test fun `履歴と間隔CDと発射状態を現在値から独立して保持する`() = onFxThread {
        val form = SimulatorInputEditor()
        val history = form.historyEditor.addHistory()
        history.item.text = "previous_sword"
        history.tick.text = "90"
        history.id.text = "7"
        val interval = form.historyEditor.addInterval()
        interval.kind.text = "kind-fixture"
        interval.effect.text = "effect-fixture"
        interval.tick.text = "95"
        val cooldown = form.historyEditor.addCooldown()
        cooldown.provider.text = "provider-fixture"
        cooldown.effect.text = "effect-fixture"
        cooldown.tick.text = "120"
        val hit = form.hits.single()
        hit.launchBaseDamage.text = "123"
        form.captureLaunch(hit)
        val launch = assertNotNull(hit.launchSnapshot)
        assertEquals(100L, launch.createdTick)
        assertEquals(123.0, launch.baseDamage)
        form.baseStats.fields.getValue(StatsType.PHYSICS_DAMAGE).text = "250"
        form.tick.text = "110"
        history.item.text = "edited_history"
        val fixed = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(8L, fixed.initialState.nextRecordId)
        assertEquals("edited_history", fixed.initialState.sourceAttackRecords.single().itemDataId)
        assertEquals(95L, fixed.initialState.intervals.effectTicks[DamageEffectIntervalKey("kind-fixture", "effect-fixture")])
        assertEquals(120L, fixed.initialState.itemCooldowns[DamageItemCooldownKey(ItemSpecialEffectRole.SOURCE, "provider-fixture", "effect-fixture")])
        assertEquals(95.0, assertNotNull(fixed.hits.single().launchSnapshot?.attacker).requirePlayerStats()[StatsType.PHYSICS_DAMAGE])
        assertEquals(250.0, fixed.preparedInputs.single().player.snapshot.requirePlayerStats()[StatsType.PHYSICS_DAMAGE])
        assertSame(launch, hit.launchSnapshot)
        hit.clearLaunch.fire()
        assertEquals(null, hit.launchSnapshot)
        assertNotNull(fixed.hits.single().launchSnapshot)
        form.historyEditor.nextRecordId.text = "7"
        assertFailsWith<IllegalArgumentException> { form.readInput() }
        form.historyEditor.nextRecordId.text = ""
        val duplicate = form.historyEditor.addHistory()
        duplicate.id.text = "7"
        assertFailsWith<IllegalArgumentException> { form.readInput() }
        duplicate.id.text = "8"
        duplicate.tick.text = "80"
        assertFailsWith<IllegalArgumentException> { form.readInput() }
    }

    @Test fun `弓とクロスボウ本数効率とReforgeおよびBASE SKILL対象倍率を既存型へ渡す`() = onFxThread {
        val form = SimulatorInputEditor()
        val slot = form.equipmentEditor.slots.getValue(StatsPart.MAIN_HAND)
        slot.enabled.isSelected = true
        slot.type.value = ItemType.BOW
        slot.multi.text = "1"
        slot.reforge.value = BowReforge.entries.first()
        val multiplier = slot.addMultiplier()
        multiplier.target.value = "Parts"
        multiplier.parts.getValue(StatsPart.BASE).isSelected = true
        multiplier.parts.getValue(StatsPart.SKILL).isSelected = true
        multiplier.factor.text = "1.2"
        form.addHit(); form.addHit()
        form.shotProfile.value = "BOW"
        val bow = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(3, assertNotNull(bow.shotPlan).projectileCount)
        assertTrue(bow.equipment.activeUpgrades.any { it.definition == slot.reforge.value })
        val record = bow.equipment.input.items.getValue(StatsPart.MAIN_HAND).baseData
        assertEquals(setOf(StatsPart.BASE, StatsPart.SKILL), assertIs<StatsPartTarget.Parts<StatsPart>>(record.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE).single().target).parts)
        slot.type.value = ItemType.CROSSBOW
        slot.arrowCount.text = "3"
        slot.arrowEfficiency.text = "0.5"
        slot.reforge.value = null
        form.shotProfile.value = "STANDARD_CROSSBOW"
        val crossbow = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(3, assertNotNull(crossbow.shotPlan).projectileCount)
        assertEquals(2, assertNotNull(crossbow.shotPlan).arrowConsumption)
    }

    @Test fun `履歴の中間行を削除して追加してもIDが重複しない`() = onFxThread {
        val form = SimulatorInputEditor()
        form.historyEditor.addHistory()
        val middle = form.historyEditor.addHistory()
        form.historyEditor.addHistory()
        middle.remove.fire()
        assertEquals("4", form.historyEditor.addHistory().id.text)
        val fixed = assertIs<SimulatorMultipleHitInput.Ready>(form.readInput())
        assertEquals(listOf(1L, 3L, 4L), fixed.initialState.sourceAttackRecords.map { it.recordId })
        assertEquals(5L, fixed.initialState.nextRecordId)
    }

    private fun onFxThread(action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(30, TimeUnit.SECONDS)
    }
}
