package com.wireguard.android.backend

import android.net.VpnService
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.atomic.AtomicBoolean

object TurnBackend {
    private val loaded = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private external fun wgSetVpnService(vpnService: VpnService?)
    private external fun wgTurnProxyStart(
        peerAddr: String,
        vkLink: String,
        mode: String,
        n: Int,
        useUdp: Int,
        listenAddr: String,
        turnIp: String,
        turnPort: Int,
        peerType: String,
        streamsPerCred: Int,
        watchdogTimeout: Int,
        networkHandle: Long,
    ): Int

    private external fun wgTurnProxyStop()
    private external fun wgNotifyNetworkChange()

    private fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        return try {
            System.loadLibrary("wg-go")
            loaded.set(true)
            true
        } catch (e: Throwable) {
            Logs.w("TurnBackend: libwg-go load failed", e)
            false
        }
    }

    fun setVpnService(vpnService: VpnService?) {
        if (!ensureLoaded()) return
        wgSetVpnService(vpnService)
    }

    data class RelayConfig(
        val peerAddr: String,
        val authLink: String,
        val source: String,
        val useUdp: Boolean,
        val listenAddr: String,
        val turnIp: String,
        val turnPort: Int,
        val peerType: String,
        val streams: Int = 4,
        val streamsPerCred: Int = 4,
        val watchdogTimeoutSec: Int = 30,
        val networkHandle: Long = 0L,
    )

    fun startRelay(config: RelayConfig): Boolean {
        if (!ensureLoaded()) return false
        if (running.get()) stopRelay()

        val mode = if (config.source.lowercase() == "wb") "wb" else "vk"
        val result = wgTurnProxyStart(
            config.peerAddr,
            config.authLink,
            mode,
            config.streams,
            if (config.useUdp) 1 else 0,
            config.listenAddr,
            config.turnIp,
            config.turnPort,
            config.peerType,
            config.streamsPerCred,
            config.watchdogTimeoutSec,
            config.networkHandle,
        )
        val ok = result == 0
        running.set(ok)
        return ok
    }

    fun stopRelay() {
        if (!loaded.get()) return
        if (!running.get()) return
        wgTurnProxyStop()
        running.set(false)
    }

    fun notifyNetworkChange() {
        if (!loaded.get()) return
        wgNotifyNetworkChange()
    }
}

