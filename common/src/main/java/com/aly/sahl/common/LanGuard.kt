package com.aly.sahl.common

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Safety checks for the small web servers on the TV (phone remote, "send from phone" pages):
 *  • only devices on the home network may connect (private addresses: 10.x, 172.16–31.x, 192.168.x, and IPv6 local);
 *  • the address typed in the browser must be the TV's own number, not a website name — this stops a web page
 *    on the phone from secretly talking to the TV (a trick called "DNS rebinding");
 *  • pages are sent with headers that stop them being framed by other sites or leaking the address.
 */
object LanGuard {
    fun allowedPeer(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC          // unique local fc00::/7
        return false
    }

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** The Host header must be a private IP address (or localhost), never a domain name. */
    fun allowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().let { if (it.startsWith("[")) it.substringAfter("[").substringBefore("]") else it.substringBeforeLast(":", it) }.lowercase()
        if (h == "localhost") return true
        val m = ipv4.matchEntire(h)
        if (m != null) {
            if (m.groupValues.drop(1).any { it.toInt() > 255 }) return false
            return try { allowedPeer(InetAddress.getByName(h) as? Inet4Address) } catch (_: Exception) { false }
        }
        if (h.contains(':')) return try { allowedPeer(InetAddress.getByName(h)) } catch (_: Exception) { false }   // IPv6 literal
        return false
    }

    /** Extra response headers for every page and answer. */
    const val HEADERS = "X-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
        "Content-Security-Policy: default-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'\r\n"

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison (no timing hints about how much of a code was right). */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
