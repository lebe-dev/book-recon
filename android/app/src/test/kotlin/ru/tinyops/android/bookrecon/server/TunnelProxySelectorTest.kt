package ru.tinyops.android.bookrecon.server

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TunnelProxySelectorTest {
    private var route: TunnelRoute? = TunnelRoute("https://b", "t", setOf("rutracker.org", "flibusta.is"))
    private val selector = TunnelProxySelector({ route }, { 4567 })

    private fun select(url: String) = selector.select(URI(url)).single()

    @Test
    fun `served https hosts go through the local proxy`() {
        val proxy = select("https://rutracker.org/forum/index.php")
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals(4567, address.port)
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(Proxy.Type.HTTP, select("https://static.rutracker.org/x.css").type())
    }

    @Test
    fun `other hosts, plain http and no route go direct`() {
        assertEquals(Proxy.NO_PROXY, select("https://example.com/"))
        assertEquals(Proxy.NO_PROXY, select("http://flibusta.is/"))
        route = null
        assertEquals(Proxy.NO_PROXY, select("https://rutracker.org/"))
    }
}
