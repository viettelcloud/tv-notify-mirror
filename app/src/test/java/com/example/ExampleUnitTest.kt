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

  @Test
  fun testSubnetPrefixExtraction() {
    val prefix1 = com.example.network.NetworkDiscovery.getSubnetPrefix("192.168.1.55")
    assertEquals("192.168.1.", prefix1)

    val prefix2 = com.example.network.NetworkDiscovery.getSubnetPrefix("10.0.2.15")
    assertEquals("10.0.2.", prefix2)
  }

  @Test
  fun testDiscoveredTvModel() {
    val tv = com.example.network.DiscoveredTv(
      name = "Living Room TV",
      ip = "192.168.1.88",
      port = 8080,
      source = "UDP Broadcast"
    )
    assertEquals("Living Room TV", tv.name)
    assertEquals("192.168.1.88", tv.ip)
    assertEquals(8080, tv.port)
    assertEquals("UDP Broadcast", tv.source)
  }
}

