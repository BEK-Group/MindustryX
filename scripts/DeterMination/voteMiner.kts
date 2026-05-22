@file:Depends("wayzer/vote", "投票实现")
@file:Depends("coreMindustry/util/spawnAround", "核心附近随机生成单位")

package DeterMination

import arc.math.Mathf
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.PermissionApi
import mindustry.ai.types.MinerAI
import mindustry.ctype.ContentType
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.type.UnitType
import wayzer.VoteEvent
import wayzer.VoteService
import coreMindustry.util.spawnAround

name = "VoteMiner"

private val defaultSpawnRadius by config.key(10, "核心周围随机生成半径(格)")
private val maxSpawnAmount by config.key(200, "单次允许生成的最大单位数量")

private fun listUnits(units: List<UnitType>): String =
    units.mapIndexed { index, unit -> "[yellow]$index[green](${unit.name})" }.joinToString()

private fun listTeams(): String =
    Team.baseTeams.joinToString { "[yellow]${it.id}[green](${it.name})" }

private fun parseUnit(raw: String, units: List<UnitType>): UnitType? {
    return raw.toIntOrNull()?.let { units.getOrNull(it) }
        ?: units.firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.localizedName.equals(raw, ignoreCase = true)
        }
}

private fun parseTeam(raw: String): Team? {
    return raw.toIntOrNull()?.let { Team.all.getOrNull(it) }
        ?: Team.baseTeams.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}

private fun randomCore(team: Team): Building? {
    val cores = team.data().cores
    if (cores.isEmpty) return null
    return cores.random()
}

private fun spawnMinerUnits(unit: UnitType, amount: Int, team: Team, radius: Int): Int {
    var succeed = 0
    repeat(amount) {
        val core = randomCore(team) ?: return@repeat
        val spawned = unit.spawnAround(core, team, radius) ?: return@repeat
        spawned.controller(MinerAI())
        succeed++
    }
    return succeed
}

onEnable {
    VoteEvent.VoteCommands += CommandInfo(this, "miner", "投票为队伍生成矿工单位".with()) {
        usage = "<unit> <amount> <team>"
        aliases = listOf("矿工", "矿机")
        requirePermission("determination.vote.miner")
        body {
            val units = content.getBy<UnitType>(ContentType.unit).filterNot { it.internal }
            if (arg.size < 3) {
                returnReply(
                    "[red]参数错误: /vote miner <unit> <amount> <team>\n[yellow]单位列表: {units}\n[yellow]队伍列表: {teams}".with(
                        "units" to listUnits(units),
                        "teams" to listTeams()
                    )
                )
            }

            val unitRaw = arg[0]
            val amountRaw = arg[1]
            val teamRaw = arg[2]

            val unit = parseUnit(unitRaw, units)
                ?: returnReply("[red]无效单位: {raw}\n[yellow]单位列表: {list}".with("raw" to unitRaw, "list" to listUnits(units)))
            val amount = amountRaw.toIntOrNull()
                ?: returnReply("[red]数量必须是整数: {raw}".with("raw" to amountRaw))
            if (amount <= 0) returnReply("[red]数量必须大于0".with())
            if (amount > maxSpawnAmount) returnReply("[red]数量过大，最大允许 {max}".with("max" to maxSpawnAmount))

            val team = parseTeam(teamRaw)
                ?: returnReply("[red]无效队伍: {raw}\n[yellow]队伍列表: {list}".with("raw" to teamRaw, "list" to listTeams()))
            if (!team.active() || team.data().cores.isEmpty) {
                returnReply("[red]目标队伍当前没有核心，无法生成单位".with())
            }

            VoteService.start(
                starter = player ?: returnReply("[red]仅玩家可发起投票".with()),
                voteDesc = "生成矿工单位({unit} x {amount} -> {team.colorizeName}[])".with(
                    "unit" to unit.name,
                    "amount" to amount,
                    "team" to team
                ),
                extDesc = "[gray]通过后会在目标队伍核心附近随机生成单位，默认设置为 [accent]MinerAI[]\n[red]需100%通过[]".with()
                    .toString(),
                requireNum = { all -> all.coerceAtLeast(1.0) }, // 100%同意
                fastSuccess = true
            ) {
                val radius = Mathf.clamp(defaultSpawnRadius, 1, 80)
                val spawned = spawnMinerUnits(unit, amount, team, radius)
                if (spawned <= 0) {
                    broadcast(
                        "[red]投票已通过，但未找到可用刷点，未生成任何单位".with(),
                        quite = true
                    )
                } else if (spawned < amount) {
                    broadcast(
                        "[yellow]投票通过: 为{team.colorizeName}[]生成 {spawned}/{amount} 个 {unit}".with(
                            "team" to team,
                            "spawned" to spawned,
                            "amount" to amount,
                            "unit" to unit.name
                        ),
                        quite = true
                    )
                } else {
                    broadcast(
                        "[green]投票通过: 为{team.colorizeName}[]生成 {amount} 个 {unit}".with(
                            "team" to team,
                            "amount" to amount,
                            "unit" to unit.name
                        ),
                        quite = true
                    )
                }
            }
        }
    }
}

PermissionApi.registerDefault("determination.vote.miner")
