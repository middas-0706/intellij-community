// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.util.ui.UIUtil
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Runs [block] with a disposable and the list of the popups that open in [block].
 * The popups do not show. After [block], the function closes the popups and disposes the disposable.
 * Call it on the EDT.
 */
internal fun <T> withInterceptedPopups(block: (disposable: Disposable, popups: List<JBPopup>) -> T): T {
  val disposable = Disposer.newDisposable()
  // the click handler opens a new list after the click, and an open popup keeps the project
  val popups = ArrayList<JBPopup>()
  UiInterceptors.registerPersistent(disposable, object : UiInterceptors.PersistentUiInterceptor<JBPopup>(JBPopup::class.java) {
    override fun shouldIntercept(component: JBPopup): Boolean = true

    override fun doIntercept(component: JBPopup, owner: RelativePoint?) {
      popups.add(component)
    }
  })
  try {
    return block(disposable, popups)
  }
  finally {
    popups.forEach { it.cancel() }
    Disposer.dispose(disposable)
  }
}

/**
 * The hidden tabs list of [tabs]. The constructor opens the list.
 */
internal class HiddenTabsList(tabs: JBTabsImpl) {
  private val popup: JBPopup = requireNotNull(tabs.showMorePopup()) { "the tabs have no hidden tab" }

  @Suppress("UNCHECKED_CAST")
  private val list = UIUtil.findComponentOfType(popup.content, JList::class.java) as JList<TabInfo>

  init {
    list.size = list.preferredSize
  }

  fun tabAt(row: Int): TabInfo = list.model.getElementAt(row)

  /**
   * Sends a mouse release with [modifiers] to the action icon of [row].
   */
  fun clickAction(row: Int, modifiers: Int) {
    val cellBounds = list.getCellBounds(row, row)
    val y = cellBounds.y + cellBounds.height / 2
    val x = (cellBounds.x + cellBounds.width - 1 downTo cellBounds.x).first { x ->
      (ListUtil.getDeepestRendererChildComponentAt(list, Point(x, y)) as? JLabel)?.icon != null
    }
    val onScreen = Point(x, y)
    SwingUtilities.convertPointToScreen(onScreen, list)

    // `ListPopupImpl.MyList.processMouseEvent` reads the menu shortcut key mask, which a headless toolkit does not have
    val event = MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, x, y, onScreen.x, onScreen.y, 1, false, MouseEvent.BUTTON1)
    list.mouseListeners.forEach { it.mouseReleased(event) }
  }
}
