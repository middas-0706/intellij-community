// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef;

import org.jetbrains.annotations.NotNull;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which input events are a user input for the clipboard bridge: a mouse press, or a press of a key that does
 * not repeat and is not a modifier or Escape, which give no activation in Chromium.
 */
final class JBCefUserInputFilter {
  private final @NotNull Set<Integer> myPressedKeys = new HashSet<>();

  synchronized boolean isUserInput(@NotNull InputEvent event) {
    if (event instanceof MouseEvent) {
      if (event.getID() != MouseEvent.MOUSE_PRESSED) return false;
      // The release of a key that is down may have gone to another component, so a mouse press forgets the keys.
      myPressedKeys.clear();
      return true;
    }
    if (!(event instanceof KeyEvent keyEvent)) return false;
    int keyCode = keyEvent.getKeyCode();
    if (event.getID() == KeyEvent.KEY_PRESSED) {
      // A key that the user holds down sends more presses before its release.
      return myPressedKeys.add(keyCode) && !isNonActivatingKey(keyCode);
    }
    if (event.getID() == KeyEvent.KEY_RELEASED) myPressedKeys.remove(keyCode);
    return false;
  }

  /** Forgets the keys that are down, for example when their releases go to another component. */
  synchronized void reset() {
    myPressedKeys.clear();
  }

  private static boolean isNonActivatingKey(int keyCode) {
    return switch (keyCode) {
      case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META, KeyEvent.VK_WINDOWS,
           KeyEvent.VK_CONTEXT_MENU, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK,
           // Escape gives no activation in Chromium, and the IDE moves the focus out of a view with it.
           KeyEvent.VK_ESCAPE -> true;
      default -> false;
    };
  }
}
