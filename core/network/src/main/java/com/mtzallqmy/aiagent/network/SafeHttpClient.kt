package com.mtzallqmy.aiagent.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Network layer shared by all providers.
 * Includes SSRF protection: private/loopback addresses are rejected by default
 * unless the caller explicitly allows them.
 */
object SafeHttpClient {
    fun create(timeoutMs: Long = 60_000L, allowPrivateNetwork: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (!allowPrivateNetwork) {
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = InetAddress.getAllByName(hostname)
                    return addresses.filter { !isPrivate(it.hostAddress) }.ifEmpty {
                        throw java.net.UnknownHostException("Blocked private/loopback address for $hostname")
                    }
                }
            })
        }
        return builder.build()
    }

    fun isPrivate(host: String?): Boolean {
        if (host == null) return false
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized == "0.0.0.0") return true
        if (normalized.startsWith("10.") || normalized.startsWith("192.168.") || normalized.startsWith("172.")) return true
        if (normalized.startsWith("169.254.")) return true
        return false
    }

    /** URL normalization: scheme + host trimmed, blocks javascript:/file: schemes. */
    fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            val scheme = (uri.scheme ?: "https").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.authority ?: return null
            if (isPrivate(uri.host)) return null
            "$scheme://$authority${uri.rawPath ?: ""}${uri.rawQuery?.let { "?$it" } ?: ""}"
        }.getOrNull()
    }
}
