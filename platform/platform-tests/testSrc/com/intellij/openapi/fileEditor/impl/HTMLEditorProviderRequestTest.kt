// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HTMLEditorProviderRequestTest {
  @Test
  fun `a request takes only origins as its trusted origins`() {
    val request = HTMLEditorProvider.Request.url("https://example.com/page")
      .withTrustedOrigins("https://example.com", "http://127.0.0.1:8080", "https://[::1]:8443", "https://a-b.example.org/")
    assertEquals(listOf("https://example.com", "http://127.0.0.1:8080", "https://[::1]:8443", "https://a-b.example.org/"),
                 request.trustedOrigins)

    for (origin in listOf("https://example.com/path", "https://example.com?x=1", "https://user@example.com", "example.com",
                          "about:blank", "HTTPS://example.com", "https://example.com:65536", "https://")) {
      assertThrows<IllegalArgumentException>(origin) { HTMLEditorProvider.Request.url("https://example.com/").withTrustedOrigins(origin) }
    }
  }
}
