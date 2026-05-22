@file:Depends("DeterMination")
@file:Depends("wayzer", "WayZer基础模块")

package DeterMination

import arc.util.CommandHandler
import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.lib.ClientOnly
import coreMindustry.lib.MyCommandHandler
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.game.EventType

name = "CpWhitelist"

private val cpWhitelistEnabled by config.key(true, "是否启用cp(DataPatch)地图白名单")
private val whitelistMapIds by config.key(listOf<Int>(), "允许启用cp(DataPatch)的地图ID白名单(/maps中的id)")
private val denyMessage by config.key(
    "[red]当前地图(ID:{id})未开启 cp(DataPatch) 功能".trim(),
    "拒绝提示(可用{id}占位)"
)
private val autoUnapplyOnDeny by config.key(true, "换图到非白名单时自动关闭DataPatch(调用state.patcher.unapply())")

private fun currentMapId(): Int = runCatching { Vars.state.rules.tags.getInt("id", -1) }.getOrDefault(-1)

private fun isWhitelisted(id: Int): Boolean = whitelistMapIds.contains(id)

private fun originClientCommands(): CommandHandler? {
    val handler = netServer?.clientCommands ?: return null
    return (handler as? MyCommandHandler)?.origin ?: handler
}

private fun buildRaw(prefix: String, cmd: String, args: List<String>): String = buildString {
    append(prefix)
    append(cmd)
    if (args.isNotEmpty()) {
        append(' ')
        append(args.joinToString(" "))
    }
}

private fun tryUnapplyDataPatch(reason: String, mapId: Int) {
    runCatching {
        state.patcher.unapply()
        state.patcher.patches.clear()
    }.onFailure {
        logger.warning("CpWhitelist: unapply failed($reason, map=$mapId): ${it.javaClass.simpleName}: ${it.message}")
    }
}

listen<EventType.WorldLoadEvent> {
    if (!cpWhitelistEnabled || !autoUnapplyOnDeny) return@listen
    val mapId = currentMapId()
    if (isWhitelisted(mapId)) return@listen
    tryUnapplyDataPatch("worldLoad", mapId)
}

command("cp", "cp(DataPatch)白名单控制") {
    attr(ClientOnly)
    body {
        val player = player ?: returnReply("[red]仅玩家可用".with())
        val mapId = currentMapId()
        if (cpWhitelistEnabled && !isWhitelisted(mapId)) {
            if (autoUnapplyOnDeny) tryUnapplyDataPatch("commandDeny", mapId)
            returnReply(denyMessage.with("id" to mapId))
        }

        val origin = originClientCommands()
            ?: returnReply("[red]cp原始指令不可用(未找到clientCommands)".with())
        val raw = buildRaw(origin.prefix, "cp", arg)
        val resp = origin.handleMessage(raw, player)
        when (resp.type) {
            CommandHandler.ResponseType.valid -> Unit
            CommandHandler.ResponseType.fewArguments,
            CommandHandler.ResponseType.manyArguments -> {
                val usage = resp.command?.paramText?.takeIf { it.isNotBlank() } ?: "[args]"
                returnReply("[red]参数错误: /cp {usage}".with("usage" to usage))
            }
            CommandHandler.ResponseType.unknownCommand ->
                returnReply("[red]cp原始指令不存在(可能未安装DataPatch插件)".with())
            CommandHandler.ResponseType.noCommand ->
                returnReply("[red]cp原始指令未被识别(前缀异常)".with())
        }
    }
}
