package io.github.sushiericworkspace.sushiericservermanager.editor.view

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EditorBaseLayoutTest {

    @Test
    fun `ウィンドウ拡張分をサイドバーへ割り当てて編集領域幅を維持する`() {
        val resource = assertNotNull(javaClass.getResource("/fxml/main/base.fxml"))
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(resource.openStream())
        val root = document.documentElement
        val sidebar = document.getElementsByTagName("ScrollPane")
            .asSequence()
            .filterIsInstance<Element>()
            .first { it.getAttributeNS(FXML_NAMESPACE, "id") == "sidebarScroll" }

        val windowWidth = root.getAttribute("prefWidth").toDouble()
        val sidebarWidth = sidebar.getAttribute("prefWidth").toDouble()

        assertEquals(1560.0, windowWidth)
        assertEquals(410.0, sidebar.getAttribute("minWidth").toDouble())
        assertEquals(450.0, sidebarWidth)
        assertEquals(1110.0, windowWidth - sidebarWidth)
    }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    companion object {
        private const val FXML_NAMESPACE = "http://javafx.com/fxml/1"
    }
}
