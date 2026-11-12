// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Component
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private val PERMIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
// The time after a user input in which the main frame can copy, like the transient activation of Chromium.
private val MAIN_FRAME_INPUT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5)
private val SUBFRAME_INPUT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1)
private const val NO_USER_INPUT = Long.MIN_VALUE

/**
 * Decides which requests of the frames of a browser the [JBCefClipboardBridge] accepts.
 *
 * - A copy with the one-time token of a copy or a cut that the IDE runs in a frame: once, within 5 seconds.
 * - A copy of the main frame without a token: when the browser has the focus, or within 5 seconds after a user input.
 *   A browser without a source of user input, which has no component and whose owner does not declare that it passes
 *   the input, takes it at any time.
 * - A copy of a subframe without a token, such as a `navigator.clipboard` write: once for each user input, within a
 *   second after it, when the subframe has the focus and the origin of its URL is the origin of the URL of the main
 *   frame. Such a subframe may also have taken the focus by a script after an input in the main frame: the bridge
 *   accepts its copy, because an ordinary subframe of the origin of the main frame can run scripts in the main frame
 *   anyway.
 * - A paste and a paste error with the one-time token of the paste script that the IDE runs in a frame: once,
 *   within 5 seconds.
 *
 * A user input is a mouse press, or a press of a key that is not a modifier or Escape and does not repeat, that the
 * component of the browser or its owner passes to the browser.
 *
 * The rules compare URLs, so a sandboxed frame served from the origin of the main frame counts as that origin.
 *
 * @param nanoTime the clock, like [System.nanoTime]
 */
@ApiStatus.Internal
@VisibleForTesting
class JBCefClipboardBridgeRules(private val nanoTime: () -> Long = System::nanoTime) {
  private val copyPermit = AtomicReference<Permit?>()
  private val pastePermit = AtomicReference<Permit?>()
  private val userInputTime = AtomicLong(NO_USER_INPUT)
  // The user input that a copy of a subframe used.
  private val usedUserInputTime = AtomicLong(NO_USER_INPUT)

  /**
   * The state of a frame and its browser when the bridge gets a request.
   *
   * @param frameId the identifier of the frame
   * @param isMain whether the frame is the main frame
   * @param url the URL of the frame
   * @param mainFrameUrl the URL of the main frame of the browser
   * @param focusedFrameId the identifier of the focused frame of the browser
   * @param browserHasFocus whether the component of the browser has the focus of the IDE
   * @param hasInputSource whether the browser has a source of user input
   */
  class Frame(
    val frameId: String,
    val isMain: Boolean,
    val url: String?,
    val mainFrameUrl: String?,
    val focusedFrameId: String?,
    val browserHasFocus: Boolean,
    val hasInputSource: Boolean,
  )

  /** Records a user input. */
  fun userInput() {
    userInputTime.set(nanoTime())
  }

  /** Returns the one-time token of a copy or a cut that the IDE runs in the frame. */
  fun startCopy(frameId: String): String = newToken(copyPermit, frameId)

  /** Returns the one-time token of a paste that the IDE runs in the frame. */
  fun startPaste(frameId: String): String = newToken(pastePermit, frameId)

  /**
   * Returns whether the bridge accepts a copy request of the frame with the token. An empty token means no token.
   */
  fun acceptsCopy(frame: Frame, token: String): Boolean {
    if (token.isNotEmpty()) return consume(copyPermit, frame.frameId, token)
    if (frame.isMain) {
      return !frame.hasInputSource || frame.browserHasFocus || isWithinUserInputWindow(userInputTime.get(), MAIN_FRAME_INPUT_WINDOW_NANOS)
    }
    if (frame.focusedFrameId != frame.frameId || !isSameOrigin(frame.url, frame.mainFrameUrl)) return false
    val time = userInputTime.get()
    if (!isWithinUserInputWindow(time, SUBFRAME_INPUT_WINDOW_NANOS)) return false
    val used = usedUserInputTime.get()
    return used != time && usedUserInputTime.compareAndSet(used, time)
  }

  /**
   * Returns whether the bridge accepts a paste request or a paste error of the frame with the token.
   */
  fun acceptsPaste(frameId: String, token: String): Boolean = consume(pastePermit, frameId, token)

  private fun isWithinUserInputWindow(time: Long, window: Long): Boolean = time != NO_USER_INPUT && nanoTime() - time <= window

  private fun newToken(permits: AtomicReference<Permit?>, frameId: String): String {
    val token = UUID.randomUUID().toString()
    permits.set(Permit(frameId, token, nanoTime() + PERMIT_TIMEOUT_NANOS))
    return token
  }

  private fun consume(permits: AtomicReference<Permit?>, frameId: String, token: String): Boolean {
    val permit = permits.get() ?: return false
    if (permit.frameId != frameId || permit.token != token || nanoTime() - permit.deadline > 0) return false
    return permits.compareAndSet(permit, null)
  }

  private class Permit(val frameId: String, val token: String, val deadline: Long)

  companion object {
    /**
     * Returns whether [focusOwner] is in the component of a browser: [browserComponent], or [uiComponent] for a browser
     * without one.
     */
    fun isFocusIn(focusOwner: Component?, browserComponent: Component?, uiComponent: Component?): Boolean {
      val component = browserComponent ?: uiComponent ?: return false
      return focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, component)
    }
  }
}

// Chromium gives a file URL an opaque origin, so no file URL has the origin of another one.
private fun isSameOrigin(url: String?, mainFrameUrl: String?): Boolean {
  val origin = JBCefJSQuery.FramePolicy.originOf(url) ?: return false
  return origin != "file://" && origin == JBCefJSQuery.FramePolicy.originOf(mainFrameUrl)
}
