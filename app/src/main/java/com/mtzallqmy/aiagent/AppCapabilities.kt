package com.mtzallqmy.aiagent

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mtzallqmy.aiagent.capabilities.Capability
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.CapabilityAvailabilityState
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.native_runtime.RustRuntimeClient
import java.io.File

fun registerAppCapabilities(context: Context, registry: CapabilityRegistry) {
    registry.register(CheckedCapability("workspace") {
        runCatching {
            val root = context.filesDir.resolve("workspaces")
            if ((root.exists() || root.mkdirs()) && root.canRead() && root.canWrite()) {
                CapabilityAvailabilityState.AVAILABLE
            } else {
                CapabilityAvailabilityState.BACKEND_UNAVAILABLE
            }
        }.getOrDefault(CapabilityAvailabilityState.BACKEND_UNAVAILABLE)
    })

    registry.register(CheckedCapability("device") {
        if (context.packageManager != null) CapabilityAvailabilityState.AVAILABLE
        else CapabilityAvailabilityState.DEVICE_UNSUPPORTED
    })

    registry.register(CheckedCapability("clipboard") {
        if (context.getSystemService(Context.CLIPBOARD_SERVICE) is ClipboardManager) {
            CapabilityAvailabilityState.AVAILABLE
        } else {
            CapabilityAvailabilityState.BACKEND_UNAVAILABLE
        }
    })

    registry.register(CheckedCapability("network") {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@CheckedCapability CapabilityAvailabilityState.BACKEND_UNAVAILABLE
        val active = manager.activeNetwork
            ?: return@CheckedCapability CapabilityAvailabilityState.DEGRADED
        val capabilities = manager.getNetworkCapabilities(active)
            ?: return@CheckedCapability CapabilityAvailabilityState.DEGRADED
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            CapabilityAvailabilityState.AVAILABLE
        } else {
            CapabilityAvailabilityState.DEGRADED
        }
    })

    registry.register(CheckedCapability("terminal") {
        val client = RustRuntimeClient(context)
        runCatching {
            client.connect()
            val capabilities = client.capabilities()
            if (capabilities.androidIsolatedProcess && capabilities.rustProcessBoundary) {
                CapabilityAvailabilityState.AVAILABLE
            } else {
                CapabilityAvailabilityState.SECURITY_DENIED
            }
        }.getOrDefault(CapabilityAvailabilityState.BACKEND_UNAVAILABLE).also {
            client.close()
        }
    })

    registry.register(CheckedCapability("ssh") {
        val executable = listOf(
            File("/system/bin/ssh"),
            File("/system/xbin/ssh"),
        ).firstOrNull { it.isFile && it.canExecute() }
        if (executable != null) CapabilityAvailabilityState.AVAILABLE
        else CapabilityAvailabilityState.BACKEND_UNAVAILABLE
    })
}

private class CheckedCapability(
    id: String,
    private val check: suspend () -> CapabilityAvailabilityState,
) : Capability {
    override val id = CapabilityId(id)
    override suspend fun availability(): CapabilityAvailabilityState = check()
}
