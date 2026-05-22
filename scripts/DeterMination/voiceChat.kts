@file:Depends("coreMindustry")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.command
import coreMindustry.lib.hasPermission
import kotlinx.coroutines.runBlocking
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val packetHello = "determination.voice.hello.v1"
private val packetHelloAck = "determination.voice.helloAck.v1"
private val packetState = "determination.voice.state.v1"
private val packetError = "determination.voice.error.v1"
private val packetFrameUp = "determination.voice.frame.up.v1"
private val packetFrameDown = "determination.voice.frame.down.v1"

private val permissionUse = "determination.voicechat.use"
private val permissionGlobal = "determination.voicechat.global"
private val permissionAdmin = "determination.voicechat.admin"

private val protocolVersion by config.key(1, "Voice protocol version")
private val codecName by config.key("imaadpcm16k", "Voice codec label")
private val maxFrameBytes by config.key(220, "Max encoded voice payload bytes")
private val maxBytesPerSecond by config.key(14_000, "Per-player uplink byte limit per second")
private val globalRequiresPermission by config.key(true, "Require permission for global voice")

private data class VoiceState(
    var linked: Boolean = true,
    var micEnabled: Boolean = false,
    var mode: String = "team",
    var lastSeenMs: Long = 0L,
    var lastFrameMs: Long = 0L,
    var windowStartMs: Long = 0L,
    var windowBytes: Int = 0,
)

private val voiceStates = mutableMapOf<String, VoiceState>()

private var packetMethodResolved = false
private var clientPacketReliableMethod: Method? = null
private var clientBinaryPacketUnreliableMethod: Method? = null

private fun nowMs(): Long = System.currentTimeMillis()

private fun decodePayload(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val map = linkedMapOf<String, String>()
    raw.split('&').forEach { part ->
        val eq = part.indexOf('=')
        if (eq <= 0 || eq >= part.lastIndex) return@forEach
        val key = part.substring(0, eq)
        val value = part.substring(eq + 1)
        map[key] = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
    return map
}

private fun encodePayload(values: Map<String, String>): String =
    values.entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())}"
    }

private fun resolvePacketMethods() {
    if (packetMethodResolved) return
    packetMethodResolved = true
    runCatching {
        Call::class.java.methods.forEach { method ->
            if (method.name == "clientPacketReliable" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == Player::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == String::class.java
            ) {
                clientPacketReliableMethod = method
            }
            if (method.name == "clientBinaryPacketUnreliable" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == Player::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == ByteArray::class.java
            ) {
                clientBinaryPacketUnreliableMethod = method
            }
        }
    }.onFailure {
        logger.warning("VoiceChat: resolve packet methods failed: ${it.message}")
    }
}

private fun sendClientPacket(player: Player, type: String, payload: String): Boolean {
    resolvePacketMethods()
    val method = clientPacketReliableMethod ?: return false
    return runCatching {
        method.invoke(null, player, type, payload)
    }.onFailure {
        logger.warning("VoiceChat: send packet $type to ${player.name} failed: ${it.message}")
    }.isSuccess
}

private fun sendClientBinary(player: Player, type: String, payload: ByteArray): Boolean {
    resolvePacketMethods()
    val method = clientBinaryPacketUnreliableMethod ?: return false
    return runCatching {
        method.invoke(null, player, type, payload)
    }.onFailure {
        logger.warning("VoiceChat: send binary $type to ${player.name} failed: ${it.message}")
    }.isSuccess
}

private fun stateOf(player: Player): VoiceState =
    voiceStates.getOrPut(player.uuid()) { VoiceState() }

private fun canUseGlobal(player: Player): Boolean {
    if (!globalRequiresPermission) return true
    return runBlocking { player.hasPermission(permissionGlobal) }
}

private fun effectiveMode(player: Player, state: VoiceState): String {
    if (state.mode == "global" && canUseGlobal(player)) return "global"
    return "team"
}

private fun touchRateWindow(state: VoiceState, byteCount: Int): Boolean {
    val now = nowMs()
    if (now - state.windowStartMs >= 1000L) {
        state.windowStartMs = now
        state.windowBytes = 0
    }
    if (state.windowBytes + byteCount > maxBytesPerSecond.coerceAtLeast(2048)) return false
    state.windowBytes += byteCount
    return true
}

private fun sendAck(player: Player, state: VoiceState) {
    val mode = effectiveMode(player, state)
    sendClientPacket(
        player,
        packetHelloAck,
        encodePayload(
            mapOf(
                "pv" to protocolVersion.toString(),
                "codec" to codecName,
                "mode" to mode,
                "mic" to if (state.micEnabled) "1" else "0"
            )
        )
    )
}

private fun sendError(player: Player, code: String, message: String) {
    sendClientPacket(player, packetError, encodePayload(mapOf("code" to code, "message" to message)))
}

private fun handleHello(player: Player, raw: String) {
    val payload = decodePayload(raw)
    val state = stateOf(player)
    state.linked = true
    state.lastSeenMs = nowMs()
    state.micEnabled = payload["mic"] == "1"
    state.mode = if (payload["mode"].equals("global", ignoreCase = true)) "global" else "team"
    if (state.mode == "global" && !canUseGlobal(player)) {
        state.mode = "team"
        sendError(player, "GLOBAL_DENIED", "global voice denied by server permission")
    }
    sendAck(player, state)
}

private fun handleState(player: Player, raw: String) {
    val payload = decodePayload(raw)
    val state = stateOf(player)
    state.lastSeenMs = nowMs()
    state.micEnabled = payload["mic"] == "1"
    state.mode = if (payload["mode"].equals("global", ignoreCase = true)) "global" else "team"
    if (state.mode == "global" && !canUseGlobal(player)) {
        state.mode = "team"
        sendError(player, "GLOBAL_DENIED", "global voice denied by server permission")
    }
    sendAck(player, state)
}

private fun buildRelayPacket(player: Player, mode: String, sequence: Int, encoded: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(encoded.size + 64)
    DataOutputStream(output).use { data ->
        data.writeByte(protocolVersion)
        data.writeByte(if (mode == "global") 1 else 0)
        data.writeInt(sequence)
        data.writeUTF(player.uuid())
        data.write(encoded)
        data.flush()
    }
    return output.toByteArray()
}

private fun relayTargets(sender: Player, mode: String): List<Player> {
    val list = mutableListOf<Player>()
    Groups.player.each { target ->
        if (target === sender) return@each
        if (voiceStates[target.uuid()] == null) return@each
        if (mode == "team" && target.team() != sender.team()) return@each
        list += target
    }
    return list
}

private fun handleFrame(player: Player, raw: ByteArray) {
    val state = voiceStates[player.uuid()] ?: return
    if (!state.micEnabled) return
    if (!touchRateWindow(state, raw.size)) {
        sendError(player, "RATE_LIMIT", "voice uplink rate limited")
        return
    }
    if (raw.size < 6) return

    val input = DataInputStream(ByteArrayInputStream(raw))
    val protocol = input.readUnsignedByte()
    if (protocol != protocolVersion) return
    input.readUnsignedByte()
    val sequence = input.readInt()
    val encoded = ByteArray(input.available())
    input.readFully(encoded)
    if (encoded.isEmpty() || encoded.size > maxFrameBytes.coerceAtLeast(64)) return

    state.lastSeenMs = nowMs()
    state.lastFrameMs = state.lastSeenMs

    val mode = effectiveMode(player, state)
    if (state.mode == "global" && mode != "global") {
        state.mode = "team"
        sendError(player, "GLOBAL_DENIED", "global voice denied by server permission")
        sendAck(player, state)
    }

    val relay = buildRelayPacket(player, mode, sequence, encoded)
    relayTargets(player, mode).forEach { target ->
        sendClientBinary(target, packetFrameDown, relay)
    }
}

private fun clearState(uuid: String) {
    voiceStates.remove(uuid)
}

onEnable {
    netServer.addPacketHandler(packetHello) { player, payload ->
        handleHello(player, payload)
    }
    netServer.addPacketHandler(packetState) { player, payload ->
        handleState(player, payload)
    }
    netServer.addBinaryPacketHandler(packetFrameUp) { player, payload ->
        handleFrame(player, payload)
    }
    logger.info("VoiceChat: packet bridge enabled under DeterMination/voiceChat")
}

onDisable {
    netServer.getPacketHandlers(packetHello).clear()
    netServer.getPacketHandlers(packetState).clear()
    netServer.getBinaryPacketHandlers(packetFrameUp).clear()
    voiceStates.clear()
}

listen<EventType.PlayerLeave> {
    clearState(it.player.uuid())
}

command("voicechat", "Show voice chat relay status") {
    aliases = listOf("vcstatus")
    requirePermission(permissionAdmin)
    body {
        val now = nowMs()
        val linked = voiceStates.size
        val active = voiceStates.values.count { now - it.lastFrameMs <= 1500L }
        val global = voiceStates.values.count { it.mode == "global" }
        returnReply("[accent]VoiceChat[] linked=$linked active=$active globalMode=$global codec=$codecName".with())
    }
}

PermissionApi.registerDefault(permissionUse)
PermissionApi.registerDefault(permissionGlobal)
PermissionApi.registerDefault(permissionAdmin, group = "@admin")
