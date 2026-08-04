package top.jie65535.mirai.tools

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisitWebTest {
    @Test
    fun `self hosted reader request uses configured base url`() {
        val request = VisitWeb.createReaderRequest(
            rawUrl = "https://example.com/docs?q=reader#section",
            readerBaseUrl = "http://127.0.0.1:4223/",
            apiKey = "",
            resolveAddresses = { listOf(InetAddress.getByName("93.184.216.34")) },
        )

        assertEquals(
            "http://127.0.0.1:4223/https://example.com/docs?q=reader",
            request.endpoint,
        )
        assertEquals(null, request.apiKey)
    }

    @Test
    fun `hosted reader remains backward compatible`() {
        val request = VisitWeb.createReaderRequest(
            rawUrl = "https://example.com/",
            readerBaseUrl = "https://r.jina.ai/",
            apiKey = "token",
            resolveAddresses = { listOf(InetAddress.getByName("93.184.216.34")) },
        )

        assertEquals("https://r.jina.ai/https://example.com/", request.endpoint)
        assertEquals("token", request.apiKey)
    }

    @Test
    fun `private resolved targets are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VisitWeb.createReaderRequest(
                rawUrl = "https://example.com/",
                readerBaseUrl = "http://127.0.0.1:4223/",
                apiKey = "",
                resolveAddresses = { listOf(InetAddress.getByName("192.168.1.10")) },
            )
        }
    }

    @Test
    fun `internal hostnames and credentials are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VisitWeb.createReaderRequest(
                rawUrl = "http://redis/",
                readerBaseUrl = "http://127.0.0.1:4223/",
                apiKey = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VisitWeb.createReaderRequest(
                rawUrl = "https://user:password@example.com/",
                readerBaseUrl = "http://127.0.0.1:4223/",
                apiKey = "",
            )
        }
    }

    @Test
    fun `unresolved public host may be resolved by reader deployment`() {
        val request = VisitWeb.createReaderRequest(
            rawUrl = "https://blocked.example/",
            readerBaseUrl = "http://127.0.0.1:4223/",
            apiKey = "",
            resolveAddresses = { throw UnknownHostException("proxy DNS only") },
        )

        assertEquals("http://127.0.0.1:4223/https://blocked.example/", request.endpoint)
    }
}
