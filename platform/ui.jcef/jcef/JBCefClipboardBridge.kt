// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.text.StringUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val LOG = logger<JBCefClipboardBridge>()

private const val COPY_PREFIX = "copy:"
private const val ERROR_PREFIX = "error:"
private const val PASTE_PREFIX = "paste:"
private const val COPY_READY_PREFIX = "copy-ready:"

/**
 * Routes the clipboard traffic of an off-screen browser through the IDE process.
 *
 * - Copy and cut send text and HTML to the IDE through a [JBCefJSQuery].
 * - Paste sends text and HTML to Chromium before it runs a regular paste.
 *
 * For pages without a secure context, paste uses a synthetic event. The browser builder selects the default based on
 * the Ozone platform and desktop environment. The system property `ide.browser.jcef.linux.clipboard.bridge` can override it.
 *
 * The query takes the calls of all the frames of the browser, and [JBCefClipboardBridgeRules] checks each request.
 * A copy or a cut of the IDE gives the frame a one-time token by a script and runs the command only after the frame
 * answers that it took the token: the script and the command reach the renderer by different channels.
 * The user input comes from the [JBCefOsrComponent] of the browser, or from [JBCefBrowserBase.notifyUserInput] when the
 * owner of the browser sends the input itself and declares it with [JBCefBrowserBase.setUserInputForwarded].
 *
 * The script of a subframe sends a `navigator.clipboard` write only after Chromium completes the write, and sends none
 * for a frame with an opaque origin. The script is not a check: a frame can call the query directly.
 */
@ApiStatus.Internal
class JBCefClipboardBridge internal constructor(private val browser: JBCefBrowserBase) {
  private val query: JBCefJSQuery = JBCefJSQuery.create(browser, JBCefJSQuery.FramePolicy.anyFrame())
  private val copyScript: String = buildCopyScript()
  @Volatile
  private var rules = JBCefClipboardBridgeRules()
  private val forwardedInputFilter = JBCefUserInputFilter()
  // Whether the owner of the browser declared that it passes the input that it sends to the browser.
  @Volatile
  private var isUserInputForwarded = false

  @get:TestOnly
  val queryFuncName: String
    get() = query.funcName

  init {
    query.addHandler { _, frame, request -> handleQuery(frame, request) }
    (browser.cefBrowser.uiComponent as? JBCefOsrComponent)?.setUserInputListener { rules.userInput() }
    browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
      override fun onLoadStart(cefBrowser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
        installCopyScript(frame)
      }

      override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
        installCopyScript(frame)
      }
    }, browser.cefBrowser)
  }

  /**
   * Pastes the system clipboard content, as the IDE sees it, into the focused frame of the browser.
   * Without text or HTML in the clipboard, Chromium pastes from its own clipboard.
   */
  fun paste() {
    val frame = browser.cefBrowser.focusedFrame ?: return
    val clipboard = CopyPasteManager.getInstance()
    val text = clipboard.getContents<String>(DataFlavor.stringFlavor).orEmpty()
    val html = clipboard.getContents<String>(DataFlavor.allHtmlFlavor).orEmpty()
    if (text.isEmpty() && html.isEmpty()) {
      frame.paste()
    }
    else {
      val token = rules.startPaste(frame.identifier)
      frame.executeJavaScript(buildPasteScript(text, html, token), frame.url, 0)
    }
  }

  /** Records that the owner of the browser passes the input that it sends, see [JBCefBrowserBase.setUserInputForwarded]. */
  fun setUserInputForwarded() {
    isUserInputForwarded = true
  }

  /** Takes an input event that the owner of the browser sends to the browser itself, see [JBCefBrowserBase.notifyUserInput]. */
  fun onUserInput(event: InputEvent) {
    if (forwardedInputFilter.isUserInput(event)) rules.userInput()
  }

  /** Forgets the keys that are down, see [JBCefBrowserBase.notifyUserInputFocusLost]. */
  fun onUserInputFocusLost() {
    forwardedInputFilter.reset()
  }

  /** Replaces the clock of the rules of this bridge, for a test that moves the time. */
  @TestOnly
  fun setClockInTests(nanoTime: () -> Long) {
    rules = JBCefClipboardBridgeRules(nanoTime)
  }

  private fun installCopyScript(frame: CefFrame?) {
    if (frame != null && frame.isValid) frame.executeJavaScript(copyScript, frame.url, 0)
  }

  /** Copies the current frame selection through the script bridge. */
  fun copy(frame: CefFrame) {
    if (frame.isValid) startCopy(frame, JBCefClipboardBridgeRules.Command.COPY)
  }

  /** Cuts the current frame selection through the script bridge. */
  fun cut(frame: CefFrame) {
    if (frame.isValid) startCopy(frame, JBCefClipboardBridgeRules.Command.CUT)
  }

  // The copy of the IDE carries a token in every frame, so that it needs neither the focus nor a recent input.
  // The script and the command reach the renderer by different channels, so the frame first takes the token and
  // answers, and only then the bridge runs the command, whose copy event finds the token.
  private fun startCopy(frame: CefFrame, command: JBCefClipboardBridgeRules.Command) {
    if (rules.isCopyWaitingForAnswer()) {
      LOG.debug("JCEF clipboard bridge: a new copy replaces a copy whose frame did not answer")
    }
    val token = rules.startCopy(frame.identifier, command)
    frame.executeJavaScript(copyScript + "\nwindow.__jbCefClipboardBridge.takeCopyToken(${jsString(token)});", frame.url, 0)
  }

  // The frame took the token of the copy that the IDE started in it: run the command now. In the out-of-process mode
  // the frame object of the call does not run a command, so the handler takes the frame from the browser by its
  // identifier, as a paste takes the focused frame.
  private fun onCopyReady(frame: CefFrame, token: String): Boolean {
    val command = rules.takeCopyReady(frame.identifier, token)
    if (command == null) {
      LOG.debug("JCEF clipboard bridge: refused the answer of a frame to a copy that it did not start or that expired")
      return false
    }
    val target = browser.cefBrowser.getFrameByIdentifier(frame.identifier) ?: return false
    if (command == JBCefClipboardBridgeRules.Command.CUT) target.cut() else target.copy()
    return true
  }

  private fun handleQuery(frame: CefFrame, request: String): JBCefJSQuery.Response? {
    try {
      when {
        request.startsWith(COPY_PREFIX) -> {
          // The request is "copy:<token>:<payload>". The main frame sends no token.
          val tokenAndPayload = request.removePrefix(COPY_PREFIX)
          val separator = tokenAndPayload.indexOf(':')
          if (separator < 0) return REFUSED
          if (!rules.acceptsCopy(snapshot(frame), token = tokenAndPayload.substring(0, separator))) return REFUSED
          onCopied(tokenAndPayload.substring(separator + 1))
        }
        request.startsWith(COPY_READY_PREFIX) -> {
          if (!onCopyReady(frame, token = request.removePrefix(COPY_READY_PREFIX))) return REFUSED
        }
        request.startsWith(PASTE_PREFIX) -> {
          if (!rules.acceptsPaste(frame.identifier, token = request.removePrefix(PASTE_PREFIX))) return REFUSED
          // The page wrote the data into the clipboard of Chromium. A regular paste command reads it back.
          val focusedFrame = browser.cefBrowser.focusedFrame
          if (focusedFrame != null && focusedFrame.identifier == frame.identifier) focusedFrame.paste()
        }
        request.startsWith(ERROR_PREFIX) -> {
          val tokenAndMessage = request.removePrefix(ERROR_PREFIX)
          val separator = tokenAndMessage.indexOf(':')
          if (separator < 0 || !rules.acceptsPaste(frame.identifier, token = tokenAndMessage.substring(0, separator))) return REFUSED
          val message = URLDecoder.decode(tokenAndMessage.substring(separator + 1), StandardCharsets.UTF_8)
          LOG.warn("JCEF clipboard bridge: clipboard write failed: ${message.take(512)}")
        }
        else -> return REFUSED
      }
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      LOG.warn("JCEF clipboard bridge: failed to handle a request", e)
    }
    return null
  }

  // In the out-of-process mode, the frame is valid only during the call, so read it here.
  private fun snapshot(frame: CefFrame): JBCefClipboardBridgeRules.Frame {
    val cefBrowser = browser.cefBrowser
    return JBCefClipboardBridgeRules.Frame(
      frameId = frame.identifier,
      isMain = frame.isMain,
      url = frame.url,
      mainFrameUrl = if (frame.isMain) frame.url else cefBrowser.mainFrame?.url,
      focusedFrameId = cefBrowser.focusedFrame?.identifier,
      browserHasFocus = JBCefClipboardBridgeRules.isFocusIn(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
                                                            browser.component, cefBrowser.uiComponent),
      // The user types into the component of a browser; a browser without one, such as a JBCefOsrHandlerBrowser, gets
      // the input from its owner, when the owner declares it.
      hasInputSource = browser.component != null || isUserInputForwarded,
    )
  }

  // The payload is "<text>\n<html>", both parts encoded with encodeURIComponent.
  private fun onCopied(payload: String) {
    val separator = payload.indexOf('\n')
    if (separator < 0) return
    val text = URLDecoder.decode(payload.substring(0, separator), StandardCharsets.UTF_8)
    val html = URLDecoder.decode(payload.substring(separator + 1), StandardCharsets.UTF_8)
    if (text.isEmpty() && html.isEmpty()) return

    ApplicationManager.getApplication().invokeLater({
      CopyPasteManager.getInstance().setContents(TextAndHtmlTransferable(text, html))
    }, ModalityState.any())
  }

  @Language("JavaScript")
  private fun buildCopyScript(): String {
    val send = query.inject("'$COPY_PREFIX' + token + ':' + encodeURIComponent(text) + '\\n' + encodeURIComponent(html)")
    // A refused answer leaves no token behind for the next copy of the page.
    val copyReady = query.inject("'$COPY_READY_PREFIX' + token", "function(response) {}",
                                 "function(code, message) { if (bridge.copyToken === token) bridge.copyToken = null; }")
    return """
      (function() {
        if (window.__jbCefClipboardBridge) return;
        const bridge = window.__jbCefClipboardBridge = {};

        // The IDE runs its copy or cut after this answer, so that the copy event finds the token.
        bridge.takeCopyToken = function(token) {
          bridge.copyToken = token;
          $copyReady
        };

        function send(text, html) {
          text = text == null ? '' : String(text);
          html = html == null ? '' : String(html);
          // The token of the copy that the IDE started in this frame, for one request, also for an empty one.
          const token = bridge.copyToken || '';
          bridge.copyToken = null;
          if (text === '' && html === '') return;
          $send
        }

        // A page that handles the copy itself puts its data here from a 'copy' or 'cut' listener.
        let captured = null;
        const setData = DataTransfer.prototype.setData;
        DataTransfer.prototype.setData = function(type, data) {
          if (captured) captured[String(type).toLowerCase()] = String(data);
          return setData.apply(this, arguments);
        };

        function selectionText() {
          const el = document.activeElement;
          if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') && typeof el.selectionStart === 'number') {
            return el.value.substring(el.selectionStart, el.selectionEnd);
          }
          const sel = document.getSelection();
          return sel ? sel.toString() : '';
        }

        function selectionHtml() {
          const sel = document.getSelection();
          if (!sel || sel.isCollapsed) return '';
          const div = document.createElement('div');
          for (let i = 0; i < sel.rangeCount; i++) div.appendChild(sel.getRangeAt(i).cloneContents());
          return div.innerHTML;
        }

        function onCopyOrCut() {
          // Take the selection now: 'cut' removes it before the event dispatch finishes.
          const text = selectionText(), html = selectionHtml();
          captured = {};
          setTimeout(function() {
            const data = captured;
            captured = null;
            if ('text/plain' in data || 'text/html' in data) send(data['text/plain'], data['text/html']);
            else send(text, html);
          }, 0);
        }
        document.addEventListener('copy', onCopyOrCut, true);
        document.addEventListener('cut', onCopyOrCut, true);

        // JS-initiated copies, e.g. a "copy code" button. The paste script uses the original write().
        // A subframe sends a write only after Chromium completes it, and a subframe with an opaque origin sends none.
        const clipboard = navigator.clipboard;
        const isSubframe = window !== window.top;
        if (clipboard) {
          const writeText = clipboard.writeText.bind(clipboard);
          bridge.write = clipboard.write.bind(clipboard);
          const sendAfter = function(result, data) {
            if (!isSubframe) {
              data().then(function(d) { send(d.text, d.html); }, function() {});
            }
            else if (String(window.origin) !== 'null') {
              result.then(function() { return data(); }).then(function(d) { send(d.text, d.html); }, function() {});
            }
            return result;
          };
          clipboard.writeText = function(text) {
            return sendAfter(writeText(text), function() { return Promise.resolve({text: text, html: ''}); });
          };
          clipboard.write = function(items) {
            return sendAfter(bridge.write(items), async function() {
              let text = '', html = '';
              for (const item of items) {
                if (item.types.includes('text/plain')) text = await (await item.getType('text/plain')).text();
                if (item.types.includes('text/html')) html = await (await item.getType('text/html')).text();
              }
              return {text: text, html: html};
            });
          };
        }
      })();
      """.trimIndent()
  }

  @Language("JavaScript")
  private fun buildPasteScript(text: String, html: String, token: String): String {
    val notifyWritten = query.inject("'$PASTE_PREFIX' + token")
    val notifyWriteFailed = query.inject("'$ERROR_PREFIX' + token + ':' + encodeURIComponent(String(error))")
    return """
      (function() {
        const text = ${jsString(text)};
        const html = ${jsString(html)};
        const token = ${jsString(token)};

        // The page gets a synthetic paste event. When the page does not handle it, the text goes in through insertText.
        function pasteSynthetic() {
          const data = new DataTransfer();
          data.setData('text/plain', text);
          if (html) data.setData('text/html', html);
          const target = document.activeElement || document.body;
          if (target.dispatchEvent(new ClipboardEvent('paste', {clipboardData: data, bubbles: true, cancelable: true}))) {
            document.execCommand('insertText', false, text);
          }
        }

        const bridge = window.__jbCefClipboardBridge;
        const write = bridge && bridge.write ? bridge.write : navigator.clipboard && navigator.clipboard.write.bind(navigator.clipboard);
        if (!write || !document.hasFocus()) {
          pasteSynthetic();
          return;
        }
        const items = {'text/plain': new Blob([text], {type: 'text/plain'})};
        if (html) items['text/html'] = new Blob([html], {type: 'text/html'});
        // The data is in the clipboard of Chromium now. The IDE runs a regular paste, so the page gets a real paste event.
        write([new ClipboardItem(items)]).then(function() { $notifyWritten }, function(error) {
          $notifyWriteFailed;
          pasteSynthetic();
        });
      })();
      """.trimIndent()
  }
}

private val REFUSED = JBCefJSQuery.Response(null, JBCefJSQuery.ERR_CODE_FRAME_REFUSED, JBCefJSQuery.ERR_MSG_FRAME_REFUSED)

// A double-quoted JS string literal.
private fun jsString(s: String): String = "\"" + StringUtil.escapeStringCharacters(s) + "\""

private class TextAndHtmlTransferable(private val text: String, private val html: String) : Transferable {
  private val flavors: Array<DataFlavor> =
    if (html.isEmpty()) arrayOf(DataFlavor.stringFlavor)
    else arrayOf(DataFlavor.allHtmlFlavor, DataFlavor.stringFlavor)

  override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors

  override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
    DataFlavor.stringFlavor -> text
    in flavors -> html
    else -> throw UnsupportedFlavorException(flavor)
  }
}
