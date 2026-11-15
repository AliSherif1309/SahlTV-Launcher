package com.aly.sahl.common

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aly.sahl.connect.Protocol
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** Device-local identity; no CA, cloud account, shared server secret or cleartext fallback. */
object ConnectTls {
    private const val ALIAS = "sahl-connect-server-v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun server(): Pair<SSLServerSocket, String> {
        var ks = store()
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Sahl Connect"))
                    .setCertificateNotAfter(java.util.Date(4102444800000L)).build())
                generateKeyPair()
            }
            ks = store()
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(km.keyManagers, null, null) }
        val socket = ctx.serverSocketFactory.createServerSocket(Protocol.PORT) as SSLServerSocket
        socket.enabledProtocols = arrayOf("TLSv1.2")
        return socket to Protocol.hash(ks.getCertificate(ALIAS).encoded)
    }
    fun client(host: String, pin: String): SSLSocket {
        require(Protocol.privateV4(host) && pin.matches(Regex("[a-f0-9]{64}")))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { throw CertificateException("Not a client identity") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                if (chain.isEmpty() || !Protocol.same(pin, Protocol.hash(chain[0].encoded))) throw CertificateException("TV identity changed; pair again on the TV")
                chain[0].checkValidity()
            }
        }
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(trust), null) }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        try {
            s.enabledProtocols = arrayOf("TLSv1.2"); s.soTimeout = 15000
            s.connect(InetSocketAddress(host, Protocol.PORT), 5000); s.startHandshake(); return s
        } catch (e: Exception) { s.close(); throw e }
    }
}
