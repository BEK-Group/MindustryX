@file:Depends("wayzer", "WayZer基础模块")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.listenTo
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.event.RequestPermissionEvent
import kotlinx.coroutines.Dispatchers
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Player
import mindustry.net.Packets.AdminAction

private val permissionTabSelfSwitch = "determination.banChangeTeam.tabSelfSwitch"
private val permissionUseTeamCommand = "determination.banChangeTeam.useTeamCommand"
private val wayzerTeamPermission = "wayzer.ext.team.change"

private fun hasPermission(player: Player, node: String): Boolean {
    val groups = buildList {
        add(player.uuid())
        if (player.admin) add("@admin")
    }
    return PermissionApi.check(groups, node)
}

listen<EventType.AdminRequestEvent> { event ->
    if (event.action != AdminAction.switchTeam) return@listen
    val actor = event.player
    val target = event.other
    if (actor != target) return@listen
    if (!actor.admin) return@listen
    if (hasPermission(actor, permissionTabSelfSwitch)) return@listen

    val oldTeam: Team = actor.team()
    launch(Dispatchers.gamePost) {
        if (actor.team() == oldTeam) return@launch
        actor.team(oldTeam)
        actor.sendMessage("[red]你没有权限在TAB菜单中改变自己的队伍".with())
        logger.info("BanChangeTeam: blocked TAB self team switch for ${actor.name} (${actor.uuid()})")
    }
}

listenTo<RequestPermissionEvent> {
    if (permission != wayzerTeamPermission) return@listenTo
    val player = subject as? Player ?: return@listenTo
    if (!player.admin) return@listenTo

    val groups = if (group.isNotEmpty()) group else buildList {
        add(player.uuid())
        add("@admin")
    }
    directReturn(
        if (PermissionApi.check(groups, permissionUseTeamCommand))
            PermissionApi.Result.Has
        else
            PermissionApi.Result.Reject
    )
}

PermissionApi.registerDefault("-$permissionTabSelfSwitch", group = "@admin")
PermissionApi.registerDefault("-$permissionUseTeamCommand", group = "@admin")
