@file:Depends("wayzer/cmds/voteOb", "投票观战命令")
@file:Depends("wayzer/user/ban", "封禁实现")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.define.Script
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.PermissionApi
import coreMindustry.lib.hasPermission
import coreMindustry.lib.registerActionFilter
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.net.Administration
import wayzer.VoteEvent
import wayzer.cmds.VoteKick
import wayzer.cmds.VoteOb
import wayzer.lib.PlayerData
import wayzer.map.BetterTeam
import wayzer.user.Ban
import java.time.Instant

name = "StopActWhenVoteing"

private val permissionAvoidStoppingAct = "determination.stopActWhenVoteing.avoid"

private val blockUnitControl by config.key(true, "是否禁止控兵(包含指挥/命令)")
private val blockBuild by config.key(true, "是否禁止建造/拆除/旋转")
private val blockBlockAction by config.key(true, "是否禁止操作方块(配置/开关/逻辑)")
private val blockTipIntervalSeconds by config.key(2, "阻止操作提示间隔(秒)")

@Savable(false)
var voteLockCount = mutableMapOf<String, Int>()

@Savable(false)
var lastBlockTipAt = mutableMapOf<String, Long>()

@Savable(false)
var originKickCommand: CommandInfo? = null

@Savable(false)
var originObCommand: CommandInfo? = null

@Savable(false)
var wrappedKickCommand: CommandInfo? = null

@Savable(false)
var wrappedObCommand: CommandInfo? = null

private val voteKick = contextScript<VoteKick>()
private val voteOb = contextScript<VoteOb>()
private val betterTeam = contextScript<BetterTeam>()
private val banImpl = contextScript<Ban>()

private fun lockPlayer(uuid: String) {
    voteLockCount[uuid] = (voteLockCount[uuid] ?: 0) + 1
}

private fun unlockPlayer(uuid: String) {
    val remain = (voteLockCount[uuid] ?: return) - 1
    if (remain <= 0) voteLockCount.remove(uuid) else voteLockCount[uuid] = remain
}

private fun isLocked(player: Player): Boolean = (voteLockCount[player.uuid()] ?: 0) > 0

private suspend fun lockVoteParticipants(starter: Player, target: Player): List<String> {
    if (starter.uuid() == target.uuid()) return emptyList()
    val locked = linkedSetOf<String>()
    if (!starter.hasPermission(permissionAvoidStoppingAct)) locked += starter.uuid()
    if (!target.hasPermission(permissionAvoidStoppingAct)) locked += target.uuid()
    locked.forEach(::lockPlayer)
    return locked.toList()
}

private fun unlockVoteParticipants(locked: List<String>) {
    locked.forEach(::unlockPlayer)
}

private fun shouldBlockAction(type: Administration.ActionType): Boolean {
    return when (type) {
        Administration.ActionType.control,
        Administration.ActionType.command,
        Administration.ActionType.commandUnits,
        Administration.ActionType.commandBuilding -> blockUnitControl

        Administration.ActionType.placeBlock,
        Administration.ActionType.breakBlock,
        Administration.ActionType.rotate,
        Administration.ActionType.buildSelect,
        Administration.ActionType.removePlanned,
        Administration.ActionType.pickupBlock,
        Administration.ActionType.dropPayload -> blockBuild

        Administration.ActionType.configure -> blockBlockAction
        else -> false
    }
}

private fun sendBlockedTip(player: Player) {
    val now = System.currentTimeMillis()
    val intervalMs = blockTipIntervalSeconds.coerceAtLeast(0) * 1000L
    val last = lastBlockTipAt[player.uuid()] ?: 0L
    if (now - last < intervalMs) return
    lastBlockTipAt[player.uuid()] = now
    player.sendMessage("[scarlet]你正在参与 ob/kick 投票，投票结束前暂时禁止该操作".with())
}

private fun buildKickWrapper(script: Script): CommandInfo {
    return CommandInfo(script, "kick", "踢出某人".with()) {
        aliases = listOf("踢出")
        usage = "<玩家名/id> <理由>"
        requirePermission("wayzer.vote.kick")
        body {
            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            val target = with(voteKick) { getTarget() }
            val reason = with(voteKick) { getInput("踢出理由", "[red]投票踢人需要理由".with()) }
            val event = VoteEvent(
                script, starter,
                voteDesc = "投票(踢出[red]{target}[yellow])".with("target" to target),
                extDesc = "[red]理由: [yellow]$reason"
            )
            val snapshot = PlayerData[target]
            val locked = lockVoteParticipants(starter, target)
            try {
                if (event.awaitResult()) {
                    if (target.hasPermission("wayzer.admin.skipKick")) {
                        broadcast("[red]警告: {target.name}[red] 为管理员, 建议联系服主处理".with("target" to target))
                    } else {
                        banImpl.ban(snapshot, 60, "投票踢出: $reason", starter)
                    }
                }
            } finally {
                unlockVoteParticipants(locked)
            }
        }
    }
}

private fun buildObWrapper(script: Script): CommandInfo {
    return CommandInfo(script, "ob", "强制观战".with()) {
        aliases = listOf("观战")
        usage = "<玩家名/id> <理由>"
        requirePermission("wayzer.vote.ob")
        body {
            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            val target = with(voteKick) { getTarget() }
            val reason = with(voteKick) { getInput("强制观战理由", "[red]投票强制观战需要理由".with()) }
            val event = VoteEvent(
                script, starter,
                voteDesc = "强制观战(目标[red]{target.name}[yellow])".with("target" to target),
                extDesc = "[red]理由: [yellow]$reason"
            )
            val locked = lockVoteParticipants(starter, target)
            try {
                if (event.awaitResult()) {
                    if (target.hasPermission("wayzer.admin.skipKick")) {
                        broadcast("[red]警告: {target.name}[red] 为管理员, 建议联系服主处理".with("target" to target))
                        return@body
                    }
                    val targetData = PlayerData[target]
                    val now = Instant.now()
                    val limitIds = (targetData.ids + targetData.id).distinct()
                    with(voteOb) {
                        limitIds.forEach { id ->
                            limitPlayers[id] = reason to now
                        }
                    }
                    betterTeam.changeTeam(target, betterTeam.spectateTeam)
                    broadcast("[yellow][提示][green]如目标继续捣乱，可使用[gold]/vote kick[]发起踢出投票".with())
                }
            } finally {
                unlockVoteParticipants(locked)
            }
        }
    }
}

registerActionFilter { action ->
    val actor = action.player ?: return@registerActionFilter true
    if (!isLocked(actor)) return@registerActionFilter true
    if (!shouldBlockAction(action.type)) return@registerActionFilter true
    sendBlockedTip(actor)
    false
}

listen<EventType.PlayerLeave> {
    voteLockCount.remove(it.player.uuid())
    lastBlockTipAt.remove(it.player.uuid())
}

listen<EventType.ResetEvent> {
    voteLockCount.clear()
    lastBlockTipAt.clear()
}

onEnable {
    val commands = VoteEvent.VoteCommands
    val kick = commands.getSub("kick")
    val ob = commands.getSub("ob")
    if (kick == null || ob == null) {
        logger.warning("stopActWhenVoteing: 找不到原始 kick/ob 命令，已跳过覆写")
        return@onEnable
    }
    originKickCommand = kick
    originObCommand = ob
    commands.removeSub(kick)
    commands.removeSub(ob)

    val kickWrapper = buildKickWrapper(this)
    val obWrapper = buildObWrapper(this)
    wrappedKickCommand = kickWrapper
    wrappedObCommand = obWrapper
    commands += kickWrapper
    commands += obWrapper
}

onDisable {
    voteLockCount.clear()
    lastBlockTipAt.clear()

    val commands = VoteEvent.VoteCommands
    wrappedKickCommand?.let { commands.removeSub(it) }
    wrappedObCommand?.let { commands.removeSub(it) }
    originKickCommand?.let { if (commands.getSub("kick") == null) commands += it }
    originObCommand?.let { if (commands.getSub("ob") == null) commands += it }

    wrappedKickCommand = null
    wrappedObCommand = null
    originKickCommand = null
    originObCommand = null
}

PermissionApi.registerDefault(permissionAvoidStoppingAct, group = "AvoidStoppingAct")
PermissionApi.registerDefault(permissionAvoidStoppingAct, group = "@admin")
