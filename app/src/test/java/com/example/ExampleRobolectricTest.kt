package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("CastNotify", appName)
  }

  @Test
  fun `verify notification payload serialization on Android context`() {
    val jsonInput = """
      {
        "appName": "Zalo",
        "title": "John Doe",
        "message": "Hello, see you on TV!",
        "timestamp": 1710000000,
        "privacyMode": false
      }
    """.trimIndent()

    val payload = com.example.model.NotificationPayload.fromJsonString(jsonInput)
    org.junit.Assert.assertNotNull(payload)
    assertEquals("Zalo", payload!!.appName)
    assertEquals("John Doe", payload.title)
    assertEquals("Hello, see you on TV!", payload.message)
    assertEquals(1710000000L, payload.timestamp)
    org.junit.Assert.assertFalse(payload.privacyMode)
  }
}
