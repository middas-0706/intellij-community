// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.impl.tabActions.ALWAYS_SHOW_MODIFIED_MARKER
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.fileEditorManagerFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.HiddenTabsList
import com.intellij.ui.withInterceptedPopups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Dimension
import java.awt.event.InputEvent

/**
 * Tests a click on the close button of an editor tab in the hidden tabs list. The tab action is the real `CloseTab`.
 */
@TestApplication
internal class EditorTabsHiddenTabsListTest {
  companion object {
    private val projectFixture = projectFixture(
      openProjectTask = OpenProjectTask {
        beforeInitTasks += { it.putUserData(FileEditorManagerKeys.ALLOW_IN_LIGHT_PROJECT, true) }
      },
      openAfterCreation = true,
    )
  }

  private val managerFixture = projectFixture.fileEditorManagerFixture()

  @Test
  fun `click on the close button of a hidden tab closes the file`(): Unit = timeoutRunBlocking {
    val result = withContext(Dispatchers.UiWithModelAccess) {
      val tabs = openTabs()
      withInterceptedPopups { _, _ ->
        val list = HiddenTabsList(tabs)
        val clickedFile = list.tabAt(0).`object` as VirtualFile
        val composite = requireNotNull(managerFixture.get().currentWindow?.getComposite(clickedFile))
        list.clickAction(0, 0)
        CloseResult(clickedFile, managerFixture.get().openFiles.toList(), composite.isDisposed())
      }
    }

    assertThat(result.openFiles).hasSize(TAB_COUNT - 1).doesNotContain(result.clickedFile)
    assertThat(result.compositeDisposed).isTrue()
  }

  @Test
  fun `Alt+click on the close button of a hidden tab closes the other tabs`(): Unit = timeoutRunBlocking {
    val (clickedFile, openFiles) = withContext(Dispatchers.UiWithModelAccess) {
      val tabs = openTabs()
      withInterceptedPopups { _, _ ->
        val list = HiddenTabsList(tabs)
        val clickedFile = list.tabAt(0).`object` as VirtualFile
        list.clickAction(0, InputEvent.ALT_DOWN_MASK)
        clickedFile to managerFixture.get().openFiles.toList()
      }
    }

    assertThat(openFiles).containsExactly(clickedFile)
  }

  @ParameterizedTest(name = "modifiers = {0}")
  @ValueSource(ints = [0, InputEvent.ALT_DOWN_MASK])
  fun `click on the modified dot of a hidden tab closes no tab when the close button is off`(modifiers: Int): Unit = timeoutRunBlocking {
    val openFiles = withContext(Dispatchers.UiWithModelAccess) {
      val settings = UISettings.getInstance().state
      val showCloseButton = settings.showCloseButton
      settings.showCloseButton = false
      try {
        val tabs = openTabs()
        withInterceptedPopups { _, _ ->
          val list = HiddenTabsList(tabs)
          // the tab shows the modified dot instead of the close button
          (list.tabAt(0).`object` as VirtualFile).putUserData(ALWAYS_SHOW_MODIFIED_MARKER, true)
          list.clickAction(0, modifiers)
          managerFixture.get().openFiles.toList()
        }
      }
      finally {
        settings.showCloseButton = showCloseButton
      }
    }

    assertThat(openFiles).hasSize(TAB_COUNT)
  }

  /**
   * Opens [TAB_COUNT] files in one window and makes the tab row narrow.
   * Returns the tabs of the window. The last tabs are hidden.
   */
  private fun openTabs(): EditorTabs {
    val manager = managerFixture.get()
    val files = (0 until TAB_COUNT).map { LightVirtualFile("file$it.txt") }
    for (file in files) {
      manager.openFile(file, true)
    }
    val window = requireNotNull(manager.currentWindow)
    // the tab row scrolls to the selected tab, so the selection of the first tab hides the last tabs
    manager.openFile(files.first(), true)
    val tabs = window.tabbedPane.editorTabs
    tabs.size = Dimension(300, 30)
    tabs.doLayout()
    check(tabs.canShowMorePopup())
    return tabs
  }

  private data class CloseResult(val clickedFile: VirtualFile, val openFiles: List<VirtualFile>, val compositeDisposed: Boolean)
}

private const val TAB_COUNT = 20
