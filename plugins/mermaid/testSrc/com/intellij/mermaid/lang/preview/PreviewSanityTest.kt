package com.intellij.mermaid.lang.preview

import com.intellij.mermaid.markdown.preview.MermaidDiagramPreviewComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.ui.jcef.JBCefApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@PreviewTest
@WithJcef
@ExtendWith(WaitForBuiltInServerExtension::class)
@TestApplication
class PreviewSanityTest {
  @JvmField
  @RegisterExtension
  val projectModel = ProjectModelExtension()

  @TestDisposable
  lateinit var disposable: Disposable

  private val project: Project
    get() = projectModel.project

  // An environment without JCEF, such as a CI agent, skips the tests instead of failing them.
  @BeforeEach
  fun assumeJcef() {
    assumeTrue(JBCefApp.isSupported(), "JCEF is not supported in this environment")
  }

  @Test
  fun `preview can actually load and dispose`() {
    runBlocking(Dispatchers.EDT) {
      val preview = MermaidDiagramPreviewComponent(project)
      Disposer.register(disposable, preview)
      preview.load()
      preview.update("")
    }
  }

  @Test
  fun `a preview that is disposed loads nothing`() {
    runBlocking(Dispatchers.EDT) {
      val preview = MermaidDiagramPreviewComponent(project)
      Disposer.dispose(preview)
      preview.load()
    }
  }

  @Test
  fun `a preview that loads while Disposer disposes it loads nothing`() {
    runBlocking(Dispatchers.EDT) {
      val preview = MermaidDiagramPreviewComponent(project)
      val loads = countLoads(preview)
      // Disposer disposes the children of the preview, in reverse order, before it calls dispose() of the preview.
      Disposer.register(preview, Disposable { loadNow(preview) })
      Disposer.dispose(preview)
      // A load waits for the end of the page load, so a load that loaded something counted it.
      assertEquals(0, loads.get())
    }
  }

  @Test
  fun `a preview that loads again while Disposer disposes it loads nothing`() {
    runBlocking(Dispatchers.EDT) {
      val preview = MermaidDiagramPreviewComponent(project)
      // Registered before the query of the first load, so Disposer disposes the query first.
      Disposer.register(preview, Disposable { loadNow(preview) })
      preview.load()
      val loads = countLoads(preview)
      Disposer.dispose(preview)
      assertEquals(0, loads.get())
    }
  }

  // Counts the page loads that start in the browser of the preview.
  private fun countLoads(preview: MermaidDiagramPreviewComponent): AtomicInteger {
    val loads = AtomicInteger()
    preview.browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
      override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
        loads.incrementAndGet()
      }
    }, preview.browser.cefBrowser)
    return loads
  }

  // Loads the preview from a child that Disposer disposes, as the editor loads it on another thread.
  private fun loadNow(preview: MermaidDiagramPreviewComponent) {
    runBlocking(Dispatchers.Default) {
      withTimeout(30.seconds) { preview.load() }
    }
  }

  @Test
  fun `preview can load minimal flowchart`() {
    // language=Mermaid
    val diagramContent = """
    ---
    title: Node
    ---
    flowchart LR
        id
    """.trimIndent()
    runBlocking(Dispatchers.EDT) {
      val preview = MermaidDiagramPreviewComponent(project)
      Disposer.register(disposable, preview)
      preview.load()
      preview.update(diagramContent)
    }
  }
}
