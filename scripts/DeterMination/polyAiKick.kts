@file:Depends("DeterMination")
@file:Depends("wayzer")
@file:Depends("wayzer/vote")
@file:Depends("wayzer/user/ban")
@file:Depends("wayzer/user/shortID")
@file:Depends("coreMindustry/utilTextInput")
@file:Depends("coreMindustry/menu")

package DeterMination

import coreLibrary.lib.PermissionApi
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.registerActionFilter
import arc.util.Time
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import mindustry.world.Tile
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.user.Ban
import kotlin.math.exp
import kotlin.math.roundToInt

name = "PolyAiKick"

private data class PolySuspectState(
    var score: Double = 0.0,
    var lastUpdate: Long = System.currentTimeMillis(),
    var lastHit: Long = 0L,
    var edgeHits: Int = 0,
    var totalActions: Int = 0,
    var streak: Int = 0,
    var lastEdgeHit: Long = 0L,
)

private data class PolyCandidate(
    val player: Player,
    val score: Double,
    val edgeHits: Int,
    val totalActions: Int,
    val lastAgoSec: Long,
)

private data class PolyConfidence(
    val player: Player,
    val confidence: Double,
    val score: Double,
    val edgeHits: Int,
    val totalActions: Int,
    val lastAgoSec: Long,
)

private data class PolyPendingBuild(
    val ownerUuid: String,
    val placedAt: Long,
)

private data class PolyPlanPatternState(
    var matchedMs: Long = 0L,
    var planMoveMs: Long = 0L,
    var noPlanStillMs: Long = 0L,
    var seenPlanMove: Boolean = false,
    var seenNoPlanStill: Boolean = false,
    var lastSampleAt: Long = 0L,
    var lastBoostAt: Long = 0L,
)

private val polyDetectMinScore by config.key(8.0, "kp判定阈值(分)")
private val polyDetectActiveSeconds by config.key(90, "kp活跃窗口(秒)")
private val polyDetectDecayPerSecond by config.key(0.9, "kp分值衰减(每秒)")
private val polyDetectEdgeRatioMin by config.key(0.75f, "kp边缘距离比例下限")
private val polyDetectEdgeRatioMax by config.key(1.08f, "kp边缘距离比例上限")
private val polyDetectNearRatioMax by config.key(0.45f, "kp近距离比例上限")
private val polyDetectComboSeconds by config.key(8, "kp边缘连击判定窗口(秒)")
private val polyDetectSameTileRepeatThreshold by config.key(4, "kp同位置未完成被打掉次数阈值(>该值强嫌疑)")
private val polyDetectUnfinishedDestroySeconds by config.key(20, "kp建造后在多少秒内被打掉视为未完成")
private val polyDetectSameTileExpireSeconds by config.key(180, "kp同位置重复统计过期时间(秒)")
private val polyDetectPlanMoveVelocityMin by config.key(0.08f, "kp规划移动判定最小速度")
private val polyDetectPlanMoveNeedSeconds by config.key(3, "kp规划时移动累计秒数阈值")
private val polyDetectNoPlanStillNeedSeconds by config.key(3, "kp无规划时静止累计秒数阈值")
private val polyDetectPlanPatternNeedSeconds by config.key(6, "kp规划移动+无规划静止总累计秒数阈值")
private val polyDetectPlanPatternCooldownSeconds by config.key(120, "kp规划行为强嫌疑加分冷却(秒)")
private val kpBanMinutesDefault by config.key(10, "kp普通目标封禁时长(分钟)")
private val kpBanMinutesAdminTarget by config.key(1, "kp管理员目标封禁时长(分钟)")

private val polySuspects = mutableMapOf<String, PolySuspectState>()
private val pendingPolyBuildByTile = mutableMapOf<Long, PolyPendingBuild>()
private val sameTileDestroyedCount = mutableMapOf<String, Int>()
private val sameTileLastAt = mutableMapOf<String, Long>()
private val planPatternByUuid = mutableMapOf<String, PolyPlanPatternState>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()
private val banImpl = contextScript<Ban>()

private fun Double.format1(): String = ((this * 10.0).roundToInt() / 10.0).toString()

private fun PolySuspectState.decay(now: Long) {
    val deltaSec = ((now - lastUpdate).coerceAtLeast(0L)) / 1000.0
    if (deltaSec <= 0.0) return
    score = (score - deltaSec * polyDetectDecayPerSecond).coerceAtLeast(0.0)
    lastUpdate = now
}

private fun Player.displayNameForVote() = name
private fun Player.displayShortIdForVote(): String = runCatching { PlayerData[this].shortId }.getOrElse { uuid().take(3) }

private fun PolyCandidate.summary() =
    "分${score.format1()} 边缘命中${edgeHits}/${totalActions.coerceAtLeast(1)} 最近${lastAgoSec}s"

private fun tileKey(tile: Tile): Long =
    (tile.x.toLong() shl 32) xor (tile.y.toLong() and 0xffffffffL)

private fun sameTileKey(uuid: String, key: Long): String = "$uuid@$key"

private fun raiseSameTileSuspicion(uuid: String, tileKey: Long, now: Long) {
    val key = sameTileKey(uuid, tileKey)
    val expireMs = polyDetectSameTileExpireSeconds.coerceAtLeast(1) * 1000L
    val last = sameTileLastAt[key]
    val count = if (last == null || now - last > expireMs) 1 else (sameTileDestroyedCount[key] ?: 0) + 1
    sameTileDestroyedCount[key] = count
    sameTileLastAt[key] = now
    if (count <= polyDetectSameTileRepeatThreshold) return

    val state = polySuspects.getOrPut(uuid) { PolySuspectState(lastUpdate = now) }
    state.decay(now)
    state.totalActions += 1
    state.lastHit = now
    val over = count - polyDetectSameTileRepeatThreshold
    val boost = 4.0 + over.coerceAtMost(6) * 2.0
    state.score += boost
}

private fun registerUnfinishedDestroy(tile: Tile, now: Long) {
    val key = tileKey(tile)
    val pending = pendingPolyBuildByTile.remove(key) ?: return
    val maxAgeMs = polyDetectUnfinishedDestroySeconds.coerceAtLeast(1) * 1000L
    if (now - pending.placedAt > maxAgeMs) return
    raiseSameTileSuspicion(pending.ownerUuid, key, now)
}

private fun registerPolyPlace(player: Player, tile: Tile, now: Long) {
    pendingPolyBuildByTile[tileKey(tile)] = PolyPendingBuild(player.uuid(), now)
}

private fun clearPlayerPolyState(uuid: String) {
    polySuspects.remove(uuid)
    pendingPolyBuildByTile.entries.removeIf { it.value.ownerUuid == uuid }
    sameTileDestroyedCount.keys.removeIf { it.startsWith("$uuid@") }
    sameTileLastAt.keys.removeIf { it.startsWith("$uuid@") }
    planPatternByUuid.remove(uuid)
}

private fun PolyPlanPatternState.resetPattern(now: Long) {
    matchedMs = 0L
    planMoveMs = 0L
    noPlanStillMs = 0L
    seenPlanMove = false
    seenNoPlanStill = false
    lastSampleAt = now
}

private fun secondsToMs(seconds: Int): Long = seconds.coerceAtLeast(1).toLong() * 1000L

private fun trackPlanPattern(player: Player) {
    val unit = player.unit() ?: return
    if (unit.type != UnitTypes.poly) return

    val now = System.currentTimeMillis()
    val state = planPatternByUuid.getOrPut(player.uuid()) { PolyPlanPatternState(lastSampleAt = now) }
    if (state.lastSampleAt <= 0L) {
        state.lastSampleAt = now
        return
    }
    val deltaMs = (now - state.lastSampleAt).coerceIn(0L, 1000L)
    state.lastSampleAt = now
    if (deltaMs <= 0L) return

    val hasTeamPlans = !player.team().data().plans.isEmpty
    val velocityMin = polyDetectPlanMoveVelocityMin.coerceAtLeast(0.01f)
    val moving = unit.vel.len2() >= velocityMin * velocityMin

    val patternMatch = (hasTeamPlans && moving) || (!hasTeamPlans && !moving)
    if (!patternMatch) {
        state.resetPattern(now)
        return
    }

    state.matchedMs += deltaMs
    if (hasTeamPlans && moving) {
        state.planMoveMs += deltaMs
        state.seenPlanMove = true
    } else {
        state.noPlanStillMs += deltaMs
        state.seenNoPlanStill = true
    }

    if (!state.seenPlanMove || !state.seenNoPlanStill) return
    if (state.planMoveMs < secondsToMs(polyDetectPlanMoveNeedSeconds)) return
    if (state.noPlanStillMs < secondsToMs(polyDetectNoPlanStillNeedSeconds)) return
    if (state.matchedMs < secondsToMs(polyDetectPlanPatternNeedSeconds)) return

    val cooldownMs = secondsToMs(polyDetectPlanPatternCooldownSeconds)
    if (now - state.lastBoostAt < cooldownMs) return
    state.lastBoostAt = now
    state.resetPattern(now)

    val suspect = polySuspects.getOrPut(player.uuid()) { PolySuspectState(lastUpdate = now) }
    suspect.decay(now)
    suspect.totalActions += 1
    suspect.lastHit = now
    suspect.score += 100.0
}

private fun trackPolySuspect(action: Administration.PlayerAction) {
    val player = action.player ?: return
    val now = System.currentTimeMillis()
    val state = polySuspects.getOrPut(player.uuid()) { PolySuspectState(lastUpdate = now) }
    state.decay(now)

    when (action.type) {
        Administration.ActionType.commandUnits -> {
            val hasPoly = action.unitIDs?.any { id ->
                Groups.unit.getByID(id)?.type == UnitTypes.poly
            } == true
            if (hasPoly) {
                state.totalActions += 1
                state.score += 1.2
                state.lastHit = now
            }
            return
        }

        Administration.ActionType.placeBlock, Administration.ActionType.breakBlock -> Unit
        else -> return
    }

    if (action.type == Administration.ActionType.breakBlock) {
        action.tile?.let { registerUnfinishedDestroy(it, now) }
    }

    val unit = player.unit() ?: return
    if (unit.type != UnitTypes.poly) return
    val tile = action.tile ?: return
    if (action.type == Administration.ActionType.placeBlock) {
        registerPolyPlace(player, tile, now)
    }

    val buildRange = (unit.type.buildRange - unit.type.hitSize * 2f).coerceAtLeast(1f)
    val distance = unit.dst(tile.worldx(), tile.worldy())
    val ratio = distance / buildRange

    state.totalActions += 1
    state.lastHit = now
    state.score += 1.15

    if (ratio in polyDetectEdgeRatioMin..polyDetectEdgeRatioMax) {
        state.edgeHits += 1
        state.score += 2.0
        if (now - state.lastEdgeHit <= polyDetectComboSeconds * 1000L) {
            state.streak += 1
            state.score += 0.4 + state.streak.coerceAtMost(4) * 0.2
        } else {
            state.streak = 1
        }
        state.lastEdgeHit = now
    } else {
        state.streak = 0
        if (ratio <= polyDetectNearRatioMax) {
            state.score -= 0.8
        }
    }
    if (state.score < 0.0) state.score = 0.0
}

private fun buildPolyCandidate(player: Player, now: Long): PolyCandidate? {
    val state = polySuspects[player.uuid()] ?: return null
    state.decay(now)
    val lastAgo = ((now - state.lastHit).coerceAtLeast(0L)) / 1000L
    if (lastAgo > polyDetectActiveSeconds) return null
    if (state.score < polyDetectMinScore) return null
    return PolyCandidate(player, state.score, state.edgeHits, state.totalActions, lastAgo)
}

private fun collectPolyCandidates(now: Long = System.currentTimeMillis()): List<PolyCandidate> =
    Groups.player.mapNotNull { buildPolyCandidate(it, now) }.sortedByDescending { it.score }

private fun findPolyCandidate(player: Player, now: Long = System.currentTimeMillis()): PolyCandidate? =
    buildPolyCandidate(player, now)

private fun buildPolyConfidence(player: Player, now: Long): PolyConfidence {
    val state = polySuspects[player.uuid()]
    if (state == null) return PolyConfidence(player, 0.0, 0.0, 0, 0, Long.MAX_VALUE)
    state.decay(now)
    val lastAgo = if (state.lastHit <= 0L) Long.MAX_VALUE else ((now - state.lastHit).coerceAtLeast(0L)) / 1000L
    if (lastAgo > polyDetectActiveSeconds || state.score <= 0.0 || state.totalActions <= 0) {
        return PolyConfidence(player, 0.0, state.score, state.edgeHits, state.totalActions, lastAgo)
    }

    val scoreSignal = 1.0 / (1.0 + exp(-(state.score - polyDetectMinScore) / 2.0))
    val edgeSignal = (state.edgeHits.toDouble() / state.totalActions.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
    val freshSignal = (1.0 - (lastAgo.toDouble() / polyDetectActiveSeconds.toDouble())).coerceIn(0.0, 1.0)
    val confidence = ((scoreSignal * 0.7 + edgeSignal * 0.2 + freshSignal * 0.1) * 100.0).coerceIn(0.0, 99.9)

    return PolyConfidence(player, confidence, state.score, state.edgeHits, state.totalActions, lastAgo)
}

private fun collectPolyConfidences(now: Long = System.currentTimeMillis()): List<PolyConfidence> =
    Groups.player.map { buildPolyConfidence(it, now) }.sortedByDescending { it.confidence }

private fun CommandContext.readArg(): String? = arg.firstOrNull().also { arg = arg.drop(1) }

private suspend fun CommandContext.getTarget(): Player {
    val id = readArg()
    if (id == null) {
        val viewer = player ?: returnReply("[red]请输入玩家名/三位id".with())
        var result: Player? = null
        PagedMenuBuilder(Groups.player.toList()) {
            option(it.name) { result = it }
        }.apply {
            title = "选择目标玩家"
            sendTo(viewer, 60_000)
        }
        return result ?: returnReply("[yellow]已取消选择".with())
    }

    if (id.startsWith("#")) {
        Groups.player.getByID(id.substring(1).toIntOrNull() ?: 0)?.let { return it }
    }

    val allPlayers = Groups.player.associateBy { it.name.replace(" ", "") }
    for (addLen in 0..arg.size) {
        val argAsName = id + arg.take(addLen).joinToString("")
        val found = allPlayers[argAsName] ?: continue
        arg = arg.drop(addLen)
        return found
    }

    return PlayerData.findByShortId(id)?.player
        ?: returnReply("[red]请输入正确的玩家名".with())
}

private suspend fun CommandContext.getRequiredInput(name: String, whenEmpty: VarString): String {
    return arg.takeIf { it.isNotEmpty() }?.joinToString(" ")
        ?: player?.let { p ->
            (textInput.textInput(p, "请在60s内输入$name") ?: returnReply("[yellow]已取消输入".with()))
                .takeIf { it.isNotBlank() }
        } ?: returnReply(whenEmpty)
}

private suspend fun CommandContext.pickPolyTarget(candidates: List<PolyCandidate>, title: String): Player {
    val viewer = player ?: returnReply("[red]仅玩家可执行该命令".with())
    var result: Player? = null
    PagedMenuBuilder(candidates) {
        val p = it.player
        option(
            "{name} [gray]({id} | 分{score} | {last}s)".with(
                "name" to p.displayNameForVote(),
                "id" to p.displayShortIdForVote(),
                "score" to it.score.format1(),
                "last" to it.lastAgoSec
            ).toString()
        ) { result = p }
    }.apply {
        this.title = title
        sendTo(viewer, 60_000)
    }
    return result ?: returnReply("[yellow]已取消选择".with())
}

private suspend fun resolveBanMinutes(target: Player): Int {
    return if (target.hasPermission("wayzer.admin.skipKick")) {
        kpBanMinutesAdminTarget.coerceAtLeast(1)
    } else {
        kpBanMinutesDefault.coerceAtLeast(1)
    }
}

registerActionFilter {
    trackPolySuspect(it)
    true
}

listen(EventType.Trigger.update) {
    if (state.gameOver) return@listen
    if (Time.delta <= 0f) return@listen
    Groups.player.forEach { trackPlanPattern(it) }
}

listen<EventType.PlayerLeave> {
    clearPlayerPolyState(it.player.uuid())
}

listen<EventType.BlockDestroyEvent> {
    registerUnfinishedDestroy(it.tile, System.currentTimeMillis())
}

listen<EventType.BlockBuildEndEvent> {
    if (it.breaking) return@listen
    val key = tileKey(it.tile)
    val pending = pendingPolyBuildByTile.remove(key) ?: return@listen
    val repeatKey = sameTileKey(pending.ownerUuid, key)
    sameTileDestroyedCount.remove(repeatKey)
    sameTileLastAt.remove(repeatKey)
}

listen<EventType.ResetEvent> {
    polySuspects.clear()
    pendingPolyBuildByTile.clear()
    sameTileDestroyedCount.clear()
    sameTileLastAt.clear()
    planPatternByUuid.clear()
}

command("kickpolyai", "投票踢出疑似PolyAI".with(), commands = VoteEvent.VoteCommands) {
    aliases = listOf("kp")
    usage = "[玩家名/id] <理由>"
    requirePermission("determination.vote.kp")
    body {
        val firstArg = readArg()
        val target = if (firstArg == null) {
            val candidates = collectPolyCandidates()
            if (candidates.isEmpty()) {
                return@body reply("[yellow]当前没有可投票的PolyAI嫌疑目标".with())
            }
            pickPolyTarget(candidates, "选择PolyAI嫌疑目标(${candidates.size})")
        } else {
            arg = listOf(firstArg) + arg
            getTarget()
        }

        val candidate = findPolyCandidate(target)
            ?: return@body reply("[yellow]目标当前未达到PolyAI嫌疑阈值".with())

        val reason = getRequiredInput("踢人理由", "[red]投票踢人需要理由".with())
        val starter = player ?: returnReply("[red]仅玩家可发起此投票".with())
        val event = VoteEvent(
            thisScript, starter,
            voteDesc = "踢人(疑似PolyAI: [red]{target}[yellow])".with("target" to target),
            extDesc = "[red]理由: [yellow]$reason\n[lightgray]嫌疑: ${candidate.summary()}"
        )
        if (!event.awaitResult()) return@body

        val banMinutes = resolveBanMinutes(target)
        banImpl.ban(PlayerData[target], banMinutes, "vote kp: $reason", starter)
    }
}

command("kp", "管理指令: 处理疑似PolyAI") {
    usage = "[玩家名/id] [理由]"
    requirePermission("determination.admin.kp")
    body {
        val firstArg = readArg()
        val target = if (firstArg == null) {
            val candidates = collectPolyCandidates()
            if (candidates.isEmpty()) {
                return@body reply("[yellow]当前没有可处理的PolyAI嫌疑目标".with())
            }
            pickPolyTarget(candidates, "管理处理PolyAI(${candidates.size})")
        } else {
            arg = listOf(firstArg) + arg
            getTarget()
        }

        val candidate = findPolyCandidate(target)
            ?: return@body reply("[yellow]目标当前未达到PolyAI嫌疑阈值".with())

        val reason = arg.joinToString(" ").ifBlank { "管理处理疑似PolyAI (${candidate.summary()})" }
        val banMinutes = resolveBanMinutes(target)
        banImpl.ban(PlayerData[target], banMinutes, "admin kp: $reason", player)
        reply(
            "[green]已处理 {name}[green]({id})，封禁 {minutes} 分钟".with(
                "name" to target.displayNameForVote(),
                "id" to target.displayShortIdForVote(),
                "minutes" to banMinutes
            )
        )
    }
}

command("polyai", "查询当前在线玩家 PolyAI 置信度".with()) {
    aliases = listOf("polyprob", "kpconf")
    usage = "[最多显示人数,默认20]"
    requirePermission("determination.query.polyai")
    body {
        val limit = arg.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val list = collectPolyConfidences()
        if (list.isEmpty()) return@body reply("[yellow]当前没有在线玩家".with())

        val lines = list.take(limit).mapIndexed { idx, item ->
            val p = item.player
            val confidenceText = "${item.confidence.format1()}%"
            val lastText = if (item.lastAgoSec == Long.MAX_VALUE) "-" else "${item.lastAgoSec}s"
            val edgeText = "${item.edgeHits}/${item.totalActions.coerceAtLeast(1)}"
            "{idx}. {name} [gray]({id})[] [accent]{conf}[] [gray]分{score} 边缘{edge} 最近{last}".with(
                "idx" to (idx + 1),
                "name" to p.displayNameForVote(),
                "id" to p.displayShortIdForVote(),
                "conf" to confidenceText,
                "score" to item.score.format1(),
                "edge" to edgeText,
                "last" to lastText
            )
        }
        reply(
            "[yellow]PolyAI 置信度列表(在线{total}人, 显示前{count}):[]\n{lines|joinLines}".with(
                "total" to list.size,
                "count" to minOf(limit, list.size),
                "lines" to lines
            )
        )
    }
}

PermissionApi.registerDefault("determination.vote.kp")
PermissionApi.registerDefault("determination.admin.kp", group = "@admin")
PermissionApi.registerDefault("determination.query.polyai")
