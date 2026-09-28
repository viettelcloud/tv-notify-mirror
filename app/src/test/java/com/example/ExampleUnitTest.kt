package com.example

import com.example.model.NotificationPayload
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun payloadSerializationAndDeserialization_isCorrect() {
    val jsonInput = """
      {
        "appName": "Zalo",
        "title": "John Doe",
        "message": "Hello, see you on TV!",
        "timestamp": 1710000000,
        "privacyMode": false
      }
    """.trimIndent()

    val payload = NotificationPayload.fromJsonString(jsonInput)
    assertNotNull(payload)
    assertEquals("Zalo", payload!!.appName)
    assertEquals("John Doe", payload.title)
    assertEquals("Hello, see you on TV!", payload.message)
    assertEquals(1710000000L, payload.timestamp)
    assertFalse(payload.privacyMode)

    val serialized = payload.toJsonString()
    val roundTrip = NotificationPayload.fromJsonString(serialized)
    assertNotNull(roundTrip)
    assertEquals("Zalo", roundTrip!!.appName)
    assertEquals("John Doe", roundTrip.title)
    assertEquals("Hello, see you on TV!", roundTrip.message)
  }
}

