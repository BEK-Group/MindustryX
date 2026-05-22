@file:Depends("wayzer/cmds/voteKick", "功能控制，使用util，覆盖votekick")
@file:Depends("wayzer/map/betterTeam", "强制观察者")

package wayzer.cmds

import arc.util.Strings
import wayzer.VoteEvent
import wayzer.map.BetterTeam
import mindustry.Vars
import mindustry.gen.Player
import java.time.Duration
import java.time.Instant
import java.util.Locale

val teams = contextScript<BetterTeam>()
val voteKick = contextScript<VoteKick>()

@Savable(false)
val limitPlayers = mutableMapOf<String, Pair<String, Instant>>()//profile -> reason,time
customLoad(this::limitPlayers) { limitPlayers.putAll(it) }

private val mvpHistoryFileName by config.key("mvpTeamBalancer-history.tsv", "MVP history file name (in scripts/data)")
private val mvpHistoryDefault by config.key(0.5, "Default MVP value when no history record found")

private data class LimitVoteMeta(
    val agreeWeight: Double = 0.0,
    val agreePercent: Double = 0.0,
    val topSupporterName: String = "",
    val topSupporterMonthMvp: Double = 0.0,
    val topSupporterTotalMvp: Double = 0.0,
)

private data class MvpHistoryEntry(
    val monthMvp: Double,
    val totalMvp: Double,
)

private data class LimitInfo(
    val reason: String,
    val time: Instant,
    val voteMeta: LimitVoteMeta?,
)

@Savable(false)
private val limitVoteMeta = mutableMapOf<String, LimitVoteMeta>()
customLoad(this::limitVoteMeta) { limitVoteMeta.putAll(it) }

private fun profileKeys(player: Player): Set<String> {
    val data = PlayerData[player]
    return buildSet {
        add(data.id)
        addAll(data.ids)
    }
}

private fun resolveLimitInfo(player: Player): LimitInfo? {
    val keys = profileKeys(player)
    val limit = keys.asSequence().mapNotNull { id -> limitPlayers[id] }.firstOrNull() ?: return null
    val voteMeta = keys.asSequence().mapNotNull { id -> limitVoteMeta[id] }.firstOrNull()
    return LimitInfo(limit.first, limit.second, voteMeta)
}

private fun applyLimit(player: Player, reason: String, time: Instant = Instant.now(), voteMeta: LimitVoteMeta? = null) {
    profileKeys(player).forEach { id ->
        limitPlayers[id] = reason to time
        if (voteMeta == null) limitVoteMeta.remove(id) else limitVoteMeta[id] = voteMeta
    }
}

private fun clearLimit(player: Player) {
    profileKeys(player).forEach { id ->
        limitPlayers.remove(id)
        limitVoteMeta.remove(id)
    }
}

private fun resolveMvpHistoryFile() = run {
    val scriptsDataFile = Vars.dataDirectory.child("scripts").child("data").child(mvpHistoryFileName)
    if (scriptsDataFile.exists()) return@run scriptsDataFile
    val legacyFile = Vars.dataDirectory.child(mvpHistoryFileName)
    if (legacyFile.exists()) return@run legacyFile
    scriptsDataFile
}

private fun loadMvpHistory(): Map<String, MvpHistoryEntry> {
    val file = resolveMvpHistoryFile()
    if (!file.exists()) return emptyMap()
    val next = mutableMapOf<String, MvpHistoryEntry>()
    runCatching { file.readString() }.onSuccess { text ->
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split('\t', limit = 4)
            if (parts.size < 2) return@forEach
            val uuid = parts[0].trim()
            val monthMvp = parts[1].trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: return@forEach
            val totalMvp = parts.getOrNull(2)?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: monthMvp
            if (uuid.isNotEmpty()) {
                next[uuid] = MvpHistoryEntry(monthMvp, totalMvp)
            }
        }
    }.onFailure {
        logger.warning("voteOb: read ${mvpHistoryFileName} failed: ${it.message}")
    }
    return next
}

private fun formatVoteStat(value: Double): String =
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')

private fun formatVotePercent(value: Double): String =
    "%.2f".format(Locale.US, value.coerceAtLeast(0.0))

private fun supporterName(player: Player): String {
    val plain = Strings.stripColors(player.name).trim()
    return if (plain.isNotEmpty()) plain else player.uuid().take(8)
}

private fun captureLimitVoteMeta(event: VoteEvent): LimitVoteMeta {
    val agreeWeight = event.agree()
    val totalWeight = event.allCanVoteWeight()
    val agreePercent = if (totalWeight <= 1e-6) 100.0 else (agreeWeight / totalWeight * 100.0).coerceIn(0.0, 100.0)
    val mvpHistory = loadMvpHistory()
    val defaultHistory = MvpHistoryEntry(mvpHistoryDefault, mvpHistoryDefault)
    val topSupporter = event.voted.entries.asSequence()
        .filter { it.value == true && event.canVoteNow(it.key) }
        .map { it.key }
        .maxWithOrNull(compareBy<Player>(
            { (mvpHistory[it.uuid()] ?: defaultHistory).totalMvp },
            { (mvpHistory[it.uuid()] ?: defaultHistory).monthMvp },
            { supporterName(it).lowercase(Locale.ROOT) }
        ))
    if (topSupporter == null) {
        return LimitVoteMeta(agreeWeight = agreeWeight, agreePercent = agreePercent)
    }
    val topSupporterHistory = mvpHistory[topSupporter.uuid()] ?: defaultHistory
    return LimitVoteMeta(
        agreeWeight = agreeWeight,
        agreePercent = agreePercent,
        topSupporterName = supporterName(topSupporter),
        topSupporterMonthMvp = topSupporterHistory.monthMvp,
        topSupporterTotalMvp = topSupporterHistory.totalMvp,
    )
}

private fun buildQuitObExtDesc(reason: String, voteMeta: LimitVoteMeta?): String = buildString {
    append("[yellow]被限制时的理由: $reason")
    if (voteMeta == null) return@buildString
    append("\n[yellow]当时赞成的票数: [accent]")
    append(formatVoteStat(voteMeta.agreeWeight))
    append("[]([accent]")
    append(formatVotePercent(voteMeta.agreePercent))
    append("%[])")
    if (voteMeta.topSupporterName.isNotBlank()) {
        append("\n[yellow]赞成的mvp最大人为: ")
        append("[highlight]<")
        append(formatVoteStat(voteMeta.topSupporterMonthMvp))
        append("/")
        append(formatVoteStat(voteMeta.topSupporterTotalMvp))
        append(">[]")
        append(voteMeta.topSupporterName)
    }
}

private fun isAfkReason(reason: String): Boolean {
    val raw = reason.trim()
    return raw.contains("挂机") || raw.contains("afk", ignoreCase = true)
}

onEnable {
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "ob", "强制观战") {
        aliases = listOf("观战")
        usage = "<玩家名/id> <理由>"
        permission = "wayzer.vote.ob"
        body {
            val target = with(voteKick) { getTarget() }
            val reason = with(voteKick) { getInput("限制观战理由", "[red]投票限制他人需要理由".with()) }
            val player = player!!
            val event = VoteEvent(
                script, player,
                voteDesc = "强制观战(目标[red]{target.name}[yellow])".with("target" to target),
                extDesc = "[red]理由: [yellow]${reason}"
            )
            if (event.awaitResult()) {
                if (target.hasPermission("wayzer.admin.skipKick"))
                    return@body broadcast(
                        "[red]错误: {target.name}[red]为管理员, 如有问题请与服主联系".with("target" to target)
                    )
                applyLimit(target, reason, voteMeta = captureLimitVoteMeta(event))
                teams.changeTeam(target, teams.spectateTeam)
                broadcast(
                    "[yellow][提示][green]如目标用户继续捣乱，可以使用[gold]/vote kick {player.shortID}[]投票踢出".with(
                        "player" to target
                    )
                )
            }
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "quitOb", "解除强行观战限制(限本人)") {
        aliases = listOf("解除观战")
        body {
            val player = player!!
            val (reason, time, voteMeta) = resolveLimitInfo(player)
                ?: returnReply("[yellow]你未被限制游戏，无需解除".with())
            if (isAfkReason(reason)) {
                clearLimit(player)
                teams.changeTeam(player)
                returnReply("[green]检测到挂机类限制，已直接通过并解除观战".with())
            }
            val delta = Duration.between(time, Instant.now())
            val event = VoteEvent(
                script, player,
                voteDesc = "解除强制(已持续{delta 分钟})".with("delta" to delta),
                extDesc = buildQuitObExtDesc(reason, voteMeta)
            )
            if (event.awaitResult()) {
                clearLimit(player)
                teams.changeTeam(player)
            }
        }
    }
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    resolveLimitInfo(player)?.let { (reason, time) ->
        val delta = Duration.between(time, Instant.now())
        player.sendMessage(
            """
                [red]你已被限制强制观战.
                [yellow]投票原因: [white]{reason}({delta 分钟}前)
                [yellow]如有疑问，请在聊天区交流
                [green]可通过[gold]/vote quitOb[]投票，取消限制
            """.trimIndent().with("reason" to reason, "delta" to delta),
            MsgType.InfoMessage
        )
        team = teams.spectateTeam
    }
}
command("votekick", "(弃用)投票踢人") {
    this.usage = "<player...>"
    attr(ClientOnly)
    body {
        //Redirect
        arg = listOf("ob", *arg.toTypedArray())
        VoteEvent.VoteCommands.handle()
    }
}
command("forceOB", "管理指令：使某人强制观战") {
    usage = "<玩家名/id>"
    permission = "wayzer.admin.forceOb"
    body {
        try {
            val target = with(voteKick) { getTarget() }
            if (resolveLimitInfo(target) != null) {
                clearLimit(target)
                teams.changeTeam(target)
                returnReply("[green]已解除目标限制".with())
            }
            val reason = with(voteKick) { getInput("限制观战理由", "[red]投票限制他人需要理由".with()) }
            applyLimit(target, reason)
            teams.changeTeam(target, teams.spectateTeam)
            broadcast(
                "[red] 管理员强制{target.name}[red]成为观察者,原因: [yellow]{reason}"
                    .with("target" to target, "reason" to reason)
            )
        } catch (t: CommandInfo.Return) {
            throw t
        } catch (t: Throwable) {
            logger.warning("forceOB failed: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
            val hint = """
                [red]执行 /forceob 出现异常: {msg}
                [yellow]请清理 scripts/cache/*.ktc 后执行:
                sa load wayzer/vote --noCache
                sa load wayzer/cmds/voteKick --noCache
                sa load wayzer/cmds/voteOb --noCache
            """.trimIndent().with("msg" to (t.message ?: t::class.simpleName ?: "unknown"))
            returnReply(hint)
        }
    }
}
PermissionApi.registerDefault("wayzer.admin.forceOb", group = "@admin")
