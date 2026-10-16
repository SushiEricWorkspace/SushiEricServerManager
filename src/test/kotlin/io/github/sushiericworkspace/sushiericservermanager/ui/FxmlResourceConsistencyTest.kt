package io.github.sushiericworkspace.sushiericservermanager.ui

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.FileNotFoundException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FxmlResourceConsistencyTest {
    @Test
    fun `FXMLのControllerとイベント参照が存在する`() {
        AppScreen.entries.filter { it.fxml != null }.forEach { screen ->
            val resource = assertNotNull(javaClass.getResource(screen.fxml!!), screen.fxml)
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(resource.openStream())
            val root = document.documentElement
            val controllerName = root.getAttributeNS(FXML_NAMESPACE, "controller")
            assertTrue(controllerName.isNotBlank(), "${screen.name}にfx:controllerがありません")
            val controller = Class.forName(controllerName)
            walk(root) { element ->
                element.attributes?.let { attributes ->
                    for (index in 0 until attributes.length) {
                        val attribute = attributes.item(index)
                        if (attribute.nodeValue.startsWith("#")) {
                            val methodName = attribute.nodeValue.removePrefix("#")
                            assertTrue(
                                controller.declaredMethods.any { it.name == methodName },
                                "$controllerName#$methodName が見つかりません"
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `画面CSSとimport先が存在する`() {
        AppScreen.entries.forEach { screen ->
            verifyCss(screen.css, mutableSetOf())
        }
    }

    @Test
    fun `FXMLで指定した独自スタイルクラスが画面CSSから参照できる`() {
        AppScreen.entries.filter { it.fxml != null }.forEach { screen ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(assertNotNull(javaClass.getResource(screen.fxml!!)).openStream())
            val styleClasses = linkedSetOf<String>()
            walk(document.documentElement) { element ->
                element.getAttribute("styleClass")
                    .split(',')
                    .map(String::trim)
                    .filterTo(styleClasses) { it.isNotBlank() && it !in BUILT_IN_STYLE_CLASSES }
            }
            val css = collectCss(screen.css, mutableSetOf())
            styleClasses.forEach { styleClass ->
                assertTrue(
                    Regex("""(?<![\w-])\.${Regex.escape(styleClass)}(?![\w-])""")
                        .containsMatchIn(css),
                    "${screen.name}のstyleClass '$styleClass' がCSSに定義されていません"
                )
            }
        }
    }

    private fun verifyCss(path: String, visited: MutableSet<String>) {
        if (!visited.add(path)) return
        val resource = javaClass.getResource(path)
            ?: throw FileNotFoundException(path)
        val text = resource.readText()
        IMPORT_REGEX.findAll(text).forEach { match ->
            val imported = match.groupValues[1]
            val resolved = if (imported.startsWith('/')) {
                imported
            } else {
                path.substringBeforeLast('/') + "/" + imported
            }
            verifyCss(resolved, visited)
        }
    }

    private fun collectCss(path: String, visited: MutableSet<String>): String {
        if (!visited.add(path)) return ""
        val text = assertNotNull(javaClass.getResource(path), path).readText()
        val imports = IMPORT_REGEX.findAll(text).joinToString("\n") { match ->
            val imported = match.groupValues[1]
            val resolved = if (imported.startsWith('/')) {
                imported
            } else {
                path.substringBeforeLast('/') + "/" + imported
            }
            collectCss(resolved, visited)
        }
        return "$text\n$imports"
    }

    private fun walk(node: Node, action: (Element) -> Unit) {
        if (node is Element) action(node)
        val children = node.childNodes
        for (index in 0 until children.length) {
            walk(children.item(index), action)
        }
    }

    companion object {
        private const val FXML_NAMESPACE = "http://javafx.com/fxml/1"
        private val IMPORT_REGEX = Regex("""@import\s+["']([^"']+)["']""")
        private val BUILT_IN_STYLE_CLASSES = setOf("button")
    }
}
