@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/ban", "禁封实现")
@file:Depends("wayzer/user/shortID", "3位ID解析")

package DeterMination

import arc.math.geom.Point2
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.PermissionApi
import coreMindustry.lib.registerActionFilter
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.entities.Damage
import mindustry.gen.Building
import mindustry.gen.Builderc
import mindustry.gen.Groups
import mindustry.net.Administration
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.user.Ban
import java.util.ArrayDeque

private enum class ActionKind { Place, Break, Rotate, Config }

private data class TileSnapshot(
    val array: Int,
    val block: Block,
    val team: Team,
    val rotation: Int,
    val config: Any?,
)

private data class ActionRecord(
    val uuid: String,
    val timeMs: Long,
    val kind: ActionKind,
    val array: Int,
    val before: TileSnapshot,
    val after: TileSnapshot,
)

private data class ConfigPending(val beforeConfig: Any?, val timeMs: Long)

private data class RollbackResult(
    var matched: Int = 0,
    var processed: Int = 0,
    var success: Int = 0,
    var failed: Int = 0,
    var skipped: Int = 0,
    var truncated: Boolean = false,
    val failedDetails: MutableList<String> = mutableListOf(),
)

private val defaultRollbackMinutes by config.key(5, "回滚默认分钟数")
private val maxRollbackMinutes by config.key(30, "回滚最大分钟数")
private val kickBanMinutes by config.key(60, "kickGriefers封禁时长(分钟)")
private val maxRecordsPerPlayer by config.key(500, "每个玩家最多缓存的记录数")
private val recordTtlMinutes by config.key(30, "缓存记录保留时长(分钟)")
private val maxRollbackActionsPerRun by config.key(1500, "单次最多回滚动作数")
private val enableRotateRollback by config.key(true, "是否回滚旋转操作")
private val enableConfigRollback by config.key(true, "是否回滚配置操作")
private val suppressSecondsAfterRollback by config.key(6, "回滚后目标禁建秒数")
private val forceClearLockSeconds by config.key(12, "回滚后目标地块锁定秒数")
private val useExplosionFallback by config.key(true, "残留建造中时是否启用定点爆炸兜底")
private val fallbackExplosionTileRadius by config.key(1f, "残留兜底爆炸半径(格)")
private val fallbackExplosionDamage by config.key(999f, "残留兜底爆炸伤害")

private val recordsByUuid = mutableMapOf<String, ArrayDeque<ActionRecord>>()
private val preTileSnapshot = mutableMapOf<Int, TileSnapshot>()
private val pendingConfigByPlayerPos = mutableMapOf<Pair<String, Int>, ArrayDeque<ConfigPending>>()
private val suppressActionUntil = mutableMapOf<String, Long>()
private val suppressTipAt = mutableMapOf<String, Long>()
private val forceClearUntilByTile = mutableMapOf<Int, Long>()
private var rollbackInProgress = false
private val banImpl = contextScript<Ban>()

private fun <T> ArrayDeque<T>.pollFirstOrNull(): T? =
    if (isEmpty()) null else removeFirst()

private fun cloneConfig(value: Any?): Any? {
    return when (value) {
        null -> null
        is ByteArray -> value.copyOf()
        is ShortArray -> value.copyOf()
        is IntArray -> value.copyOf()
        is LongArray -> value.copyOf()
        is FloatArray -> value.copyOf()
        is DoubleArray -> value.copyOf()
        is BooleanArray -> value.copyOf()
        is CharArray -> value.copyOf()
        is Point2 -> value.cpy()
        is Array<*> -> {
            if (value.all { it is Point2 }) {
                value.map { (it as Point2).cpy() }.toTypedArray()
            } else {
                value.copyOf()
            }
        }

        else -> value
    }
}

private fun safeBlockConfig(build: Building?): Any? {
    if (build == null) return null
    return runCatching { cloneConfig(build.config()) }.getOrNull()
}

private fun captureSnapshot(tile: Tile): TileSnapshot {
    val build = tile.build
    return TileSnapshot(
        array = tile.array(),
        block = tile.block(),
        team = tile.team(),
        rotation = build?.rotation ?: 0,
        config = safeBlockConfig(build)
    )
}

private fun arrayToTile(array: Int): Tile? {
    if (array < 0) return null
    val width = world.width()
    if (width <= 0) return null
    val x = array % width
    val y = array / width
    if (x < 0 || y < 0 || x >= width || y >= world.height()) return null
    return world.tile(x, y)
}

private fun collectBlockFootprintArrays(centerTile: Tile, block: Block, output: MutableSet<Int>) {
    val size = block.size.coerceAtLeast(1)
    if (size <= 1) {
        output += centerTile.array()
        return
    }
    val offset = block.sizeOffset
    for (dx in 0 until size) {
        for (dy in 0 until size) {
            val other = world.tile(centerTile.x + dx + offset, centerTile.y + dy + offset) ?: continue
            output += other.array()
        }
    }
    if (output.isEmpty()) output += centerTile.array()
}

private fun touchedArraysForRecord(record: ActionRecord): Set<Int> {
    val centerTile = arrayToTile(record.array) ?: return setOf(record.array)
    val touched = linkedSetOf<Int>()
    collectBlockFootprintArrays(centerTile, record.before.block, touched)
    collectBlockFootprintArrays(centerTile, record.after.block, touched)
    if (touched.isEmpty()) touched += record.array
    return touched
}

private fun cleanupPendingConfig(now: Long = System.currentTimeMillis()) {
    val expireMs = 15_000L
    pendingConfigByPlayerPos.entries.removeIf { (_, queue) ->
        while (true) {
            val first = queue.peekFirst() ?: break
            if (now - first.timeMs <= expireMs) break
            queue.removeFirst()
        }
        queue.isEmpty()
    }
}

private fun prunePlayerRecords(uuid: String, now: Long = System.currentTimeMillis()) {
    val queue = recordsByUuid[uuid] ?: return
    val ttlMs = recordTtlMinutes.coerceAtLeast(1).toLong() * 60_000L
    while (true) {
        val first = queue.peekFirst() ?: break
        if (now - first.timeMs <= ttlMs) break
        queue.removeFirst()
    }
    val keepLimit = maxRecordsPerPlayer.coerceAtLeast(1)
    while (queue.size > keepLimit) {
        queue.removeFirst()
    }
    if (queue.isEmpty()) {
        recordsByUuid.remove(uuid)
    }
}

private fun addRecord(record: ActionRecord) {
    val queue = recordsByUuid.getOrPut(record.uuid) { ArrayDeque() }
    queue.addLast(record)
    prunePlayerRecords(record.uuid, record.timeMs)
}

private fun applyConfigRollback(tile: Tile, config: Any?): Boolean {
    if (!enableConfigRollback) return false
    val build = tile.build ?: return false
    return runCatching {
        build.configureAny(cloneConfig(config))
    }.isSuccess
}

private fun cleanupForceClear(now: Long = System.currentTimeMillis()) {
    forceClearUntilByTile.entries.removeIf { (_, until) -> until <= now }
}

private fun markForceClear(arrays: Set<Int>) {
    val sec = forceClearLockSeconds.coerceAtLeast(0)
    if (sec <= 0) return
    val until = System.currentTimeMillis() + sec * 1000L
    arrays.forEach { array ->
        forceClearUntilByTile[array] = until
    }
}

private fun isForceClearLocked(array: Int, now: Long = System.currentTimeMillis()): Boolean {
    cleanupForceClear(now)
    val until = forceClearUntilByTile[array] ?: return false
    return until > now
}

private fun clearPlanAt(tile: Tile) {
    for (team in Team.all) {
        team.data().plans.removeAll { it.x == tile.x && it.y == tile.y }
    }
}

private fun clearUnitBuildPlanAt(tile: Tile) {
    Groups.unit.each { unit ->
        val builder = unit as? Builderc ?: return@each
        val x = tile.x.toInt()
        val y = tile.y.toInt()
        runCatching { builder.removeBuild(x, y, true) }
        runCatching { builder.removeBuild(x, y, false) }
    }
}

private fun forceClearTile(tile: Tile, forceAir: Boolean = false): String? {
    return runCatching<String?> {
        val details = mutableListOf<String>()
        clearPlanAt(tile)
        clearUnitBuildPlanAt(tile)

        val shouldAttemptRemove = forceAir || tile.build is ConstructBlock.ConstructBuild
        if (shouldAttemptRemove && (tile.block() != Blocks.air || tile.build != null)) {
            runCatching { tile.removeNet() }
                .onFailure { details += "removeNet=${it.message ?: it::class.simpleName}" }
        }
        if (forceAir && tile.block() != Blocks.air) {
            runCatching { tile.setNet(Blocks.air, Team.derelict, 0) }
                .onFailure { details += "setAir=${it.message ?: it::class.simpleName}" }
        }

        if (tile.build is ConstructBlock.ConstructBuild && useExplosionFallback) {
            repeat(2) { idx ->
                runCatching {
                    // Building-only damage fallback: tileDamage does not affect units.
                    Damage.tileDamage(
                        Team.derelict,
                        tile.x.toInt(),
                        tile.y.toInt(),
                        fallbackExplosionTileRadius.coerceAtLeast(0.5f),
                        fallbackExplosionDamage.coerceAtLeast(1f)
                    )
                }.onFailure { details += "fallbackExplosion#$idx=${it.message ?: it::class.simpleName}" }
            }
            clearPlanAt(tile)
            clearUnitBuildPlanAt(tile)
        } else if (tile.build is ConstructBlock.ConstructBuild) {
            details += "fallbackExplosionDisabled"
        }

        if (tile.build is ConstructBlock.ConstructBuild) {
            val cb = tile.build as ConstructBlock.ConstructBuild
            val reason = "still ConstructBuild(current=${cb.current.name},progress=${cb.progress})"
            if (details.isEmpty()) reason else "$reason, detail=${details.joinToString(";")}"
        } else {
            null
        }
    }.getOrElse { it.message ?: it::class.simpleName ?: "未知错误" }
}

private fun clearOnlineBuilderQueue(target: PlayerData) {
    val player = target.player ?: return
    val unit = player.unit() ?: return
    val builder = unit as? Builderc ?: return
    runCatching { builder.clearBuilding() }
}

private fun suppressPlayerAction(uuid: String) {
    val sec = suppressSecondsAfterRollback.coerceAtLeast(0)
    if (sec <= 0) return
    suppressActionUntil[uuid] = System.currentTimeMillis() + sec * 1000L
}

private fun isActionBlocked(type: Administration.ActionType): Boolean {
    return when (type) {
        Administration.ActionType.placeBlock,
        Administration.ActionType.breakBlock,
        Administration.ActionType.rotate,
        Administration.ActionType.buildSelect,
        Administration.ActionType.removePlanned,
        Administration.ActionType.pickupBlock,
        Administration.ActionType.dropPayload,
        Administration.ActionType.configure -> true

        else -> false
    }
}

private fun verifyResidue(arrays: Set<Int>, result: RollbackResult) {
    arrays.forEach { arr ->
        val tile = arrayToTile(arr) ?: return@forEach
        if (tile.build !is ConstructBlock.ConstructBuild) return@forEach
        val err = forceClearTile(tile)
        if (tile.build is ConstructBlock.ConstructBuild) {
            result.failed++
            result.failedDetails += "- residue-construct (${tile.x},${tile.y}) reason=${err ?: "construct not cleared"}"
        }
    }
}

private fun clearBuildersOnTouchedTiles(arrays: Set<Int>) {
    arrays.forEach { arr ->
        val tile = arrayToTile(arr) ?: return@forEach
        clearPlanAt(tile)
        clearUnitBuildPlanAt(tile)
    }
}

private fun restoreBlock(snapshot: TileSnapshot): String? {
    val tile = arrayToTile(snapshot.array) ?: return "tile不存在"
    return runCatching<String?> {
        if (snapshot.block == Blocks.air) {
            return@runCatching forceClearTile(tile, forceAir = true)
        }
        tile.setNet(snapshot.block, snapshot.team, snapshot.rotation.coerceIn(0, 3))
        if (enableConfigRollback && snapshot.config != null) {
            if (!applyConfigRollback(tile, snapshot.config)) {
                return@runCatching "配置恢复失败"
            }
        }
        null
    }.getOrElse { it.message ?: it::class.simpleName ?: "未知错误" }
}

private fun restoreRotation(snapshot: TileSnapshot): String? {
    val tile = arrayToTile(snapshot.array) ?: return "tile不存在"
    val build = tile.build
    // If target tile is in residual construct state (e.g. air building), force full block restore.
    if (build is ConstructBlock.ConstructBuild) {
        return restoreBlock(snapshot)
    }
    if (build == null) {
        return restoreBlock(snapshot)
    }
    if (tile.block() != snapshot.block) {
        return restoreBlock(snapshot)
    }
    if (!build.block.rotate) {
        return restoreBlock(snapshot)
    }
    return runCatching {
        build.rotation = snapshot.rotation.coerceIn(0, 3)
        build.updateProximity()
        build.noSleep()
        null
    }.getOrElse { it.message ?: it::class.simpleName ?: "未知错误" }
}

private fun executeRecord(record: ActionRecord): String? {
    return when (record.kind) {
        ActionKind.Place, ActionKind.Break -> restoreBlock(record.before)
        ActionKind.Rotate -> restoreRotation(record.before)
        ActionKind.Config -> {
            val tile = arrayToTile(record.array) ?: return "tile不存在"
            if (applyConfigRollback(tile, record.before.config)) null else "配置恢复失败"
        }
    }
}

private fun kindName(kind: ActionKind) = when (kind) {
    ActionKind.Place -> "放置"
    ActionKind.Break -> "拆除"
    ActionKind.Rotate -> "旋转"
    ActionKind.Config -> "配置"
}

private fun failureLine(record: ActionRecord, reason: String): String {
    val width = world.width().coerceAtLeast(1)
    val x = record.array % width
    val y = record.array / width
    return "- ${kindName(record.kind)} (${x},${y}) before=${record.before.block.name} after=${record.after.block.name} reason=${reason}"
}

private fun rollbackActions(target: PlayerData, minutes: Int): RollbackResult {
    val result = RollbackResult()
    val queue = recordsByUuid[target.uuid] ?: return result
    val now = System.currentTimeMillis()
    val from = now - minutes.toLong() * 60_000L
    val actions = queue.filter { it.timeMs in from..now }
    result.matched = actions.size
    val cap = maxRollbackActionsPerRun.coerceAtLeast(1)
    val selected = actions.takeLast(cap).asReversed()
    result.truncated = actions.size > selected.size
    val touched = mutableSetOf<Int>()

    clearOnlineBuilderQueue(target)
    suppressPlayerAction(target.uuid)

    rollbackInProgress = true
    try {
        selected.forEach { action ->
            val affected = touchedArraysForRecord(action)
            touched += affected
            markForceClear(affected)
            result.processed++
            val error = executeRecord(action)
            if (error == null) {
                result.success++
            } else {
                result.failed++
                result.failedDetails += failureLine(action, error)
            }
        }
    } finally {
        rollbackInProgress = false
    }
    markForceClear(touched)
    clearBuildersOnTouchedTiles(touched)
    clearOnlineBuilderQueue(target)
    verifyResidue(touched, result)
    return result
}

private fun targetPublicName(target: PlayerData): String {
    val online = target.player
    if (online != null) return online.name
    // 离线目标仅显示短ID，避免投票信息暴露档案真名。
    return "玩家#${target.shortId}"
}

private fun voteTitle(action: String, target: PlayerData, minutes: Int) =
    "{action}([red]{target}[yellow],窗口[green]{minutes}[]分钟)".with(
        "action" to action,
        "target" to targetPublicName(target),
        "minutes" to minutes
    )

private fun rollbackSummary(target: PlayerData, minutes: Int, result: RollbackResult) =
    """
        [yellow]回滚目标: [white]{name}[] ({shortId})
        [yellow]时间窗口: [white]{minutes}分钟[]
        [yellow]匹配记录: [white]{matched}[] / 执行: [white]{processed}[]
        [green]成功: {success}[] [red]失败: {failed}[]${if (result.truncated) " [scarlet](超过上限,已截断)[]" else ""}
    """.trimIndent().with(
        "name" to targetPublicName(target),
        "shortId" to target.shortId,
        "minutes" to minutes,
        "matched" to result.matched,
        "processed" to result.processed,
        "success" to result.success,
        "failed" to result.failed
    )

registerActionFilter { action ->
    val player = action.player ?: return@registerActionFilter true
    val now = System.currentTimeMillis()
    cleanupForceClear(now)
    val until = suppressActionUntil[player.uuid()]
    if (until != null) {
        if (until <= now) {
            suppressActionUntil.remove(player.uuid())
        } else if (isActionBlocked(action.type)) {
            val lastTip = suppressTipAt[player.uuid()] ?: 0L
            if (now - lastTip > 1000L) {
                suppressTipAt[player.uuid()] = now
                player.sendMessage("[scarlet]回滚处理中，暂时禁止建筑操作".with())
            }
            return@registerActionFilter false
        }
    }

    val actionTile = action.tile
    if (actionTile != null && isActionBlocked(action.type) && isForceClearLocked(actionTile.array(), now)) {
        val lastTip = suppressTipAt[player.uuid()] ?: 0L
        if (now - lastTip > 1000L) {
            suppressTipAt[player.uuid()] = now
            player.sendMessage("[scarlet]该位置刚被回滚，暂时禁止建造操作".with())
        }
        clearPlanAt(actionTile)
        clearUnitBuildPlanAt(actionTile)
        forceClearTile(actionTile)
        return@registerActionFilter false
    }

    if (rollbackInProgress) return@registerActionFilter true
    if (action.type != Administration.ActionType.configure) return@registerActionFilter true
    val tile = action.tile ?: return@registerActionFilter true
    val build = tile.build ?: return@registerActionFilter true
    if (!enableConfigRollback) return@registerActionFilter true

    cleanupPendingConfig()
    val key = player.uuid() to tile.array()
    val queue = pendingConfigByPlayerPos.getOrPut(key) { ArrayDeque() }
    queue.addLast(ConfigPending(cloneConfig(build.config()), System.currentTimeMillis()))
    true
}

listen<EventType.BuildSelectEvent> {
    val tile = it.tile ?: return@listen
    val now = System.currentTimeMillis()
    if (!isForceClearLocked(tile.array(), now)) return@listen
    val builder = it.builder as? Builderc
    if (builder != null) {
        val x = tile.x.toInt()
        val y = tile.y.toInt()
        runCatching { builder.removeBuild(x, y, true) }
        runCatching { builder.removeBuild(x, y, false) }
    }
    forceClearTile(tile)
}

listen<EventType.BlockBuildBeginEvent> {
    val tile = it.tile ?: return@listen
    val now = System.currentTimeMillis()
    if (!isForceClearLocked(tile.array(), now)) return@listen
    forceClearTile(tile)
}

onEnable {

    VoteEvent.VoteCommands += CommandInfo(this, "rollGriefers", "投票回滚某玩家最近建筑操作".with()) {
        aliases = listOf("rg", "捣乱")
        usage = "<ShortID> [分钟]"
        requirePermission("determination.vote.rollGriefers")
        body {
            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            if (arg.isEmpty()) returnReply("[red]请输入目标ShortID".with())
            val target = PlayerData.findByShortId(arg[0])
                ?: returnReply("[red]未找到目标玩家，请输入正确的 ShortID/UUID".with())
            val minutes = (arg.getOrNull(1)?.toIntOrNull() ?: defaultRollbackMinutes)
                .coerceIn(1, maxRollbackMinutes.coerceAtLeast(1))
            val event = VoteEvent(
                thisScript, starter,
                voteDesc = voteTitle("回滚捣乱", target, minutes),
                extDesc = "[yellow]将回滚目标在最近 ${minutes} 分钟内的建筑操作".with().toString()
            )
            if (!event.awaitResult()) return@body

            val result = rollbackActions(target, minutes)
            logger.info("rollGriefers target=${target.shortId} matched=${result.matched} success=${result.success} failed=${result.failed}")
            broadcast("[green]投票通过，已执行回滚\n{summary}".with("summary" to rollbackSummary(target, minutes, result)), quite = true)
            if (result.failedDetails.isNotEmpty()) {
                logger.warning("rollGriefers failed details:\n" + result.failedDetails.joinToString("\n"))
                broadcast(
                    "[scarlet]以下回滚操作失败:\n{list|joinLines}".with("list" to result.failedDetails),
                    quite = true
                )
            }
        }
    }

    VoteEvent.VoteCommands += CommandInfo(this, "kickGriefers", "投票踢出并回滚某玩家".with()) {
        aliases = listOf("kg", "飞走")
        usage = "<ShortID> [分钟] [理由]"
        requirePermission("determination.vote.kickGriefers")
        body {
            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            if (arg.isEmpty()) returnReply("[red]请输入目标ShortID".with())
            val target = PlayerData.findByShortId(arg[0])
                ?: returnReply("[red]未找到目标玩家，请输入正确的 ShortID/UUID".with())
            var nextIndex = 1
            var minutes = defaultRollbackMinutes
            arg.getOrNull(nextIndex)?.toIntOrNull()?.let {
                minutes = it
                nextIndex++
            }
            minutes = minutes.coerceIn(1, maxRollbackMinutes.coerceAtLeast(1))
            val reason = arg.drop(nextIndex).joinToString(" ").ifBlank { "投票处理疑似捣乱行为" }

            val event = VoteEvent(
                thisScript, starter,
                voteDesc = voteTitle("踢出并回滚", target, minutes),
                extDesc = "[red]理由: [yellow]{reason}".with("reason" to reason).toString()
            )
            if (!event.awaitResult()) return@body

            val online = target.player
            if (online != null && online.hasPermission("wayzer.admin.skipKick")) {
                broadcast("[red]错误: {target.name}[red]为管理员, 如有问题请与服主联系".with("target" to online))
                return@body
            }

            banImpl.ban(target, kickBanMinutes.coerceAtLeast(1), "kickGriefers: $reason", starter)

            val result = rollbackActions(target, minutes)
            logger.info("kickGriefers target=${target.shortId} matched=${result.matched} success=${result.success} failed=${result.failed}")
            broadcast("[green]投票通过，已踢出并执行回滚\n{summary}".with("summary" to rollbackSummary(target, minutes, result)), quite = true)
            if (result.failedDetails.isNotEmpty()) {
                logger.warning("kickGriefers failed details:\n" + result.failedDetails.joinToString("\n"))
                broadcast(
                    "[scarlet]以下回滚操作失败:\n{list|joinLines}".with("list" to result.failedDetails),
                    quite = true
                )
            }
        }
    }
}

command("rollbackgriefer", "管理员直接回滚某玩家最近建筑操作".with()) {
    aliases = listOf("rg")
    usage = "<ShortID> [分钟]"
    requirePermission("determination.admin.rollbackGriefer")
    body {
        if (arg.isEmpty()) returnReply("[red]参数错误: /rg <ShortID> [分钟]".with())
        val target = PlayerData.findByShortId(arg[0])
            ?: returnReply("[red]未找到目标玩家，请输入正确的 ShortID/UUID".with())
        val minutes = (arg.getOrNull(1)?.toIntOrNull() ?: defaultRollbackMinutes)
            .coerceIn(1, maxRollbackMinutes.coerceAtLeast(1))

        val result = rollbackActions(target, minutes)
        logger.info(
            "admin rollbackGriefer operator=${player?.uuid() ?: "console"} " +
                "target=${target.shortId} matched=${result.matched} success=${result.success} failed=${result.failed}"
        )
        returnReply(
            "[green]管理员回滚已执行\n{summary}".with(
                "summary" to rollbackSummary(target, minutes, result)
            )
        )
        if (result.failedDetails.isNotEmpty()) {
            logger.warning("admin rollbackGriefer failed details:\n" + result.failedDetails.joinToString("\n"))
            reply(
                "[scarlet]以下回滚操作失败:\n{list|joinLines}".with(
                    "list" to result.failedDetails
                )
            )
        }
    }
}

onDisable {
    recordsByUuid.clear()
    preTileSnapshot.clear()
    pendingConfigByPlayerPos.clear()
    suppressActionUntil.clear()
    suppressTipAt.clear()
    forceClearUntilByTile.clear()
}

listen<EventType.WorldLoadEvent> {
    recordsByUuid.clear()
    preTileSnapshot.clear()
    pendingConfigByPlayerPos.clear()
    suppressActionUntil.clear()
    suppressTipAt.clear()
    forceClearUntilByTile.clear()
}

listen<EventType.PlayerLeave> {
    suppressActionUntil.remove(it.player.uuid())
    suppressTipAt.remove(it.player.uuid())
}

listen<EventType.TilePreChangeEvent> {
    if (rollbackInProgress) return@listen
    val tile = it.tile ?: return@listen
    preTileSnapshot[tile.array()] = captureSnapshot(tile)
}

listen<EventType.BlockBuildEndEvent> {
    if (rollbackInProgress) return@listen
    val player = it.unit?.player ?: return@listen
    val uuid = player.uuid()
    val tile = it.tile
    val array = tile.array()
    val before = preTileSnapshot.remove(array) ?: return@listen
    val after = captureSnapshot(tile)
    addRecord(
        ActionRecord(
            uuid = uuid,
            timeMs = System.currentTimeMillis(),
            kind = if (it.breaking) ActionKind.Break else ActionKind.Place,
            array = array,
            before = before,
            after = after
        )
    )
}

listen<EventType.BuildRotateEvent> {
    if (rollbackInProgress || !enableRotateRollback) return@listen
    val player = it.unit?.player ?: return@listen
    val build = it.build ?: return@listen
    if (!build.block.rotate) return@listen
    val tile = build.tile ?: return@listen
    val before = captureSnapshot(tile).copy(rotation = it.previous)
    val after = captureSnapshot(tile)
    addRecord(
        ActionRecord(
            uuid = player.uuid(),
            timeMs = System.currentTimeMillis(),
            kind = ActionKind.Rotate,
            array = tile.array(),
            before = before,
            after = after
        )
    )
}

listen<EventType.ConfigEvent> {
    if (rollbackInProgress || !enableConfigRollback) return@listen
    val player = it.player ?: return@listen
    val build = it.tile ?: return@listen
    val tile = build.tile ?: return@listen
    val key = player.uuid() to tile.array()

    cleanupPendingConfig()
    val pending = pendingConfigByPlayerPos[key]?.pollFirstOrNull()
    if (pendingConfigByPlayerPos[key]?.isEmpty() == true) pendingConfigByPlayerPos.remove(key)
    if (pending == null) return@listen

    val before = captureSnapshot(tile).copy(config = cloneConfig(pending.beforeConfig))
    val after = captureSnapshot(tile).copy(config = cloneConfig(build.config()))
    addRecord(
        ActionRecord(
            uuid = player.uuid(),
            timeMs = System.currentTimeMillis(),
            kind = ActionKind.Config,
            array = tile.array(),
            before = before,
            after = after
        )
    )
}

PermissionApi.registerDefault("determination.vote.rollGriefers", "determination.vote.kickGriefers")
PermissionApi.registerDefault("determination.admin.rollbackGriefer", group = "@admin")
