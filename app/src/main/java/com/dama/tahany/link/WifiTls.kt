package com.dama.tahany.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

internal object WifiTls {
    private const val KEY_ALIAS = "tahany-wifi-tls"

    @Synchronized
    fun serverContext(): SSLContext {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // TLS signs a digest that the platform provider has already computed.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
        val key = store.getKey(KEY_ALIAS, null) as PrivateKey
        val certificate = store.getCertificate(KEY_ALIAS) as X509Certificate
        val manager = object : X509KeyManager {
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                if (type == "EC") arrayOf(KEY_ALIAS) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
                getServerAliases(type, issuers)?.first()
            override fun getCertificateChain(alias: String?) =
                if (alias == KEY_ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?) = if (alias == KEY_ALIAS) key else null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, null) }
    }

    fun clientContext(): SSLContext {
        // The first TLS connection is provisional. The controller binds the
        // displayed pairing code to the certificate and requires both confirmations.
        val provisionalTrust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not used")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.singleOrNull()
                    ?: throw CertificateException("Expected one Tahany certificate")
                certificate.checkValidity()
                if (certificate.publicKey.algorithm != "EC") throw CertificateException("Expected an EC key")
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(provisionalTrust), null)
        }
    }
}
