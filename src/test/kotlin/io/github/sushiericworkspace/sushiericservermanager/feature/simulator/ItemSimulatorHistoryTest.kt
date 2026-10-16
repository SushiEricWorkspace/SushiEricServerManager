package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemSimulatorImport
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.event.Event
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class ItemSimulatorHistoryTest {
    @Test fun `実Scene履歴filterより先に拒否成功を処理しoriginalとdirtyとUndoRedoを維持する`() = fx {
        val temporary=createTempDirectory("simulator-history").toFile()
        val memory=InMemoryEditorDataStore(); var saves=0
        val store=object:EditorDataStore by memory {
            override fun <T:ManagedData<T,*>> save(descriptor:EditorDataDescriptor<T>,id:String,data:T):StoreResult<Unit> {
                saves++; return memory.save(descriptor,id,data)
            }
        }
        val service=EditorDataService(store,temporary)
        val loader=FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
        val root=loader.load<Parent>(); val main=loader.getController<MainController>()
        val stage=Stage().apply { scene=Scene(root); show() }
        val view=HistoryView(main,service)
        try {
            view.seed()
            view.current().display.displayName="two"; view.record()
            assertTrue(view.onUndo()); assertEquals("one",view.current().display.displayName)
            val original=view.original().deepCopy(); val before=view.current().deepCopy()
            var copies=0
            val button=Button("取込"); val message=Label()
            val spinner=Spinner<Int>(0,10,1).apply { isEditable=true; editor.text="2" }
            main.mainContentContainer.children.addAll(spinner,button,message)
            val capture=ItemSimulatorImport(main.mainContentContainer,button,message,{false},{copies++;SimulatorImportedItem.fromEdited(view.current())},{})
            Event.fireEvent(button,MouseEvent(MouseEvent.MOUSE_PRESSED,0.0,0.0,0.0,0.0,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,false,null))
            assertEquals(0,copies); assertTrue(message.text.contains("未確定"))
            assertEquals(before,view.current()); assertEquals(original,view.original()); assertFalse(view.unchanged()); assertEquals(0,saves)
            main.mainContentContainer.children.remove(spinner)
            button.fire()
            assertEquals(1,copies); assertEquals(before,view.current()); assertEquals(original,view.original()); assertEquals(0,saves)
            assertTrue(view.onRedo()); assertEquals("two",view.current().display.displayName)
            assertTrue(view.onUndo()); assertEquals("one",view.current().display.displayName)
            assertEquals(original,view.original()); assertEquals(0,saves)
            capture.dispose()
        } finally { stage.close(); service.itemReferences.close(); service.closeIo(); temporary.deleteRecursively() }
    }

    private class HistoryView(main:MainController,service:EditorDataService):EditorView<MutableItemBaseData>(main,service,service.items) {
        fun seed() {
            val base=MutableItemBaseData(id="item").apply { display.displayName="original" }
            originalDataMap[base.id]=base; editingDataMap[base.id]=base.deepCopy().apply { display.displayName="one" }
            selectTab(base.id)
        }
        fun current()=editingDataMap.getValue("item")
        fun original()=originalDataMap.getValue("item")
        fun unchanged()=current()==original()
        fun record()=recordHistorySnapshot("item")
        override fun setupSidebar(container:VBox,selectId:String?) = Unit
        override fun setupMainContent(selectData:MutableItemBaseData) { main.mainContentContainer.children.setAll(Label(selectData.display.displayName)) }
        override fun resolveSaveConflict(dataId:String,originalData:MutableItemBaseData,currentData:MutableItemBaseData,serverData:MutableItemBaseData)=currentData
    }
    private fun fx(action:()->Unit) {
        val ready=CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } } catch (_:IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20,TimeUnit.SECONDS)); val task=FutureTask(action); Platform.runLater(task); task.get(30,TimeUnit.SECONDS)
    }
}
