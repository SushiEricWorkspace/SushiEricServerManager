package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemDetail
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPartTarget
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsType
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.TreeRow
import javafx.scene.Node
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemSimulatorImport
import io.github.sushiericworkspace.sushiericservermanager.editor.component.EditorSpinnerFactory
import io.github.sushiericworkspace.sushiericservermanager.util.NumericSpinnerFactory
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.detail.toMutable
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Popup
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.*
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class ItemSimulatorCaptureTest {
    @Test fun `mouseとSpaceEnterとactionをScene履歴前に消費し一回だけcopyする`() = fx {
        fixture { root, button, stage ->
            var history=0; var snapshots=0; var accepted=0
            val original=MutableItemBaseData(id="sword")
            val model=original.deepCopy()
            stage.scene.addEventFilter(MouseEvent.MOUSE_PRESSED) { history++ }
            stage.scene.addEventFilter(KeyEvent.KEY_PRESSED) { history++ }
            ItemSimulatorImport(root,button,Label(),{ false },{ snapshots++; SimulatorImportedItem.fromEdited(model) },{ accepted++ })
            Event.fireEvent(button,mouse())
            Event.fireEvent(button,mouse(type=MouseEvent.MOUSE_RELEASED))
            Event.fireEvent(button,mouse(type=MouseEvent.MOUSE_CLICKED))
            for (code in listOf(KeyCode.SPACE,KeyCode.ENTER)) {
                Event.fireEvent(button,KeyEvent(KeyEvent.KEY_PRESSED,"","",code,false,false,false,false))
                Event.fireEvent(button,KeyEvent(KeyEvent.KEY_RELEASED,"","",code,false,false,false,false))
            }
            button.fire()
            button.executeAccessibleAction(javafx.scene.AccessibleAction.FIRE)
            button.onAction.handle(javafx.event.ActionEvent(button,button))
            Event.fireEvent(button,mouse(MouseButton.SECONDARY))
            Event.fireEvent(button,mouse(MouseButton.MIDDLE))
            assertEquals(6,snapshots); assertEquals(6,accepted); assertEquals(0,history)
            assertEquals(original,model)
        }
    }

    @Test fun `実Itemの通常数値Stats倍率Spinnerでvalid未確定とinvalid空欄を非focusでも拒否する`() = fx {
        fixture { root, button, _ ->
            val message=Label(); var copied=0
            val capture=ItemSimulatorImport(root,button,message,{ false },{ copied++; null },{})
            val other=MutableItemBaseData(id="other").apply { display.displayName="other" }
            val pickaxe=MutableItemBaseData(id="pickaxe",itemDetail=MutableItemDetail(
                content=ItemType.PICKAXE.createContent().toMutable())).apply { display.displayName="pickaxe" }
            val stats=MutableItemBaseData(id="stats").apply {
                display.displayName="stats"
                this.stats[StatsType.PHYSICS_DAMAGE]=1.0
                statMultipliers[StatsType.PHYSICS_DAMAGE]=mutableListOf(
                    ItemStatMultiplier(StatsPartTarget.Self,1.1))
            }
            val cases=listOf(other to TreeRow.Editor.DetailContent,
                pickaxe to TreeRow.Editor.DetailContent,
                stats to TreeRow.Editor.StatsContent)
            var spinnerCount=0
            for ((model,row) in cases) {
                val graphic=ItemEditorFactory(model,{}).createGraphic(row)
                root.children.add(0,graphic)
                val before=model.deepCopy()
                val values=nodes(graphic).filterIsInstance<Spinner<*>>().toList()
                assertTrue(values.isNotEmpty()); spinnerCount+=values.size
                for (spinner in values.filter { it.isEditable }) {
                    val originalText=spinner.editor.text
                    for (text in listOf("2","","999999")) {
                        spinner.editor.text=text
                        button.fire()
                        assertTrue(message.text.contains("未確定"))
                        assertNotNull(capture.rejectionReason()); assertEquals(before,model)
                    }
                    spinner.editor.text=originalText
                }
                root.children.remove(graphic)
            }
            assertTrue(spinnerCount >= 4); assertEquals(0,copied)
        }
    }

    @Test fun `busyとownedPopupと表示終了を拒否してfocusやtextを変更しない`() = fx {
        fixture { root, button, stage ->
            var busy=true; var copied=0
            val text=TextField("keep"); root.children.add(0,text)
            val message=Label()
            val capture=ItemSimulatorImport(root,button,message,{ busy },{ copied++; null },{})
            val focus=stage.scene.focusOwner
            Event.fireEvent(button,mouse())
            assertEquals("keep",text.text); assertSame(focus,stage.scene.focusOwner); assertEquals(0,copied)
            busy=false
            val popup=Popup().apply { content.add(Label("owned")); show(stage) }
            try { assertTrue(capture.rejectionReason()!!.contains("ポップアップ")) } finally { popup.hide() }
            stage.close()
            assertNotNull(capture.rejectionReason())
        }
    }

    @Test fun `TextとIME入力focus中の拒否は入力とfocusとmodelを変えない`() {
        lateinit var stage:Stage; lateinit var text:TextField; lateinit var button:Button; lateinit var message:Label
        val model=MutableItemBaseData(id="typed")
        val original=model.deepCopy(); var copies=0
        fx {
            text=TextField("typing"); button=Button("取込"); message=Label()
            val root=VBox(text,button,message)
            stage=Stage().apply { scene=Scene(root,400.0,300.0); show(); toFront(); requestFocus() }
            text.textProperty().addListener { _,_,value -> model.display.displayName=value }
            ItemSimulatorImport(root,button,message,{ false },{ copies++; SimulatorImportedItem.fromEdited(model) },{})
            text.requestFocus()
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!fx { text.requestFocus(); stage.scene.focusOwner === text }) { assertTrue(System.nanoTime()<deadline,"fixtureの入力focusが必要です"); Thread.sleep(20) }
            fx {
                Event.fireEvent(text,InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    listOf(InputMethodTextRun("あ",InputMethodHighlight.UNSELECTED_RAW)),"",0))
                val beforeText=text.text; val beforeModel=model.deepCopy(); val focus=stage.scene.focusOwner
                Event.fireEvent(button,mouse())
                assertEquals(beforeText,text.text); assertEquals(beforeModel,model)
                assertSame(focus,stage.scene.focusOwner); assertEquals(0,copies)
                assertTrue(message.text.contains("未確定")); assertEquals("",original.display.displayName)
            }
        } finally { fx { stage.close() } }
    }

    @Test fun `dispose後はcaptureを解除して取込actionを実行しない`() = fx {
        fixture { root,button,stage ->
            var copied=0; var sceneEvents=0
            val capture=ItemSimulatorImport(root,button,Label(),{false},{copied++;null},{})
            stage.scene.addEventFilter(MouseEvent.MOUSE_PRESSED) { sceneEvents++ }
            capture.dispose()
            Event.fireEvent(button,mouse()); button.fire()
            assertEquals(0,copied); assertEquals(1,sceneEvents)
        }
    }

    private fun nodes(node:Node):Sequence<Node> = sequence {
        yield(node); if (node is Parent) for(child in node.childrenUnmodifiable) yieldAll(nodes(child))
    }

    private fun fixture(action:(VBox,Button,Stage)->Unit) {
        val button=Button("取込"); val root=VBox(button); val stage=Stage().apply { scene=Scene(root,400.0,300.0); show() }
        try { action(root,button,stage) } finally { stage.close() }
    }
    private fun mouse(button:MouseButton=MouseButton.PRIMARY,type:javafx.event.EventType<MouseEvent> = MouseEvent.MOUSE_PRESSED) = MouseEvent(type,0.0,0.0,0.0,0.0,button,1,false,false,false,false,true,false,false,false,false,false,null)
    private fun <T> fx(action:()->T):T {
        val ready=CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } } catch (_:IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20,TimeUnit.SECONDS)); val task=FutureTask(action); Platform.runLater(task); return task.get(30,TimeUnit.SECONDS)
    }
}
