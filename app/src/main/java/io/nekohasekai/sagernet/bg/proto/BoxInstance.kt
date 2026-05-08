package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import com.wireguard.android.backend.TurnBackend
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {
    private fun launchTurnRelayIfEnabled() {
        val bean = profile.requireBean()
        val enabled = when (bean) {
            is HysteriaBean -> bean.turnEnabled == true
            is WireGuardBean -> bean.turnEnabled == true
            else -> false
        }
        if (!enabled) return

        val relayPort = when (bean) {
            is HysteriaBean -> bean.turnRelayPort ?: 9000
            is WireGuardBean -> bean.turnRelayPort ?: 9000
            else -> 9000
        }
        val peer = when (bean) {
            is HysteriaBean -> bean.turnPeer
            is WireGuardBean -> bean.turnPeer
            else -> ""
        }?.trim().orEmpty()
        if (peer.isBlank()) error("TURN relay peer is empty")

        val source = when (bean) {
            is HysteriaBean -> bean.turnSource
            is WireGuardBean -> bean.turnSource
            else -> "vk"
        }?.trim().orEmpty().lowercase()
        val authLink = when (bean) {
            is HysteriaBean -> bean.turnAuthLink
            is WireGuardBean -> bean.turnAuthLink
            else -> ""
        }?.trim().orEmpty()
        val turnHost = when (bean) {
            is HysteriaBean -> bean.turnServer
            is WireGuardBean -> bean.turnServer
            else -> ""
        }?.trim().orEmpty()
        val turnPort = when (bean) {
            is HysteriaBean -> bean.turnPort ?: 3478
            is WireGuardBean -> bean.turnPort ?: 3478
            else -> 3478
        }
        val turnUdp = when (bean) {
            is HysteriaBean -> bean.turnUseUdp == true
            is WireGuardBean -> bean.turnUseUdp == true
            else -> false
        }

        val peerType = if (bean is WireGuardBean) "wireguard" else "proxy_v2"
        val nativeStarted = TurnBackend.startRelay(
            TurnBackend.RelayConfig(
                peerAddr = peer,
                authLink = authLink,
                source = source,
                useUdp = turnUdp,
                listenAddr = "127.0.0.1:$relayPort",
                turnIp = turnHost,
                turnPort = turnPort,
                peerType = peerType,
            )
        )
        if (nativeStarted) return

        val cmd = mutableListOf(initPlugin("turn-relay-plugin").path, "-peer", peer, "-listen", "127.0.0.1:$relayPort")
        if (source == "wb") {
            cmd.add("-wb")
        } else {
            if (authLink.isBlank()) error("TURN auth link is empty for VK source")
            cmd.addAll(listOf("-vk-link", authLink))
        }
        if (turnUdp) cmd.add("-udp")
        if (turnHost.isNotBlank()) cmd.addAll(listOf("-turn", turnHost))
        if (turnPort > 0) cmd.addAll(listOf("-port", turnPort.toString()))
        processes.start(cmd)
    }

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            File(
                                app.cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()
        launchTurnRelayIfEnabled()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = File(
                            cacheDir, "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = File(
                            cacheDir, "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = File(
                            cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = File(
                                cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".crt"
                            )

                            certFile.parentFile?.mkdirs()
                            certFile.writeText(bean.certificates)
                            cacheFiles.add(certFile)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = File(
                            cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        TurnBackend.stopRelay()
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::box.isInitialized) {
            box.close()
        }
    }

}
