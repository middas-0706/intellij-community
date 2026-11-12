// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mermaid.markdown.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.components.BorderLayoutPanel
import org.intellij.plugins.markdown.ui.preview.accessor.MarkdownLinkOpener
import java.net.URI

class MermaidDiagramPreviewComponent(private val project: Project): BorderLayoutPanel(), Disposable {
  val browser: JBCefBrowser by disposableHolder(createBrowser())
  private val openLinkQueryLock = Any()
  // The origin of the page and the query that accepts only the main frame on that origin.
  private var openLinkQuery: Pair<String, JBCefJSQuery>? = null
  private var isDisposed = false

  init {
    Disposer.register(this, browser)
    addToCenter(browser.component)
  }

  suspend fun load() {
    val url = MermaidPreviewStaticServer.obtainStaticIndexUrl()
    // A preview that is disposed, for example while it waits for the server, loads nothing.
    val query = openLinkQuery(url) ?: return
    with(browser) {
      waitForPageLoad(url)
      injectLinkOpener(query)
    }
  }

  private fun openLinkQuery(url: String): JBCefJSQuery? = synchronized(openLinkQueryLock) {
    if (isDisposed) return null
    val origin = URI(url).let { "${it.scheme}://${it.rawAuthority}" }
    val current = openLinkQuery
    // A query that is disposed, for example by Disposer while it disposes the preview, serves nothing.
    if (current != null && current.first == origin) return current.second.takeUnless { it.isDisposed }
    current?.second?.let(Disposer::dispose)
    val query = JBCefJSQuery.create(browser, JBCefJSQuery.FramePolicy.mainFrameOf(origin))
    // Disposer marks the preview as disposed before dispose() sets the flag, so the preview can be disposed already.
    if (!Disposer.tryRegister(this, query)) {
      Disposer.dispose(query)
      return null
    }
    query.addVoidHandler { link ->
      link?.let(::openLink)
    }
    openLinkQuery = origin to query
    query
  }

  internal class PreviewUpdateException(cause: Exception): IllegalStateException(cause)

  suspend fun update(text: String) {
    val content = PreviewEncodingUtil.encodeContent(text)
    // language=JavaScript
    val code = """
    (function() {
      return new Promise(resolve => {
        window["updateMermaidDiagramContent"]("$content");
        resolve();
      });
    })();
    """.trimIndent()
    try {
      browser.executeCancellableJavaScript(code)
    } catch (exception: JsCallExecutionException) {
      throw PreviewUpdateException(exception)
    }
  }

  override fun dispose() {
    synchronized(openLinkQueryLock) {
      isDisposed = true
      openLinkQuery = null
    }
    removeAll()
  }

  private suspend fun injectLinkOpener(openLinkQuery: JBCefJSQuery) {
    // language=JavaScript
    val code = """
    (function() {
      return new Promise(resolve => {
        window["openLink"] = function(link) {
          window.${openLinkQuery.funcName}({
            request: "" + link,
            onSuccess: (response) => {}, 
            onFailure: (errorCode, errorMessage) => {}
          });
        };
        resolve();
      });
    })();
    """.trimIndent()
    browser.executeCancellableJavaScript(code)
  }

  private fun openLink(link: String) {
    @Suppress("UnstableApiUsage")
    MarkdownLinkOpener.getInstance().openLink(project, link)
  }
}