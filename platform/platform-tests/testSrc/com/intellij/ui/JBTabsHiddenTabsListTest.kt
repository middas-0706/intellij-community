// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JLabel

@TestApplication
class JBTabsHiddenTabsListTest {
  companion object {
    private val projectFixture = projectFixture()
  }

  @Test
  fun `click on the action runs it when the tab action closes the tab`(): Unit = timeoutRunBlocking {
    val result = clickActionOfFirstRow(closesWithTabAction = true, modifiers = 0)

    assertThat(result.actionModifiers).hasSize(1)
    assertThat(result.tabStays).isTrue()
  }

  @Test
  fun `Alt+click on the action runs it with the Alt key when the tab action closes the tab`(): Unit = timeoutRunBlocking {
    val result = clickActionOfFirstRow(closesWithTabAction = true, modifiers = InputEvent.ALT_DOWN_MASK)

    assertThat(result.actionModifiers).singleElement().matches { it and InputEvent.ALT_DOWN_MASK != 0 }
    assertThat(result.tabStays).isTrue()
  }

  @Test
  fun `Alt+click on the action of an editor place removes the tab when the tab action does not close the tab`(): Unit = timeoutRunBlocking {
    val result = clickActionOfFirstRow(closesWithTabAction = false, modifiers = InputEvent.ALT_DOWN_MASK)

    assertThat(result.actionModifiers).isEmpty()
    assertThat(result.tabStays).isFalse()
  }

  @Test
  fun `click on the row of a removed tab does not run the action`(): Unit = timeoutRunBlocking {
    val actionModifiers = withContext(Dispatchers.EDT) {
      withHiddenTabs(closesWithTabAction = true) { tabs, action, _ ->
        val list = HiddenTabsList(tabs)
        tabs.removeTab(list.tabAt(0))
        list.clickAction(0, 0)
        action.events.map { it.modifiersEx }
      }
    }

    assertThat(actionModifiers).isEmpty()
  }

  @Test
  fun `the list opens again once when the tab action opens it again`(): Unit = timeoutRunBlocking {
    val popupCount = withContext(Dispatchers.EDT) {
      withHiddenTabs(closesWithTabAction = true, reopenList = true) { tabs, _, popups ->
        HiddenTabsList(tabs).clickAction(0, 0)
        popups.size
      }
    }

    // the first list and the list of the tab action
    assertThat(popupCount).isEqualTo(2)
  }

  private suspend fun clickActionOfFirstRow(closesWithTabAction: Boolean, modifiers: Int): ClickResult {
    return withContext(Dispatchers.EDT) {
      withHiddenTabs(closesWithTabAction) { tabs, action, _ ->
        val list = HiddenTabsList(tabs)
        val hiddenTab = list.tabAt(0)
        list.clickAction(0, modifiers)
        ClickResult(action.events.map { it.modifiersEx }, hiddenTab in tabs.tabs)
      }
    }
  }

  /**
   * Runs [test] with 20 tabs with the place `ActionPlaces.EDITOR_TAB`, so that some of them are hidden.
   * With [closesWithTabAction], a click on the action icon runs the tab action, as `EditorTabs` does for `CloseTab`.
   * With [reopenList], the tab action closes the list and opens it again, as `CloseTab` does.
   */
  private fun <T> withHiddenTabs(
    closesWithTabAction: Boolean,
    reopenList: Boolean = false,
    test: (JBTabsImpl, RecordingAction, List<JBPopup>) -> T,
  ): T {
    return withInterceptedPopups { disposable, popups ->
      val tabs = TestTabs(projectFixture.get(), disposable, closesWithTabAction)
      val action = RecordingAction { e ->
        if (reopenList) {
          PopupUtil.getPopupContainerFor(e.inputEvent?.component)?.cancel()
          tabs.showMorePopup()
        }
      }
      repeat(20) { index ->
        tabs.addTab(TabInfo(JLabel()).setText("Tab $index").setTabLabelActions(DefaultActionGroup(action), ActionPlaces.EDITOR_TAB))
      }
      tabs.setSize(300, 30)
      tabs.doLayout()
      check(tabs.canShowMorePopup())

      test(tabs, action, popups)
    }
  }

  private class TestTabs(project: Project, disposable: Disposable, private val closesWithTabAction: Boolean) : JBTabsImpl(project, disposable) {
    override fun performTabActionInHiddenTabsList(tabInfo: TabInfo, tabAction: AnAction?, e: MouseEvent) {
      if (closesWithTabAction && tabAction != null) {
        executeTabAction(tabInfo, tabAction, e)
      }
      else {
        super.performTabActionInHiddenTabsList(tabInfo, tabAction, e)
      }
    }
  }

  private class RecordingAction(private val onPerformed: (AnActionEvent) -> Unit) : DumbAwareAction() {
    val events = mutableListOf<InputEvent>()

    override fun actionPerformed(e: AnActionEvent) {
      events.add(e.inputEvent!!)
      onPerformed(e)
    }
  }

  private data class ClickResult(val actionModifiers: List<Int>, val tabStays: Boolean)
}
