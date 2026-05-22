@file:Depends("DeterMination")
@file:Depends("wayzer/user/nameExt")

package DeterMination

import arc.util.Strings
import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.contextScript
import coreLibrary.lib.PermissionApi
import coreMindustry.lib.registerActionFilter
import mindustry.Vars.tilesize
import mindustry.ai.types.CommandAI
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.net.Administration
import wayzer.user.NameExt
import kotlin.math.roundToInt

name = "UnitHistory"

private val permissionViewUnitHistory = "determination.unitHistory.use"
private val historyMaxSize by config.key(500, "Max unit control history entries (global)")
private val resolveRetryTicks by config.key(12, "Unit target resolve retry ticks")
private val historyPageSize by config.key(12, "/uh entries per page")

private data class UnitHistoryEntry(
    val teamId: Int,
    val playerName: String,
    val unitSummary: String,
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
)

private data class PendingCommand(
    val teamId: Int,
    val playerName: String,
    val unitSummary: String,
    val fromX: Int,
    val fromY: Int,
    val unitIds: IntArray,
    var retriesLeft: Int,
)

@Savable(false)
private val history = mutableListOf<UnitHistoryEntry>()

@Savable(false)
private val pending = mutableListOf<PendingCommand>()

private val nameExt by lazy { runCatching { contextScript<NameExt>() }.getOrNull() }

private fun plainName(raw: String?): String {
    val stripped = Strings.stripColors(raw ?: "").trim()
    return stripped.ifEmpty { "<unknown>" }
}

private fun invokeCompat(target: Any?, methodName: String, vararg args: Any?): Any? {
    if (target == null) return null
    val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
        ?: target.javaClass.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
        ?: return null
    return runCatching {
        method.isAccessible = true
        method.invoke(target, *args)
    }.getOrNull()
}

private fun displayNameForHistory(player: Player): String {
    val overrideMap = invokeCompat(nameExt, "getOverrideName") as? Map<*, *>
    val overrideName = overrideMap?.get(player.uuid()) as? String
    return plainName(overrideName ?: player.name)
}

private fun unitName(unit: Unit): String {
    val localized = unit.type.localizedName?.trim().orEmpty()
    if (localized.isNotEmpty()) return localized
    return unit.type.name
}

private fun summarizeUnits(units: List<Unit>): String {
    if (units.isEmpty()) return "未知单位"
    val counts = linkedMapOf<String, Int>()
    units.forEach { unit ->
        val name = unitName(unit)
        counts[name] = (counts[name] ?: 0) + 1
    }
    return counts.entries.joinToString(" + ") { (name, count) ->
        if (count <= 1) name else "$name x $count"
    }
}

private fun worldToTile(value: Float): Int = (value / tilesize).roundToInt()

private fun averageTileFromUnits(units: List<Unit>): Pair<Int, Int> {
    if (units.isEmpty()) return 0 to 0
    val avgX = units.sumOf { it.x.toDouble() } / units.size
    val avgY = units.sumOf { it.y.toDouble() } / units.size
    return worldToTile(avgX.toFloat()) to worldToTile(avgY.toFloat())
}

private fun appendHistory(entry: UnitHistoryEntry) {
    history += entry
    val maxSize = historyMaxSize.coerceAtLeast(1)
    if (history.size > maxSize) {
        history.subList(0, history.size - maxSize).clear()
    }
}

private fun resolveTargetTile(command: PendingCommand): Pair<Int, Int>? {
    val points = mutableListOf<Pair<Float, Float>>()
    command.unitIds.forEach { id ->
        val unit = Groups.unit.getByID(id) ?: return@forEach
        val ai = unit.controller() as? CommandAI ?: return@forEach
        val target = ai.targetPos ?: return@forEach
        points += target.x to target.y
    }
    if (points.isEmpty()) return null
    val avgX = points.sumOf { it.first.toDouble() } / points.size
    val avgY = points.sumOf { it.second.toDouble() } / points.size
    return worldToTile(avgX.toFloat()) to worldToTile(avgY.toFloat())
}

private fun recordCommand(action: Administration.PlayerAction) {
    if (action.type == Administration.ActionType.control) {
        val player = action.player ?: return
        val target = action.unit ?: return
        if (target.team != player.team()) return
        val fromUnit = player.unit()
        val fromX = worldToTile((fromUnit ?: target).x)
        val fromY = worldToTile((fromUnit ?: target).y)
        appendHistory(
            UnitHistoryEntry(
                teamId = player.team().id,
                playerName = displayNameForHistory(player),
                unitSummary = unitName(target),
                fromX = fromX,
                fromY = fromY,
                toX = worldToTile(target.x),
                toY = worldToTile(target.y),
            )
        )
        return
    }

    if (action.type != Administration.ActionType.commandUnits) return
    val player = action.player ?: return
    val ids = action.unitIDs ?: return
    if (ids.isEmpty()) return

    val team = player.team()
    val units = ids
        .asSequence()
        .mapNotNull { id -> Groups.unit.getByID(id) }
        .filter { it.team == team }
        .toList()
    if (units.isEmpty()) return

    val (fromX, fromY) = averageTileFromUnits(units)
    pending += PendingCommand(
        teamId = team.id,
        playerName = displayNameForHistory(player),
        unitSummary = summarizeUnits(units),
        fromX = fromX,
        fromY = fromY,
        unitIds = units.map { it.id }.toIntArray(),
        retriesLeft = resolveRetryTicks.coerceAtLeast(0),
    )
}

private fun pollPendingCommands() {
    if (pending.isEmpty()) return
    val iterator = pending.iterator()
    while (iterator.hasNext()) {
        val command = iterator.next()
        val target = resolveTargetTile(command)
        if (target == null && command.retriesLeft > 0) {
            command.retriesLeft -= 1
            continue
        }
        val (toX, toY) = target ?: (command.fromX to command.fromY)
        appendHistory(
            UnitHistoryEntry(
                teamId = command.teamId,
                playerName = command.playerName,
                unitSummary = command.unitSummary,
                fromX = command.fromX,
                fromY = command.fromY,
                toX = toX,
                toY = toY,
            )
        )
        iterator.remove()
    }
}

private fun parseTeam(raw: String): Team? {
    val token = raw.trim()
    if (token.isEmpty()) return null
    token.toIntOrNull()?.let { id ->
        Team.all.getOrNull(id)?.let { return it }
    }
    return Team.all.firstOrNull { team ->
        team.name.equals(token, ignoreCase = true) || team.localized().equals(token, ignoreCase = true)
    }
}

private fun formatHistoryLine(entry: UnitHistoryEntry): String =
    "{player} 控制 {unit} 从 ({fromX},{fromY}) 到 ({toX},{toY})".with(
        "player" to entry.playerName,
        "unit" to entry.unitSummary,
        "fromX" to entry.fromX,
        "fromY" to entry.fromY,
        "toX" to entry.toX,
        "toY" to entry.toY
    ).toString()

private fun resolveQueryTeam(sender: Player?, arg0: String?): Team? {
    val token = arg0?.trim().orEmpty()
    if (token.isEmpty()) return sender?.team()
    if (token.equals("all", ignoreCase = true)) return null
    return parseTeam(token)
}

registerActionFilter {
    recordCommand(it)
    true
}

listen(EventType.Trigger.update) {
    pollPendingCommands()
}

listen<EventType.ResetEvent> {
    history.clear()
    pending.clear()
}

listen<EventType.WorldLoadEvent> {
    history.clear()
    pending.clear()
}

command("unitHistory", "查看控兵记录".with()) {
    aliases = listOf("uh")
    usage = "[team|all|page] [page]"
    requirePermission(permissionViewUnitHistory)
    body {
        val sender = player
        val queryArg = arg.getOrNull(0)?.trim().orEmpty()
        val pageArg = arg.getOrNull(1)?.trim().orEmpty()

        val (selectedTeam, requestedPage) = if (queryArg.isEmpty()) {
            sender?.team() to 1
        } else {
            val firstAsPage = queryArg.toIntOrNull()
            if (firstAsPage != null) {
                if (pageArg.isNotEmpty()) {
                    returnReply("[red]参数格式: /uh [team|all] [page]".with())
                }
                (sender?.team()) to firstAsPage
            } else {
                val team = resolveQueryTeam(sender, queryArg)
                if (!queryArg.equals("all", ignoreCase = true) && team == null) {
                    returnReply("[red]无效队伍参数: {arg}".with("arg" to queryArg))
                }
                if (pageArg.isEmpty()) {
                    team to 1
                } else {
                    val page = pageArg.toIntOrNull()
                        ?: returnReply("[red]无效页码: {arg}".with("arg" to pageArg))
                    team to page
                }
            }
        }

        if (requestedPage <= 0) {
            returnReply("[red]页码需大于 0".with())
        }

        if (sender == null && queryArg.isEmpty()) {
            returnReply("[red]控制台请指定队伍或 all".with())
        }

        val filtered = if (selectedTeam == null) {
            history.toList()
        } else {
            history.filter { it.teamId == selectedTeam.id }
        }
        if (filtered.isEmpty()) {
            val scope = selectedTeam?.localized() ?: "全部队伍".with()
            returnReply("[yellow]{scope} 暂无控兵记录".with("scope" to scope))
        }

        val lines = filtered.asReversed().map(::formatHistoryLine)
        val pageSize = historyPageSize.coerceAtLeast(1)
        val totalPage = ((lines.size - 1) / pageSize) + 1
        val realPage = requestedPage.coerceIn(1, totalPage)
        val from = (realPage - 1) * pageSize
        val to = (from + pageSize).coerceAtMost(lines.size)
        val pageLines = lines.subList(from, to)
        val scope = selectedTeam?.localized() ?: "全部队伍".with()
        reply(
            "[accent]控兵记录[{scope}][] 共 {count} 条 [lightgray]第 {page}/{totalPage} 页[]\n{lines|joinLines}\n[lightgray]使用 /uh <页码> 或 /uh <team|all> <页码> 翻页[]".with(
                "scope" to scope,
                "count" to filtered.size,
                "page" to realPage,
                "totalPage" to totalPage,
                "lines" to pageLines
            )
        )
    }
}

PermissionApi.registerDefault(permissionViewUnitHistory)
