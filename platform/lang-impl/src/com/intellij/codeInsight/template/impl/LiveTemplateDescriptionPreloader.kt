// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.impl

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val LOG = logger<LiveTemplateDescriptionPreloader>()

/**
 * Reads the descriptions of the default live templates on a background thread.
 */
@Service(Service.Level.APP)
internal class LiveTemplateDescriptionPreloader(private val coroutineScope: CoroutineScope) {
  private var job: Job? = null

  /**
   * Cancels the previous preload and reads the descriptions of [templates].
   */
  @Synchronized
  fun preload(templates: List<TemplateImpl>) {
    job?.cancel()
    job = coroutineScope.launch(Dispatchers.IO) {
      for (template in templates) {
        ensureActive()
        try {
          template.description
        }
        catch (e: Throwable) {
          if (e is CancellationException || e is ControlFlowException) {
            throw e
          }
          LOG.warn("Cannot preload the description of template ${template.key} in group ${template.groupName}", e)
        }
      }
    }
  }
}
