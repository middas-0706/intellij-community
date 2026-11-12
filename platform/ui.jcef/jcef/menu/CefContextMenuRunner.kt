// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef.menu

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.jcef.JBCefClipboardBridge
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.callback.CefRunContextMenuCallback
import org.cef.handler.CefContextMenuHandlerAdapter
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Point
import javax.swing.SwingUtilities

internal class CefContextMenuRunner(private val clipboardBridge: JBCefClipboardBridge?) : CefContextMenuHandlerAdapter() {
  override fun runContextMenu(browser: CefBrowser, frame: CefFrame, params: CefContextMenuParams, model: CefMenuModel, callback: CefRunContextMenuCallback): Boolean {
    if (model.count <= 0) {
      closePopup()
      return true
    }

    if (isJcefContextMenuActionGroupRegistered()) {
      thisLogger().warn("Native context menu actions are overridden, skip native menu popup")
      return true
    }
    // In the out-of-process mode, the frame is valid only during this call.
    val frameId = frame.identifier
    val menuAdapter = JBCefMenuAdapter(model) { selectedItem ->
      CefContextMenuCommands.run(browser, clipboardBridge, frameId, selectedItem?.commandId, callback)
    }

    val popupCoordinates = Point(params.xCoord, params.yCoord)

    SwingUtilities.invokeLater {
      myCurrentPopup = JBPopupFactory.getInstance().createListPopup(menuAdapter).apply {
        setRequestFocus(false)
        show(RelativePoint(browser.uiComponent, popupCoordinates))
      }
    }

    return true
  }

  override fun onContextMenuDismissed(browser: CefBrowser?, frame: CefFrame?) {
    closePopup()
  }

  private fun closePopup() {
    SwingUtilities.invokeLater {
      mySelectionCallback?.cancel()
      mySelectionCallback = null

      myCurrentPopup?.cancel()
      myCurrentPopup = null
    }
  }

  fun isShowing(): Boolean = myCurrentPopup != null

  private var myCurrentPopup: ListPopup? = null
  private var mySelectionCallback: CefRunContextMenuCallback? = null
}

/**
 * Runs the commands that the user selects in the native context menu.
 */
@ApiStatus.Internal
object CefContextMenuCommands {
  /**
   * Runs the command that the user selected in the native context menu of the frame, or cancels the menu for `null`.
   * With the clipboard bridge, the bridge runs Copy and Cut, as it runs those of the shortcuts, so that the request of
   * the frame carries the token of the IDE.
   */
  @VisibleForTesting
  fun run(browser: CefBrowser, clipboardBridge: JBCefClipboardBridge?, frameId: String, commandId: Int?, callback: CefRunContextMenuCallback) {
    if (commandId == null) {
      callback.cancel()
      return
    }
    if (clipboardBridge != null && (commandId == CefMenuModel.MenuId.MENU_ID_COPY || commandId == CefMenuModel.MenuId.MENU_ID_CUT)) {
      callback.cancel()
      val frame = browser.getFrameByIdentifier(frameId) ?: return
      if (commandId == CefMenuModel.MenuId.MENU_ID_COPY) clipboardBridge.copy(frame) else clipboardBridge.cut(frame)
      return
    }
    callback.Continue(commandId, 0)
  }
}
