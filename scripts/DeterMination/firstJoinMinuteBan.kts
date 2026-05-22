@file:Depends("DeterMination")
@file:Depends("wayzer/user/ban")

package DeterMination

import mindustry.game.EventType
import wayzer.user.Ban

name = "FirstJoinMinuteBan"

private val firstJoinMinuteBanEnabled by config.key(false, "Enable auto-ban on first join")
private val firstJoinBanMinutes by config.key(1, "Auto-ban duration on first join (minutes)")
private val firstJoinBanReason by config.key("第一次进服，请稍等一分钟", "Auto-ban reason on first join")

private val banImpl = contextScript<Ban>()

listen<EventType.PlayerJoin> {
    if (!firstJoinMinuteBanEnabled) return@listen
    val player = it.player
    val adminInfo = netServer.admins.getInfoOptional(player.uuid())
    val joined = adminInfo?.timesJoined ?: 0
    if (joined > 1) return@listen

    logger.info("FirstJoinMinuteBan: first join detected for ${player.name} (${player.uuid()}), ban ${firstJoinBanMinutes} minute(s)")
    launch {
        runCatching {
            banImpl.ban(
                PlayerData[player],
                firstJoinBanMinutes.coerceAtLeast(1),
                firstJoinBanReason,
                null
            )
        }.onFailure { error ->
            logger.warning("FirstJoinMinuteBan: ban failed for ${player.uuid()}: ${error.message}")
        }
    }
}
