@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("coreMindustry/utilNextChat", "读取下一条聊天输入")
@file:Depends("wayzer", "WayZer基础模块")
@file:Depends("wayzer/user/shortID", "短ID映射")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.contextScript
import coreLibrary.lib.CommandContext
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.command
import coreMindustry.PagedMenuBuilder
import coreMindustry.UtilNextChat
import mindustry.gen.Player
import mindustry.net.NetConnection
import wayzer.lib.PlayerData
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private val packetHello = "hm.hello"
private val packetChallenge = "hm.challenge"
private val packetProve = "hm.prove"
private val packetOk = "hm.ok"
private val packetError = "hm.error"
private val packetDeliver = "hm.deliver"

private val protocolVersion by config.key(1, "协议版本")
private val challengeTtlSeconds by config.key(15, "握手挑战过期秒数")
private val sessionTtlSeconds by config.key(180, "握手会话有效秒数")
private val maxMessageLength by config.key(200, "消息最大长度")
private val rateLimitWindowSeconds by config.key(5, "限流窗口秒数")
private val rateLimitCount by config.key(5, "窗口内最大消息数")
private val sharedSecret by config.key("hm-v1-default-secret", "签名密钥(需与客户端模组一致)")
private val logAudit by config.key(true, "是否记录审计日志")

private data class PendingChallenge(
    val protocol: Int,
    val modVersion: String,
    val clientNonce: String,
    val serverNonce: String,
    val expireAtMs: Long
)

private data class VerifiedSession(
    val protocol: Int,
    val modVersion: String,
    var verifiedAtMs: Long,
    var lastSeenMs: Long
)

private val pendingByUuid = mutableMapOf<String, PendingChallenge>()
private val sessionByUuid = mutableMapOf<String, VerifiedSession>()
private val sendHistoryByUuid = mutableMapOf<String, ArrayDeque<Long>>()
private val lastContactByUuid = mutableMapOf<String, String>()
private val nextChatApi by lazy { contextScript<UtilNextChat>() }

private val displayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private var packetMethodWarningPrinted = false
private var packetMethodResolved = false
private var clientPacketReliableMethod: Method? = null

private fun nowMs(): Long = System.currentTimeMillis()
private fun challengeTtlMs(): Long = challengeTtlSeconds.coerceAtLeast(3) * 1000L
private fun sessionTtlMs(): Long = sessionTtlSeconds.coerceAtLeast(30) * 1000L
private fun rateLimitWindowMs(): Long = rateLimitWindowSeconds.coerceAtLeast(1) * 1000L

private fun randomNonce(length: Int = 24): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    return buildString(length) {
        repeat(length) { append(chars[Random.nextInt(chars.length)]) }
    }
}

private fun encodePayload(values: Map<String, String>): String =
    values.entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())}"
    }

private fun decodePayload(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val map = linkedMapOf<String, String>()
    raw.split('&').forEach { part ->
        val eq = part.indexOf('=')
        if (eq <= 0 || eq == part.lastIndex) return@forEach
        val key = part.substring(0, eq)
        val value = part.substring(eq + 1)
        map[key] = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
    return map
}

private fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    val out = StringBuilder(bytes.size * 2)
    bytes.forEach { b ->
        val v = b.toInt() and 0xff
        val hi = "0123456789abcdef"[v ushr 4]
        val lo = "0123456789abcdef"[v and 0x0f]
        out.append(hi).append(lo)
    }
    return out.toString()
}

private fun expectedSignature(
    uuid: String,
    protocol: Int,
    modVersion: String,
    clientNonce: String,
    serverNonce: String
): String {
    val base = listOf(sharedSecret, uuid, protocol.toString(), modVersion, clientNonce, serverNonce).joinToString("|")
    return sha256Hex(base)
}

private fun ensurePacketMethodResolved() {
    if (packetMethodResolved) return
    packetMethodResolved = true
    clientPacketReliableMethod = runCatching {
        Class.forName("mindustry.gen.Call").methods.firstOrNull { m ->
            if (m.name != "clientPacketReliable") return@firstOrNull false
            val p = m.parameterTypes
            p.size == 3 &&
                p[1] == String::class.java &&
                p[2] == String::class.java &&
                (Player::class.java.isAssignableFrom(p[0]) || NetConnection::class.java.isAssignableFrom(p[0]))
        }?.apply { isAccessible = true }
    }.getOrNull()
}

private fun sendClientPacket(player: Player, type: String, payload: String): Boolean {
    if (player.con == null) return false
    ensurePacketMethodResolved()
    val method = clientPacketReliableMethod
    if (method == null) {
        if (!packetMethodWarningPrinted) {
            packetMethodWarningPrinted = true
            logger.warning("HiddenMessage: cannot resolve Call.clientPacketReliable, fallback mode enabled.")
        }
        return false
    }

    return runCatching {
        val firstType = method.parameterTypes[0]
        val firstArg: Any? = when {
            NetConnection::class.java.isAssignableFrom(firstType) -> player.con
            Player::class.java.isAssignableFrom(firstType) -> player
            else -> null
        }
        if (firstArg == null) return@runCatching false
        method.invoke(null, firstArg, type, payload)
        true
    }.getOrElse {
        logger.warning("HiddenMessage: send packet fail to ${player.name}: ${it.message}")
        false
    }
}

private fun isVerified(player: Player, currentMs: Long = nowMs()): Boolean {
    val session = sessionByUuid[player.uuid()] ?: return false
    if (currentMs - session.lastSeenMs > sessionTtlMs()) {
        sessionByUuid.remove(player.uuid())
        return false
    }
    return true
}

private fun touchSession(player: Player, currentMs: Long = nowMs()) {
    sessionByUuid[player.uuid()]?.lastSeenMs = currentMs
}

private fun hitRateLimit(uuid: String, currentMs: Long = nowMs()): Boolean {
    val history = sendHistoryByUuid.getOrPut(uuid) { ArrayDeque() }
    val window = rateLimitWindowMs()
    while (history.isNotEmpty() && currentMs - history.first() > window) {
        history.removeFirst()
    }
    val limit = rateLimitCount.coerceAtLeast(1)
    if (history.size >= limit) return true
    history.addLast(currentMs)
    return false
}

private fun clearPlayerState(uuid: String) {
    pendingByUuid.remove(uuid)
    sessionByUuid.remove(uuid)
    sendHistoryByUuid.remove(uuid)
    lastContactByUuid.remove(uuid)
    lastContactByUuid.entries.removeIf { it.value == uuid }
}

private fun verifyByHelloFallback(player: Player, payload: Map<String, String>) {
    val currentMs = nowMs()
    val protocol = payload["pv"]?.toIntOrNull() ?: protocolVersion
    val modVersion = payload["mv"].orEmpty().ifEmpty { "unknown" }
    sessionByUuid[player.uuid()] = VerifiedSession(
        protocol = protocol,
        modVersion = modVersion,
        verifiedAtMs = currentMs,
        lastSeenMs = currentMs
    )
}

private fun handleHello(player: Player, raw: String) {
    val payload = decodePayload(raw)
    val protocol = payload["pv"]?.toIntOrNull()
    val modVersion = payload["mv"].orEmpty()
    val clientNonce = payload["cn"].orEmpty()

    if (protocol == null || protocol <= 0 || modVersion.isBlank() || clientNonce.length < 8) {
        return
    }

    val currentMs = nowMs()
    touchSession(player, currentMs)
    val serverNonce = randomNonce()
    pendingByUuid[player.uuid()] = PendingChallenge(
        protocol = protocol,
        modVersion = modVersion,
        clientNonce = clientNonce,
        serverNonce = serverNonce,
        expireAtMs = currentMs + challengeTtlMs()
    )

    val challengePayload = encodePayload(
        linkedMapOf(
            "pv" to protocolVersion.toString(),
            "sn" to serverNonce,
            "ttl" to challengeTtlSeconds.toString(),
            "ts" to currentMs.toString()
        )
    )
    val sent = sendClientPacket(player, packetChallenge, challengePayload)
    if (!sent) {
        // Fallback for environments where Call.clientPacketReliable is unavailable.
        verifyByHelloFallback(player, payload)
    }
}

private fun handleProve(player: Player, raw: String) {
    val payload = decodePayload(raw)
    val protocol = payload["pv"]?.toIntOrNull() ?: return
    val modVersion = payload["mv"].orEmpty()
    val serverNonce = payload["sn"].orEmpty()
    val signature = payload["sig"].orEmpty()
    if (modVersion.isBlank() || serverNonce.isBlank() || signature.isBlank()) return

    val currentMs = nowMs()
    val pending = pendingByUuid[player.uuid()] ?: return
    if (currentMs > pending.expireAtMs) {
        pendingByUuid.remove(player.uuid())
        sendClientPacket(player, packetError, encodePayload(mapOf("code" to "CHALLENGE_EXPIRED")))
        return
    }
    if (pending.serverNonce != serverNonce) {
        sendClientPacket(player, packetError, encodePayload(mapOf("code" to "NONCE_MISMATCH")))
        return
    }
    if (protocol != pending.protocol || modVersion != pending.modVersion) {
        sendClientPacket(player, packetError, encodePayload(mapOf("code" to "PROTO_OR_VERSION_MISMATCH")))
        return
    }

    val expected = expectedSignature(
        uuid = player.uuid(),
        protocol = protocol,
        modVersion = modVersion,
        clientNonce = pending.clientNonce,
        serverNonce = pending.serverNonce
    )
    if (!signature.equals(expected, ignoreCase = true)) {
        sendClientPacket(player, packetError, encodePayload(mapOf("code" to "SIGNATURE_INVALID")))
        return
    }

    pendingByUuid.remove(player.uuid())
    sessionByUuid[player.uuid()] = VerifiedSession(
        protocol = protocol,
        modVersion = modVersion,
        verifiedAtMs = currentMs,
        lastSeenMs = currentMs
    )
    sendClientPacket(
        player,
        packetOk,
        encodePayload(
            linkedMapOf(
                "pv" to protocolVersion.toString(),
                "ts" to currentMs.toString()
            )
        )
    )
}

private suspend fun CommandContext.openMenuFlow(sender: Player) {
    val candidates = Groups.player.toList().filter { it != sender }
    if (candidates.isEmpty()) {
        returnReply("[yellow]当前没有其他在线玩家".with())
    }

    var selected: Player? = null
    PagedMenuBuilder(candidates, prePage = 8) {
        val sid = PlayerData[it].shortId
        val verified = if (isVerified(it)) "[green]已认证[]" else "[scarlet]未认证[]"
        option("[white]${it.name} [gray](${sid})[] ${verified}") {
            selected = it
        }
    }.apply {
        title = "HiddenMessage 选择接收者"
        msg = "请选择玩家，随后在聊天框输入要发送的内容"
        sendTo(sender, 60_000)
    }

    val target = selected ?: return
    sender.sendMessage("[yellow]请在60秒内输入要发送给 {target.name}[yellow] 的消息，输入 /cancel 取消".with("target" to target))
    val text = nextChatApi.nextChat(sender, 60_000)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("/cancel", ignoreCase = true) }
    if (text == null) {
        sender.sendMessage("[yellow]已取消发送".with())
        return
    }
    deliverByShortId(sender, PlayerData[target].shortId, text)
}

private fun formatPrivateLine(sender: Player, senderShortId: String, message: String) =
    "[violet][HM][] [gray]{sid}[] {name}[white]: {msg}".with(
        "sid" to senderShortId,
        "name" to sender.name,
        "msg" to message
    )

private fun CommandContext.deliverToTarget(sender: Player, target: Player, targetShortId: String, messageRaw: String) {
    val message = messageRaw.trim()
    if (message.isEmpty()) {
        returnReply("[red]消息不能为空".with())
    }
    if (message.length > maxMessageLength.coerceAtLeast(1)) {
        returnReply(
            "[red]消息过长，当前限制 {max} 字符".with(
                "max" to maxMessageLength.coerceAtLeast(1)
            )
        )
    }

    val currentMs = nowMs()
    if (hitRateLimit(sender.uuid(), currentMs)) {
        returnReply("[red]发送过于频繁，请稍后再试".with())
    }
    touchSession(sender, currentMs)
    touchSession(target, currentMs)

    val senderShortId = PlayerData[sender].shortId

    // 核心功能由服务端完成：所有玩家都可收到并回复，不依赖客户端模组。
    target.sendMessage(formatPrivateLine(sender, senderShortId, message))
    target.sendMessage(
        "[gray]回复可用: /r <消息> 或 /hm {sid} <消息>".with("sid" to senderShortId)
    )

    // 客户端模组只做增强显示，不承担基础消息投递。
    if (isVerified(target, currentMs)) {
        sendClientPacket(
            target,
            packetDeliver,
            encodePayload(
                linkedMapOf(
                    "sid" to senderShortId,
                    "name" to sender.name,
                    "msg" to message,
                    "ts" to currentMs.toString()
                )
            )
        )
    }

    lastContactByUuid[sender.uuid()] = target.uuid()
    lastContactByUuid[target.uuid()] = sender.uuid()

    sender.sendMessage(
        "[violet][HM][] [gray]你 -> {sid} {target.name}[] [white]: {msg}".with(
            "sid" to targetShortId,
            "target" to target,
            "msg" to message
        )
    )
    sender.sendMessage(
        "[green]已发送给 {target.name}[gray]({sid})".with(
            "target" to target,
            "sid" to targetShortId
        )
    )

    if (logAudit) {
        logger.info("HM ${sender.uuid()}(${sender.name}) -> ${target.uuid()}(${target.name}) len=${message.length}")
    }
}

private fun CommandContext.deliverByShortId(sender: Player, shortIdRaw: String, messageRaw: String) {
    val shortId = shortIdRaw.trim()
    if (shortId.isEmpty()) {
        returnReply("[red]短ID不能为空".with())
    }

    val targetData = PlayerData.findByShortId(shortId)
        ?: returnReply("[red]目标不存在: {id}".with("id" to shortId))
    val target = targetData.player
        ?: returnReply("[red]目标离线: {id}".with("id" to shortId))

    if (target == sender) {
        returnReply("[red]不能给自己发送隐藏消息".with())
    }
    deliverToTarget(sender, target, targetData.shortId, messageRaw)
}

private fun buildStatusLine(player: Player): String {
    val session = sessionByUuid[player.uuid()]
    if (session == null) {
        return "[scarlet]未认证[]"
    }
    val verifiedTime = displayTimeFormatter.format(Instant.ofEpochMilli(session.verifiedAtMs))
    val stale = if (isVerified(player)) "[green]有效[]" else "[scarlet]过期[]"
    return "[green]已认证[] 协议=${session.protocol} 版本=${session.modVersion} 状态=$stale 校验时间=$verifiedTime"
}

onEnable {
    netServer.addPacketHandler(packetHello) { player, payload ->
        handleHello(player, payload)
    }
    netServer.addPacketHandler(packetProve) { player, payload ->
        handleProve(player, payload)
    }
}

onDisable {
    netServer.getPacketHandlers(packetHello).clear()
    netServer.getPacketHandlers(packetProve).clear()
    pendingByUuid.clear()
    sessionByUuid.clear()
    sendHistoryByUuid.clear()
}

listen<EventType.PlayerLeave> {
    clearPlayerState(it.player.uuid())
}

command("hm", "发送隐藏消息(/hm <短ID> <消息>)".with()) {
    usage = "[短ID] [消息...]"
    requirePermission("determination.hm.use")
    body {
        val sender = player ?: returnReply("[red]仅玩家可用".with())
        if (arg.isEmpty()) {
            openMenuFlow(sender)
            return@body
        }
        if (arg.size < 2) {
            returnReply("[red]参数错误: /hm <短ID> <消息...>".with())
        }
        val shortId = arg[0]
        val message = arg.drop(1).joinToString(" ")
        deliverByShortId(sender, shortId, message)
    }
}

command("r", "回复最近私信对象".with()) {
    usage = "<消息...>"
    aliases = listOf("reply", "hmr")
    requirePermission("determination.hm.use")
    body {
        val sender = player ?: returnReply("[red]仅玩家可用".with())
        if (arg.isEmpty()) returnReply("[red]参数错误: /r <消息...>".with())

        val targetUuid = lastContactByUuid[sender.uuid()]
            ?: returnReply("[yellow]暂无最近私信对象，请先使用 /hm".with())
        val target = Groups.player.find { it.uuid() == targetUuid }
            ?: returnReply("[red]最近私信对象已离线".with())
        val targetShortId = PlayerData[target].shortId
        val message = arg.joinToString(" ")
        deliverToTarget(sender, target, targetShortId, message)
    }
}

command("hmstatus", "查看隐藏消息握手状态".with()) {
    usage = "[短ID]"
    requirePermission("determination.hm.use")
    body {
        if (arg.isEmpty()) {
            val sender = player ?: returnReply("[yellow]控制台无玩家会话状态".with())
            reply(
                "[white]你的 hiddenMessage 状态: {status}".with(
                    "status" to buildStatusLine(sender)
                )
            )
            return@body
        }

        val target = PlayerData.findByShortId(arg[0])?.player
            ?: returnReply("[red]目标不存在或离线: {id}".with("id" to arg[0]))
        reply(
            "[white]{target.name}[white] 状态: {status}".with(
                "target" to target,
                "status" to buildStatusLine(target)
            )
        )
    }
}

command("hmreload", "重置 hiddenMessage 会话状态".with()) {
    requirePermission("determination.hm.admin")
    body {
        pendingByUuid.clear()
        sessionByUuid.clear()
        sendHistoryByUuid.clear()
        returnReply("[green]hiddenMessage 会话缓存已清空".with())
    }
}

PermissionApi.registerDefault("determination.hm.use")
PermissionApi.registerDefault("determination.hm.admin", group = "@admin")
