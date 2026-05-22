
@file:Depends("DeterMination")
@file:Depends("wayzer")
@file:Depends("wayzer/vote")
@file:Depends("wayzer/map/betterTeam")
@file:Depends("wayzer/user/shortID")
@file:Depends("coreMindustry/menu")
@file:Depends("coreMindustry/utilTextInput")

package DeterMination

import arc.util.Strings
import arc.util.Time
import arc.util.Align
import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Event
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.PermissionApi
import coreMindustry.MenuV2
import coreMindustry.lib.hasPermission
import coreMindustry.lib.registerActionFilter
import coreMindustry.renderPaged
import mindustry.Vars.netServer
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import mindustry.world.Block
import mindustry.world.blocks.production.GenericCrafter
import mindustry.world.blocks.units.Reconstructor
import mindustry.world.blocks.units.UnitAssembler
import mindustry.world.blocks.units.UnitFactory
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.map.BetterTeam
import java.time.Duration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

name = "MvpTeamBalancer"

private val EPS = 1e-6
private val afkPrefixRegex = Regex("^<AFK>\\s*", RegexOption.IGNORE_CASE)
private val mvpPrefixRegex = Regex("^<[^>]+>\\s*")

private val permissionAddMvp = "determination.mvpstats.addmvp"
private val permissionDoRecover = "determination.mvpstats.dorecover"
private val permissionReTeam = "determination.mvpstats.rt"

private val balancerEnabled by config.key(true, "是否启用MVP智能分队")
private val activeVelocityThreshold by config.key(0.04f, "计入活跃时间的最小速度阈值")
private val afkIdleSeconds by config.key(180f, "AFK判定秒数(超过该时间未移动即AFK)")
private val afkMoveDistanceThreshold by config.key(0.2f, "AFK位移判定阈值(格)")
private val afkControlActiveSeconds by config.key(45f, "控兵保活秒数(该时间内不判定AFK)")
private val afkRecoverKeepTeamBuildThreshold by config.key(20.0, "AFK恢复时建造分超过该值则保持原队(严格大于)")
private val afkRecoverPopupDuration by config.key(4f, "AFK恢复重分配提示弹窗时长(秒)")
private val roundRebalanceDelaySeconds by config.key(2, "每局开局后自动重排延迟(秒)")
private val mvpBuildScoreThresholdExclusive by config.key(40.0, "结算全场MVP建筑分门槛(需严格大于)")
private val mvpActiveMinutesThresholdExclusive by config.key(2.0, "结算全场MVP活跃分钟门槛(需严格大于)")
private val broadcastMvpOnGameOver by config.key(true, "是否在结算时广播全场MVP")
private val broadcastRebalanceSummary by config.key(true, "是否在开局重排后广播摘要")
private val rankPageSize by config.key(20, "/rank 每页显示条数")

private val historyFileName by config.key("mvpTeamBalancer-history.tsv", "MVP历史文件名(存放于scripts/data)")
private val creditFileName by config.key("mvpTeamBalancer-credit.tsv", "信用数据文件名(存放于scripts/data)")
private val recoverFileName by config.key("mvpTeamBalancer-recover.tsv", "信用恢复申请文件名(存放于scripts/data)")

private val defaultMvpCount by config.key(0.5, "玩家默认MVP数量")
private val defaultCredit by config.key(2.0, "玩家默认信用")
private val addMvpMaxDelta by config.key(5.0, "/addMvp 单次最大增加数量")
private val addMvpRejectAbove by config.key(6.0, "/addMvp 拒绝阈值(当前值或目标值大于该值时拒绝；不影响对局结算+1)")

private val doubtBuildPenaltyPoints by config.key(100.0, "doubt成功后对目标当局建筑分削减值")
private val doubtCreditBaseLoss by config.key(0.2, "doubt成功后信用基础扣减")

private val recoverPageSize by config.key(8, "/dr 菜单每页显示条数")
private val recoverMaxGrantExclusive by config.key(0.5, "/dr 批准恢复时输入值必须严格小于该值")

private val teams = contextScript<BetterTeam>()
private val textInputScript = contextScript<coreMindustry.UtilTextInput>()

@Savable(false)
private val mvpHistory = mutableMapOf<String, Double>()
@Savable(false)
private val creditHistory = mutableMapOf<String, Double>()
@Savable(false)
private val breachHistory = mutableMapOf<String, Int>()
@Savable(false)
private val mvpNameCache = mutableMapOf<String, String>()

private data class RoundStat(
    var buildScore: Double = 0.0,
    var doubtPenalty: Double = 0.0,
    var activeTicks: Float = 0f,
    var lastTeamId: Int = -1,
    var lastName: String = "",
    val bucketScores: MutableMap<String, Double> = mutableMapOf()
)

private data class TeamBalance(
    var mvpSum: Double = 0.0,
    var members: Int = 0
)

private data class BuildRule(
    val bucket: String,
    val points: Double,
    val cap: Double
)

private data class CandidateScore(
    val uuid: String,
    val name: String,
    val team: Team,
    val rawBuildScore: Double,
    val doubtPenalty: Double,
    val netBuildScore: Double,
    val activeSeconds: Float,
    val mvpBefore: Double,
    val weight: Double,
    val credit: Double,
    val creditFactor: Double,
    val score: Double
)

private data class ResolvedTarget(
    val uuid: String,
    val displayName: String,
    val realName: String,
    val shortId: String,
    val online: Player?
)

private data class RecoverRequest(
    val uuid: String,
    val requestAt: Long,
    val requestName: String,
    val creditSnapshot: Double,
    val breachSnapshot: Int
)

@Savable(false)
private val roundStats = mutableMapOf<String, RoundStat>()

@Savable(false)
private val recoverRequests = mutableMapOf<String, RecoverRequest>()

@Savable(false)
private val sessionJoinAt = mutableMapOf<String, Long>()

@Savable(false)
private var roundStartTick = 0L

@Savable(false)
private var gameOverHandled = false

@Savable(false)
private var doubtVoteCommand: CommandInfo? = null

private data class AfkState(
    var idleTicks: Float = 0f,
    var afk: Boolean = false,
    var lastX: Float = Float.NaN,
    var lastY: Float = Float.NaN,
    var restoreName: String = ""
)

@Savable(false)
private val afkStates = mutableMapOf<String, AfkState>()
@Savable(false)
private val lastControlActionAt = mutableMapOf<String, Long>()

private fun formatDouble(value: Double, digits: Int): String = "%.${digits}f".format(Locale.US, value)
private fun formatMvp(value: Double): String = formatDouble(value, 2).trimEnd('0').trimEnd('.')

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    return sdf.format(Date(millis))
}

private fun formatDurationHuman(duration: Duration): String {
    val totalSec = duration.seconds.coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}

private fun normalizedName(raw: String?): String {
    val plain = Strings.stripColors(raw ?: "").trim()
    val noAfk = plain.replace(afkPrefixRegex, "").trim()
    return noAfk.ifEmpty { "<unknown>" }
}

private fun persistSafeName(raw: String): String =
    raw.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

private fun historyFile() = dataDirectory.child(historyFileName)
private fun creditFile() = dataDirectory.child(creditFileName)
private fun recoverFile() = dataDirectory.child(recoverFileName)

private fun playerHistory(uuid: String): Double = (mvpHistory[uuid] ?: defaultMvpCount).coerceAtLeast(defaultMvpCount)
private fun playerCredit(uuid: String): Double = (creditHistory[uuid] ?: defaultCredit).coerceAtLeast(0.0)
private fun playerBreach(uuid: String): Int = (breachHistory[uuid] ?: 0).coerceAtLeast(0)

private fun setPlayerHistory(uuid: String, value: Double) {
    val normalized = value.coerceAtLeast(defaultMvpCount)
    if (normalized <= defaultMvpCount + EPS) mvpHistory.remove(uuid)
    else mvpHistory[uuid] = normalized
}

private fun setPlayerCredit(uuid: String, value: Double) {
    val normalized = value.coerceAtLeast(0.0)
    if (abs(normalized - defaultCredit) <= EPS) creditHistory.remove(uuid)
    else creditHistory[uuid] = normalized
}

private fun setPlayerBreach(uuid: String, value: Int) {
    val normalized = value.coerceAtLeast(0)
    if (normalized == 0) breachHistory.remove(uuid)
    else breachHistory[uuid] = normalized
}

private fun creditFactor(uuid: String): Double {
    val credit = playerCredit(uuid)
    return if (credit < 1.0 - EPS) credit.coerceAtLeast(0.0) else 1.0
}

private fun displayName(uuid: String): String {
    val onlineName = Groups.player.find { it.uuid() == uuid }?.let { normalizedName(it.name) }
    if (onlineName != null) {
        mvpNameCache[uuid] = onlineName
        return onlineName
    }
    return mvpNameCache[uuid] ?: uuid.take(8)
}

private fun realName(uuid: String): String {
    val fromInfo = runCatching { netServer.admins.getInfoOptional(uuid)?.lastName }.getOrNull()
    val plainInfo = normalizedName(fromInfo)
    if (plainInfo != "<unknown>") return plainInfo
    return displayName(uuid)
}

private fun shortIdOf(uuid: String): String {
    val service = PlayerData.IGetUidByShortId.getOrNull() ?: return uuid.take(3)
    val name = mvpNameCache[uuid] ?: "player"
    return runCatching { service.getShortId(PlayerData(name, uuid)) }.getOrElse { uuid.take(3) }
}

private fun resolveByShortId(shortId: String): ResolvedTarget? {
    val data = PlayerData.findByShortId(shortId.trim()) ?: return null
    val uuid = data.uuid
    val display = normalizedName(data.player?.name ?: data.name)
    val real = realName(uuid)
    mvpNameCache[uuid] = display
    return ResolvedTarget(
        uuid = uuid,
        displayName = display,
        realName = real,
        shortId = data.shortId,
        online = data.player
    )
}
private fun loadHistoryFromFile() {
    mvpHistory.clear()
    mvpNameCache.clear()
    val file = historyFile()
    if (!file.exists()) return
    val text = runCatching { file.readString() }.getOrElse {
        logger.warning("MvpTeamBalancer: read history failed: ${it.message}")
        return
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2) return@forEach
        val uuid = parts[0].trim()
        val count = parts[1].trim().toDoubleOrNull() ?: return@forEach
        if (uuid.isEmpty()) return@forEach
        setPlayerHistory(uuid, count)
        if (parts.size >= 3) {
            val name = parts[2].trim()
            if (name.isNotEmpty()) mvpNameCache[uuid] = name
        }
    }
}

private fun saveHistoryToFile() {
    val file = historyFile()
    runCatching {
        file.parent().mkdirs()
        val content = buildString {
            appendLine("# uuid\tmvpCount\tlastName")
            mvpHistory.entries
                .asSequence()
                .filter { it.value > defaultMvpCount + EPS }
                .sortedBy { it.key }
                .forEach { (uuid, count) ->
                    val name = persistSafeName(mvpNameCache[uuid].orEmpty())
                    append(uuid).append('\t').append(formatMvp(count))
                    if (name.isNotEmpty()) append('\t').append(name)
                    appendLine()
                }
        }
        file.writeString(content, false)
    }.onFailure {
        logger.warning("MvpTeamBalancer: save history failed: ${it.message}")
    }
}

private fun loadCreditFromFile() {
    creditHistory.clear()
    breachHistory.clear()
    val file = creditFile()
    if (!file.exists()) return
    val text = runCatching { file.readString() }.getOrElse {
        logger.warning("MvpTeamBalancer: read credit failed: ${it.message}")
        return
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val parts = line.split('\t', limit = 4)
        if (parts.size < 3) return@forEach
        val uuid = parts[0].trim()
        val credit = parts[1].trim().toDoubleOrNull() ?: return@forEach
        val breach = parts[2].trim().toIntOrNull() ?: return@forEach
        if (uuid.isEmpty()) return@forEach
        setPlayerCredit(uuid, credit)
        setPlayerBreach(uuid, breach)
        if (parts.size >= 4) {
            val name = parts[3].trim()
            if (name.isNotEmpty()) mvpNameCache[uuid] = name
        }
    }
}

private fun saveCreditToFile() {
    val file = creditFile()
    runCatching {
        file.parent().mkdirs()
        val allUuid = (creditHistory.keys + breachHistory.keys).distinct().sorted()
        val content = buildString {
            appendLine("# uuid\tcredit\tbreachCount\tlastName")
            allUuid.forEach { uuid ->
                val credit = playerCredit(uuid)
                val breach = playerBreach(uuid)
                if (abs(credit - defaultCredit) <= EPS && breach == 0) return@forEach
                val name = persistSafeName(mvpNameCache[uuid].orEmpty())
                append(uuid)
                    .append('\t').append(formatMvp(credit))
                    .append('\t').append(breach)
                if (name.isNotEmpty()) append('\t').append(name)
                appendLine()
            }
        }
        file.writeString(content, false)
    }.onFailure {
        logger.warning("MvpTeamBalancer: save credit failed: ${it.message}")
    }
}

private fun loadRecoverRequests() {
    recoverRequests.clear()
    val file = recoverFile()
    if (!file.exists()) return
    val text = runCatching { file.readString() }.getOrElse {
        logger.warning("MvpTeamBalancer: read recover requests failed: ${it.message}")
        return
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val parts = line.split('\t', limit = 5)
        if (parts.size < 5) return@forEach
        val uuid = parts[0].trim()
        val requestAt = parts[1].trim().toLongOrNull() ?: return@forEach
        val creditSnap = parts[2].trim().toDoubleOrNull() ?: return@forEach
        val breachSnap = parts[3].trim().toIntOrNull() ?: return@forEach
        val requestName = parts[4].trim().ifEmpty { uuid.take(8) }
        if (uuid.isEmpty()) return@forEach
        recoverRequests[uuid] = RecoverRequest(
            uuid = uuid,
            requestAt = requestAt,
            requestName = requestName,
            creditSnapshot = creditSnap,
            breachSnapshot = breachSnap
        )
    }
}

private fun saveRecoverRequests() {
    val file = recoverFile()
    runCatching {
        file.parent().mkdirs()
        val content = buildString {
            appendLine("# uuid\trequestAtMillis\tcreditSnapshot\tbreachSnapshot\trequestName")
            recoverRequests.values
                .sortedBy { it.requestAt }
                .forEach { req ->
                    append(req.uuid)
                        .append('\t').append(req.requestAt)
                        .append('\t').append(formatMvp(req.creditSnapshot))
                        .append('\t').append(req.breachSnapshot)
                        .append('\t').append(persistSafeName(req.requestName))
                        .appendLine()
                }
        }
        file.writeString(content, false)
    }.onFailure {
        logger.warning("MvpTeamBalancer: save recover requests failed: ${it.message}")
    }
}

private fun ensureRoundStat(player: Player): RoundStat {
    val stat = roundStats.getOrPut(player.uuid()) { RoundStat() }
    stat.lastTeamId = player.team().id
    val name = normalizedName(player.name)
    stat.lastName = name
    mvpNameCache[player.uuid()] = name
    return stat
}

private fun ensureRoundStat(uuid: String, teamId: Int, name: String): RoundStat {
    val stat = roundStats.getOrPut(uuid) { RoundStat() }
    stat.lastTeamId = teamId
    stat.lastName = name
    mvpNameCache[uuid] = name
    return stat
}

private fun updateRoundSnapshot() {
    Groups.player.forEach { ensureRoundStat(it) }
}

private fun currentNetBuildScore(uuid: String): Double {
    val stat = roundStats[uuid] ?: return 0.0
    return (stat.buildScore - stat.doubtPenalty).coerceAtLeast(0.0)
}

private fun afkStateOf(player: Player): AfkState = afkStates.getOrPut(player.uuid()) { AfkState() }
private fun isAfk(uuid: String): Boolean = afkStates[uuid]?.afk == true

private fun isControlAction(type: Administration.ActionType): Boolean {
    return when (type) {
        Administration.ActionType.control,
        Administration.ActionType.command,
        Administration.ActionType.commandUnits,
        Administration.ActionType.commandBuilding -> true
        else -> false
    }
}

private fun isControlRecentlyActive(uuid: String, now: Long): Boolean {
    val keepMs = (afkControlActiveSeconds.coerceAtLeast(0f) * 1000f).toLong()
    if (keepMs <= 0L) return false
    val last = lastControlActionAt[uuid] ?: return false
    return now - last <= keepMs
}

private fun afkDisplayName(player: Player, base: String): String {
    val raw = normalizedName(base)
    val clean = raw.replace(mvpPrefixRegex, "").trim()
    val mvp = "[highlight]<${formatMvp(playerHistory(player.uuid()))}>[]"
    return if (clean.isEmpty()) "$mvp[gray]<AFK>[][white]" else "$mvp[gray]<AFK>[] $clean[white]"
}

private fun restoreAfkName(player: Player, state: AfkState) {
    if (!state.afk) return
    val restore = state.restoreName.ifBlank { normalizedName(player.name) }
    player.name = restore
    state.afk = false
    state.restoreName = ""
}

private fun ensureAfkName(player: Player, state: AfkState) {
    if (!state.afk) return
    if (state.restoreName.isBlank()) {
        state.restoreName = normalizedName(player.name)
    }
    val expected = afkDisplayName(player, state.restoreName)
    if (player.name != expected) {
        player.name = expected
    }
}

private fun markAfk(player: Player, state: AfkState) {
    if (state.afk) {
        ensureAfkName(player, state)
        return
    }
    state.afk = true
    state.restoreName = normalizedName(player.name)
    ensureAfkName(player, state)
}

private fun popupAfkRecover(player: Player, text: String) {
    val con = player.con ?: return
    val duration = afkRecoverPopupDuration.coerceAtLeast(1f)
    Call.infoPopup(con, text, duration, Align.center, 0, 0, 0, 0)
}

private fun reassignAfterAfkRecover(player: Player) {
    if (!balancerEnabled || !state.rules.pvp) return
    if (player.team() == teams.spectateTeam) return
    if (isAfk(player.uuid())) return
    val netBuildScore = currentNetBuildScore(player.uuid())
    if (netBuildScore > afkRecoverKeepTeamBuildThreshold) {
        popupAfkRecover(
            player,
            "[accent]AFK结束[] 建造分[green]{build}[] > {limit}，保持原队".with(
                "build" to formatMvp(netBuildScore),
                "limit" to formatMvp(afkRecoverKeepTeamBuildThreshold)
            ).toString()
        )
        return
    }

    val allTeams = availablePvpTeams()
    if (allTeams.isEmpty()) return

    val activePlayers = Groups.player
        .asSequence()
        .filter { it.team() != teams.spectateTeam }
        .filter { !isAfk(it.uuid()) }
        .toList()
    if (activePlayers.none { it.uuid() == player.uuid() }) return

    val playerCount = activePlayers.size
    val teamList = teamPoolFor(playerCount, allTeams)
    if (teamList.isEmpty()) return
    val enforceMinTwo = shouldEnforceMinTwo(playerCount, allTeams.size)

    val balance = buildTeamBalance(teamList, activePlayers, excludeUuid = player.uuid())
    val target = pickTeamForAssign(balance, enforceMinTwo) ?: return
    val old = player.team()
    if (old == target) {
        popupAfkRecover(
            player,
            "[accent]AFK结束[] 已重算分队：维持 [green]{team}[]".with("team" to target.localized()).toString()
        )
        return
    }

    teams.changeTeam(player, target)
    popupAfkRecover(
        player,
        "[accent]AFK结束[] 已重算分队：[yellow]{from}[] -> [green]{to}[]".with(
            "from" to old.localized(),
            "to" to target.localized()
        ).toString()
    )
}

private fun clearAfkVisuals() {
    Groups.player.forEach { player ->
        val state = afkStates[player.uuid()] ?: return@forEach
        restoreAfkName(player, state)
    }
}

private fun resetRoundState() {
    clearAfkVisuals()
    afkStates.clear()
    lastControlActionAt.clear()
    roundStats.clear()
    roundStartTick = state.tick.toLong()
    gameOverHandled = false
    updateRoundSnapshot()
}

// f(x) = max(0.6, 1 + (10/(x+10)-0.5) - 0.05*sin((PI/2)*clamp(x-10,0,20)/20))
private fun calcWeight(mvpBefore: Double): Double {
    val x = mvpBefore.coerceAtLeast(0.0)
    val baseDecay = 10.0 / (x + 10.0) - 0.5
    val clamped = (x - 10.0).coerceIn(0.0, 20.0) / 20.0
    val adjust = 0.05 * sin((PI / 2.0) * clamped)
    return max(0.6, 1.0 + baseDecay - adjust)
}

private fun calcRoundSeconds(): Float {
    val elapsedTick = (state.tick.toLong() - roundStartTick).coerceAtLeast(1L)
    return (elapsedTick / 60f).coerceAtLeast(1f)
}

private fun containsOnly(items: Set<String>, allowed: Set<String>): Boolean {
    return items.isNotEmpty() && items.all { it in allowed }
}

private fun requirementItems(block: Block): Set<String> {
    return block.requirements
        .asSequence()
        .map { it.item.name.lowercase(Locale.ROOT) }
        .toSet()
}

private fun classifyCrafterRule(block: Block): BuildRule? {
    val req = requirementItems(block)
    if (req.isEmpty()) return null

    val serpuloAdvanced = setOf("plastanium", "phase-fabric", "surge-alloy")
    val erekirAdvanced = setOf("oxide", "carbide", "surge-alloy")

    if (req.any { it in erekirAdvanced }) {
        return BuildRule("crafter-erekir-adv", 6.0, 200.0)
    }
    if ("thorium" in req) {
        return if (req.any { it in setOf("beryllium", "tungsten", "oxide", "carbide") }) {
            BuildRule("crafter-erekir-thorium", 4.0, 200.0)
        } else {
            BuildRule("crafter-serpulo-ti-th", 2.0, 200.0)
        }
    }
    if (req.any { it in serpuloAdvanced }) {
        return BuildRule("crafter-serpulo-adv", 2.0, 300.0)
    }
    if ("titanium" in req) {
        return BuildRule("crafter-serpulo-ti-th", 2.0, 200.0)
    }
    if (containsOnly(req, setOf("beryllium", "graphite", "tungsten"))) {
        return if ("tungsten" in req) {
            BuildRule("crafter-erekir-be-gr-w", 3.0, 100.0)
        } else {
            BuildRule("crafter-erekir-be-gr", 2.0, 100.0)
        }
    }
    if (containsOnly(req, setOf("copper", "lead"))) {
        return BuildRule("crafter-serpulo-cu-pb", 1.0, 100.0)
    }
    return null
}

private fun classifyUnitFactoryRule(block: Block): BuildRule? {
    val name = block.name.lowercase(Locale.ROOT)
    return when (name) {
        "ground-factory", "air-factory", "naval-factory", "additive-reconstructor" ->
            BuildRule("unit-serpulo-t12", 1.0, 100.0)

        "multiplicative-reconstructor" ->
            BuildRule("unit-serpulo-t3", 20.0, 40.0)

        "exponential-reconstructor", "tetrative-reconstructor" ->
            BuildRule("unit-serpulo-t45", 40.0, 80.0)

        "tank-fabricator", "ship-fabricator", "mech-fabricator", "tank-refabricator" ->
            BuildRule("unit-erekir-t12", 10.0, 100.0)

        "ship-refabricator" ->
            BuildRule("unit-erekir-t3", 30.0, 30.0)

        "mech-refabricator", "prime-refabricator", "tank-assembler", "ship-assembler", "mech-assembler" ->
            BuildRule("unit-erekir-t45", 20.0, 100.0)

        else -> {
            when {
                name.endsWith("-factory") -> BuildRule("unit-serpulo-t12", 1.0, 100.0)
                name.contains("reconstructor") && name.contains("multiplicative") -> BuildRule("unit-serpulo-t3", 20.0, 40.0)
                name.contains("reconstructor") && (name.contains("exponential") || name.contains("tetrative")) -> BuildRule("unit-serpulo-t45", 40.0, 80.0)
                name.endsWith("-fabricator") -> BuildRule("unit-erekir-t12", 10.0, 100.0)
                name.endsWith("-refabricator") -> BuildRule("unit-erekir-t45", 20.0, 100.0)
                name.endsWith("-assembler") -> BuildRule("unit-erekir-t45", 20.0, 100.0)
                else -> null
            }
        }
    }
}

private fun classifyBuildRule(block: Block): BuildRule? {
    return when {
        block is UnitFactory || block is Reconstructor || block is UnitAssembler -> classifyUnitFactoryRule(block)
        block is GenericCrafter -> classifyCrafterRule(block)
        else -> null
    }
}

private fun applyBuildRule(stat: RoundStat, rule: BuildRule): Double {
    val current = stat.bucketScores[rule.bucket] ?: 0.0
    val remain = (rule.cap - current).coerceAtLeast(0.0)
    if (remain <= EPS) return 0.0
    val added = rule.points.coerceAtMost(remain)
    if (added <= EPS) return 0.0
    stat.bucketScores[rule.bucket] = current + added
    stat.buildScore += added
    return added
}
private fun shouldEnforceMinTwo(playerCount: Int, teamCount: Int): Boolean {
    if (playerCount <= 0 || teamCount <= 0) return false
    return playerCount.toDouble() / teamCount.toDouble() > 1.0
}

private fun teamPoolFor(playerCount: Int, allTeams: List<Team>): List<Team> {
    if (playerCount <= 0 || allTeams.isEmpty()) return emptyList()
    val enforceMinTwo = shouldEnforceMinTwo(playerCount, allTeams.size)
    val useCountByPlayer = if (enforceMinTwo) {
        (playerCount / 2).coerceAtLeast(1)
    } else {
        playerCount.coerceAtLeast(1)
    }
    return allTeams.take(useCountByPlayer.coerceAtMost(allTeams.size))
}

private fun availablePvpTeams(): List<Team> {
    return teams.allTeam
        .asSequence()
        .filter { it != Team.derelict && it != teams.spectateTeam }
        .sortedBy { it.id }
        .toList()
}

private fun pickTeam(balance: Map<Team, TeamBalance>): Team? {
    return balance.entries
        .minWithOrNull(
            compareBy<Map.Entry<Team, TeamBalance>> { it.value.mvpSum }
                .thenBy { it.value.members }
                .thenBy { it.key.id }
        )?.key
}

private fun pickTeamForAssign(balance: Map<Team, TeamBalance>, enforceMinTwo: Boolean): Team? {
    if (enforceMinTwo) {
        val underFilled = balance.entries
            .filter { it.value.members < 2 }
            .minWithOrNull(
                compareBy<Map.Entry<Team, TeamBalance>> { it.value.members }
                    .thenBy { it.value.mvpSum }
                    .thenBy { it.key.id }
            )?.key
        if (underFilled != null) return underFilled
    }
    return pickTeam(balance)
}

private fun buildTeamBalance(
    teamList: List<Team>,
    players: Iterable<Player>,
    excludeUuid: String? = null
): MutableMap<Team, TeamBalance> {
    val balance = teamList.associateWith { TeamBalance() }.toMutableMap()
    players.forEach { p ->
        if (excludeUuid != null && p.uuid() == excludeUuid) return@forEach
        if (isAfk(p.uuid())) return@forEach
        val t = p.team()
        val slot = balance[t] ?: return@forEach
        slot.mvpSum += playerHistory(p.uuid())
        slot.members += 1
    }
    return balance
}

private fun runRoundRebalance() {
    if (!balancerEnabled || !state.rules.pvp) return
    val players = Groups.player
        .asSequence()
        .filter { it.team() != teams.spectateTeam }
        .filter { !isAfk(it.uuid()) }
        .sortedWith(
            compareByDescending<Player> { playerHistory(it.uuid()) }
                .thenBy { it.uuid() }
        )
        .toList()
    if (players.isEmpty()) return

    val allTeams = availablePvpTeams()
    if (allTeams.isEmpty()) return
    val teamList = teamPoolFor(players.size, allTeams)
    if (teamList.isEmpty()) return
    val enforceMinTwo = shouldEnforceMinTwo(players.size, allTeams.size)

    val balance = teamList.associateWith { TeamBalance() }.toMutableMap()
    val assign = mutableMapOf<String, Team>()
    players.forEach { p ->
        val target = pickTeamForAssign(balance, enforceMinTwo) ?: return@forEach
        assign[p.uuid()] = target
        val slot = balance.getValue(target)
        slot.mvpSum += playerHistory(p.uuid())
        slot.members += 1
    }

    var moved = 0
    players.forEach { p ->
        val target = assign[p.uuid()] ?: return@forEach
        if (p.team() == target) return@forEach
        teams.changeTeam(p, target)
        moved += 1
    }

    if (broadcastRebalanceSummary) {
        val summary = teamList.joinToString(" | ") { t ->
            val slot = balance[t] ?: TeamBalance()
            "${t.localized()}:ΣMVP=${formatMvp(slot.mvpSum)},人数=${slot.members}"
        }
        broadcast(
            "[accent]MVP智能分队[] 已执行，重排 [yellow]{moved}[] 人。\n[lightgray]{summary}[]"
                .with("moved" to moved, "summary" to summary),
            MsgType.InfoMessage,
            quite = true
        )
    }
}

private fun settleRoundMvp(): CandidateScore? {
    val roundSeconds = calcRoundSeconds()
    val minActiveSeconds = mvpActiveMinutesThresholdExclusive * 60.0
    val candidates = mutableListOf<CandidateScore>()

    roundStats.forEach { (uuid, stat) ->
        val rawBuildScore = stat.buildScore
        val netBuildScore = (rawBuildScore - stat.doubtPenalty).coerceAtLeast(0.0)
        if (netBuildScore <= mvpBuildScoreThresholdExclusive) return@forEach

        val activeSeconds = (stat.activeTicks / 60f).coerceAtLeast(0f)
        if (activeSeconds.toDouble() <= minActiveSeconds) return@forEach

        val team = Team.all.getOrNull(stat.lastTeamId) ?: return@forEach
        if (team == Team.derelict || team == teams.spectateTeam) return@forEach

        val mvpBefore = playerHistory(uuid)
        val weight = calcWeight(mvpBefore)
        val credit = playerCredit(uuid)
        val creditMul = creditFactor(uuid)
        val score = netBuildScore * (activeSeconds / roundSeconds.toDouble()) * weight * creditMul
        val name = Groups.player.find { it.uuid() == uuid }?.let { normalizedName(it.name) }
            ?: stat.lastName.ifEmpty { uuid.take(6) }

        candidates += CandidateScore(
            uuid = uuid,
            name = name,
            team = team,
            rawBuildScore = rawBuildScore,
            doubtPenalty = stat.doubtPenalty,
            netBuildScore = netBuildScore,
            activeSeconds = activeSeconds,
            mvpBefore = mvpBefore,
            weight = weight,
            credit = credit,
            creditFactor = creditMul,
            score = score
        )
    }

    val winner = candidates.sortedWith(
        compareByDescending<CandidateScore> { it.score }
            .thenByDescending { it.netBuildScore }
            .thenByDescending { it.activeSeconds }
            .thenBy { it.mvpBefore }
            .thenBy { it.uuid }
    ).firstOrNull()

    if (winner != null) {
        setPlayerHistory(winner.uuid, winner.mvpBefore + 1.0)
        mvpNameCache[winner.uuid] = winner.name
        saveHistoryToFile()
    }

    return winner
}

private fun broadcastMvpResult(result: CandidateScore?) {
    if (!broadcastMvpOnGameOver) return
    if (result == null) {
        val msg = "[accent]本局MVP结算[] 无人满足条件 [lightgray](建筑分 > {build}, 活跃 > {active} 分钟)[]".with(
            "build" to formatMvp(mvpBuildScoreThresholdExclusive),
            "active" to formatDouble(mvpActiveMinutesThresholdExclusive, 1)
        )
        broadcast(msg, MsgType.InfoMessage, quite = true)
        return
    }
    val creditPart = if (result.creditFactor < 1.0 - EPS) {
        "[scarlet]信用={credit}(乘{creditFactor})[]".with(
            "credit" to formatMvp(result.credit),
            "creditFactor" to formatMvp(result.creditFactor)
        ).toString()
    } else {
        "[lightgray]信用={credit}[]".with("credit" to formatMvp(result.credit)).toString()
    }
    broadcast(
        (
            "[accent]本局MVP[]: [green]{name}[] [lightgray](队伍:{team}, 建筑分:{build}, 质疑扣分:{penalty}, 活跃:{active}s, " +
                "x:{before}, 权重:{weight}, {creditPart}, 分数:{score})[]"
            ).with(
            "name" to result.name,
            "team" to result.team.localized(),
            "build" to formatMvp(result.netBuildScore),
            "penalty" to formatMvp(result.doubtPenalty),
            "active" to formatDouble(result.activeSeconds.toDouble(), 1),
            "before" to formatMvp(result.mvpBefore),
            "weight" to formatDouble(result.weight, 3),
            "creditPart" to creditPart,
            "score" to formatDouble(result.score, 3)
        ),
        MsgType.InfoMessage,
        quite = true
    )
}

private fun formatRankPage(page: Int, selfUuid: String? = null): String {
    val entries = mvpHistory.entries
        .asSequence()
        .filter { it.value > defaultMvpCount + EPS }
        .sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { realName(it.key) }
                .thenBy { it.key }
        )
        .toList()
    if (entries.isEmpty()) {
        val selfText = selfUuid?.let { uuid ->
            "\n[lightgray]我的排名: [yellow]未上榜[] (MVP=${formatMvp(playerHistory(uuid))}, 信用=${formatMvp(playerCredit(uuid))})"
        } ?: ""
        return "[yellow]暂无 MVP 记录$selfText"
    }

    val pageSize = rankPageSize.coerceAtLeast(5)
    val totalPage = ((entries.size - 1) / pageSize) + 1
    val realPage = page.coerceIn(1, totalPage)
    val from = (realPage - 1) * pageSize
    val to = (from + pageSize).coerceAtMost(entries.size)

    val lines = entries.subList(from, to).mapIndexed { idx, entry ->
        val rank = from + idx + 1
        val uuid = entry.key
        val credit = playerCredit(uuid)
        val name = realName(uuid)
        val uuidHint = uuid.take(6)
        val creditText = if (credit < 1.0 - EPS) {
            "[scarlet]${formatMvp(credit)}[]"
        } else {
            "[lightgray]${formatMvp(credit)}[]"
        }
        "[yellow]${rank}. [green]${name}[] [lightgray](${uuidHint})[] : MVP=[accent]${formatMvp(entry.value)}[]  信用=${creditText}"
    }

    val selfLine = selfUuid?.let { uuid ->
        val selfIndex = entries.indexOfFirst { it.key == uuid }
        if (selfIndex >= 0) {
            val rank = selfIndex + 1
            val value = entries[selfIndex].value
            val credit = playerCredit(uuid)
            "[lightgray]我的排名: [yellow]${rank}[]  MVP=[accent]${formatMvp(value)}[]  信用=${formatMvp(credit)}"
        } else {
            "[lightgray]我的排名: [yellow]未上榜[] (MVP=${formatMvp(playerHistory(uuid))}, 信用=${formatMvp(playerCredit(uuid))})"
        }
    } ?: ""

    return "[accent]MVP 排行[] [lightgray]第 ${realPage}/${totalPage} 页，总 ${entries.size} 人[]\n" +
        lines.joinToString("\n") +
        "\n[lightgray]使用 /rank <页码> 翻页[]" +
        if (selfLine.isEmpty()) "" else "\n$selfLine"
}

private fun applyDoubtPenalty(targetUuid: String, targetName: String, targetTeamId: Int) {
    val penalty = doubtBuildPenaltyPoints.coerceAtLeast(0.0)
    val stat = ensureRoundStat(targetUuid, targetTeamId, targetName)
    stat.doubtPenalty += penalty

    val beforeBreach = playerBreach(targetUuid)
    val loss = doubtCreditBaseLoss.coerceAtLeast(0.0) * (1.0 + beforeBreach.toDouble())
    val beforeCredit = playerCredit(targetUuid)
    val afterCredit = (beforeCredit - loss).coerceAtLeast(0.0)
    setPlayerCredit(targetUuid, afterCredit)
    setPlayerBreach(targetUuid, beforeBreach + 1)
    mvpNameCache[targetUuid] = targetName
    saveCreditToFile()

    val showName = realName(targetUuid)
    broadcast(
        "[yellow]质疑通过: [green]{name}[] 本局建筑分 -{penalty}，信用 {before} -> {after}（违约次数 {breach}）".with(
            "name" to showName,
            "penalty" to formatMvp(penalty),
            "before" to formatMvp(beforeCredit),
            "after" to formatMvp(afterCredit),
            "breach" to (beforeBreach + 1)
        ),
        MsgType.InfoMessage,
        quite = true
    )
}

registerVar("scoreBroad.ext.mvp-build-self", "积分板: 显示自己的MVP与建造分", DynamicVar {
    val player = VarToken("player").get() as? Player
        ?: return@DynamicVar null
    val uuid = player.uuid()
    val mvp = formatMvp(playerHistory(uuid))
    val build = formatMvp(currentNetBuildScore(uuid))
    "{cK}我的MVP: {cV}{mvp} {cK}建造分: {cV}{build}".with(
        "mvp" to mvp,
        "build" to build
    )
})

private fun registerDoubtVoteCommand() {
    val commands = VoteEvent.VoteCommands
    commands.getSub("doubt")?.let { commands.removeSub(it) }

    val cmd = CommandInfo(this, "doubt", "质疑玩家(降低其本局建筑分与信用)") {
        aliases = listOf("质疑")
        usage = "<ShortID>"
        requirePermission("wayzer.vote.doubt")
        body {
            if (state.gameOver) returnReply("[red]本局已结束，无法发起质疑".with())
            val starter = player ?: returnReply("[red]仅玩家可发起质疑投票".with())
            val shortId = arg.firstOrNull()?.trim().orEmpty()
            if (shortId.isEmpty()) returnReply("[red]用法: /vote doubt <ShortID>".with())

            val target = resolveByShortId(shortId)
                ?: returnReply("[red]找不到目标玩家，请检查ShortID".with())
            val targetPlayer = target.online
                ?: returnReply("[red]目标玩家需在线才能被质疑".with())
            if (targetPlayer.uuid() == starter.uuid()) returnReply("[red]不能质疑自己".with())
            if (targetPlayer.team() == teams.spectateTeam) returnReply("[red]观察者不能作为质疑目标".with())

            val targetUuid = targetPlayer.uuid()
            val targetName = normalizedName(targetPlayer.name)
            val targetTeamId = targetPlayer.team().id
            val beforeBreach = playerBreach(targetUuid)
            val loss = doubtCreditBaseLoss.coerceAtLeast(0.0) * (1.0 + beforeBreach.toDouble())

            val event = VoteEvent(
                thisScript,
                starter,
                voteDesc = "质疑([red]{target}[yellow])".with("target" to targetPlayer),
                extDesc = "[lightgray]通过后: 本局建筑分 -${formatMvp(doubtBuildPenaltyPoints)}，信用 -${formatMvp(loss)}".with().toString()
            )
            if (event.awaitResult()) {
                applyDoubtPenalty(targetUuid, targetName, targetTeamId)
            }
        }
    }

    commands += cmd
    doubtVoteCommand = cmd
}

private fun unregisterDoubtVoteCommand() {
    doubtVoteCommand?.let { VoteEvent.VoteCommands.removeSub(it) }
    doubtVoteCommand = null
}

private fun recoverSorted(): List<RecoverRequest> =
    recoverRequests.values.sortedWith(compareBy<RecoverRequest> { it.requestAt }.thenBy { it.uuid })

private fun recoverListText(): String {
    val items = recoverSorted()
    if (items.isEmpty()) return "[yellow]当前没有待处理恢复申请"
    val lines = items.mapIndexed { idx, req ->
        val currentCredit = playerCredit(req.uuid)
        "[yellow]${idx + 1}.[] [green]${realName(req.uuid)}[] [gray](${shortIdOf(req.uuid)})[] " +
            "credit=${formatMvp(currentCredit)} requestAt=${formatTime(req.requestAt)}"
    }
    return "[accent]待处理恢复申请[] 共 ${items.size} 条\n" + lines.joinToString("\n")
}

private fun resolveRecoverUuid(token: String): String? {
    val raw = token.trim()
    if (raw.isEmpty()) return null
    if (raw in recoverRequests) return raw
    val byShort = PlayerData.IGetUidByShortId.getOrNull()?.getUidByShortId(raw)
    if (byShort != null && byShort in recoverRequests) return byShort
    return recoverRequests.keys.firstOrNull {
        realName(it).equals(raw, ignoreCase = true) || displayName(it).equals(raw, ignoreCase = true)
    }
}

private fun approveRecover(uuid: String, delta: Double, operatorName: String): String {
    recoverRequests[uuid] ?: return "[red]申请已不存在"
    if (delta <= 0.0 || delta >= recoverMaxGrantExclusive - EPS) {
        return "[red]恢复值必须 > 0 且 < {max}".with("max" to formatMvp(recoverMaxGrantExclusive)).toString()
    }
    val before = playerCredit(uuid)
    val after = (before + delta).coerceAtMost(defaultCredit)
    setPlayerCredit(uuid, after)
    recoverRequests.remove(uuid)
    saveCreditToFile()
    saveRecoverRequests()
    val name = realName(uuid)
    return "[green]已批准 {name}[green] 的信用恢复: {before} -> {after} (+{delta}) by {op}".with(
        "name" to name,
        "before" to formatMvp(before),
        "after" to formatMvp(after),
        "delta" to formatMvp(delta),
        "op" to operatorName
    ).toString()
}

private fun rejectRecover(uuid: String, operatorName: String): String {
    val req = recoverRequests.remove(uuid) ?: return "[red]申请已不存在"
    saveRecoverRequests()
    return "[yellow]已拒绝 {name}[yellow] 的信用恢复申请 by {op}".with(
        "name" to realName(req.uuid),
        "op" to operatorName
    ).toString()
}

private suspend fun openRecoverDecisionMenu(admin: Player, req: RecoverRequest) {
    var action: String? = null
    MenuV2(admin, followup = true) {
        title = "处理恢复申请"
        msg = "[green]{name}[] [gray]({sid})[]\n" +
            "当前信用: {credit}\n" +
            "申请时信用: {snapCredit}\n" +
            "申请时违约次数: {snapBreach}\n" +
            "申请时间: {time}".with(
                "name" to realName(req.uuid),
                "sid" to shortIdOf(req.uuid),
                "credit" to formatMvp(playerCredit(req.uuid)),
                "snapCredit" to formatMvp(req.creditSnapshot),
                "snapBreach" to req.breachSnapshot,
                "time" to formatTime(req.requestAt)
            ).toString()
        option("批准并输入恢复值(<${formatMvp(recoverMaxGrantExclusive)})") {
            action = "approve"
            close()
        }
        option("拒绝并移除申请") {
            action = "reject"
            close()
        }
        option("返回") { close() }
    }.send().awaitWithTimeout()

    when (action) {
        "reject" -> {
            admin.sendMessage(rejectRecover(req.uuid, normalizedName(admin.name)).with())
        }

        "approve" -> {
            val tip = "请输入恢复值 ( >0 且 < ${formatMvp(recoverMaxGrantExclusive)} )"
            val input = with(textInputScript) {
                textInput(admin, "信用恢复审批", tip, "", 12, false)
            }
            if (input == null) {
                admin.sendMessage("[yellow]已取消输入".with())
                return
            }
            val delta = input.trim().toDoubleOrNull()
            if (delta == null || delta <= 0.0 || delta >= recoverMaxGrantExclusive - EPS) {
                admin.sendMessage("[red]输入无效，恢复值必须 >0 且 < {max}".with("max" to formatMvp(recoverMaxGrantExclusive)))
                return
            }
            admin.sendMessage(approveRecover(req.uuid, delta, normalizedName(admin.name)).with())
        }
    }
}

private suspend fun openRecoverMenu(admin: Player, initialPage: Int = 1) {
    val prePage = recoverPageSize.coerceAtLeast(1)
    MenuV2(admin, followup = true) {
        var selectedPage by stateKey(initialPage.coerceAtLeast(1), keyPrefix = "recover-")
        title = "信用恢复列表"

        val list = recoverSorted()
        if (list.isEmpty()) {
            msg = "[yellow]当前没有待处理恢复申请"
            option("关闭") { close() }
            return@MenuV2
        }

        msg = "[lightgray]点击条目进入审批流程[]"
        renderPaged(list, selectedPage, prePage, key = "recover-list") { req ->
            val line = "[green]{name}[] [gray]({sid})[] [lightgray]信用:{credit} 申请:{time}[]".with(
                "name" to realName(req.uuid),
                "sid" to shortIdOf(req.uuid),
                "credit" to formatMvp(playerCredit(req.uuid)),
                "time" to formatTime(req.requestAt)
            ).toString()
            option(line) {
                openRecoverDecisionMenu(admin, req)
                refresh()
            }
        }
        option("关闭") { close() }
    }.send().awaitWithTimeout()
}

private fun handleRecoverActionArgs(args: List<String>, operatorName: String): String? {
    if (args.isEmpty()) return null
    val action = args[0].lowercase(Locale.ROOT)
    if (action !in setOf("approve", "ok", "pass", "批准", "reject", "deny", "拒绝", "remove")) {
        return null
    }
    if (args.size < 2) {
        return "[red]参数不足。用法: /dr approve <ShortID/UUID> <Num> 或 /dr reject <ShortID/UUID>"
    }
    val uuid = resolveRecoverUuid(args[1])
        ?: return "[red]未找到对应恢复申请: ${args[1]}"

    return if (action in setOf("reject", "deny", "拒绝", "remove")) {
        rejectRecover(uuid, operatorName)
    } else {
        if (args.size < 3) return "[red]参数不足。用法: /dr approve <ShortID/UUID> <Num>"
        val delta = args[2].toDoubleOrNull()
            ?: return "[red]Num 必须是数字"
        approveRecover(uuid, delta, operatorName)
    }
}

command("rank", "查看MVP排行与信用") {
    usage = "[page]"
    body {
        val page = arg.firstOrNull()?.toIntOrNull() ?: 1
        reply(formatRankPage(page, player?.uuid()).with())
    }
}

command("info", "查看自己的权重/MVP/信用/在线信息") {
    aliases = listOf("my", "me")
    body {
        val p = player ?: returnReply("[red]仅玩家可用".with())
        val uuid = p.uuid()
        val mvp = playerHistory(uuid)
        val weight = calcWeight(mvp)
        val credit = playerCredit(uuid)
        val creditMul = creditFactor(uuid)
        val breach = playerBreach(uuid)
        val adminInfo = netServer.admins.getInfoOptional(uuid)
        val kicked = adminInfo?.timesKicked ?: 0
        val joined = adminInfo?.timesJoined ?: 0
        val joinAt = sessionJoinAt[uuid] ?: System.currentTimeMillis().also { sessionJoinAt[uuid] = it }
        val onlineDuration = Duration.ofMillis((System.currentTimeMillis() - joinAt).coerceAtLeast(0L))
        val activeSeconds = (roundStats[uuid]?.activeTicks ?: 0f) / 60f
        val afkText = if (isAfk(uuid)) "[scarlet]AFK[]" else "[green]活跃[]"

        reply(
            """
            [accent]个人信息[]
            [lightgray]名字: [green]{name}[] [gray]({sid})[]
            [lightgray]队伍: [yellow]{team}[]  状态: {afk}
            [lightgray]MVP: [accent]{mvp}[]  当前权重因子: [accent]{weight}[]
            [lightgray]信用: [accent]{credit}[]  信用乘子: [accent]{creditMul}[]  违约次数: [yellow]{breach}[]
            [lightgray]本次在线: [accent]{online}[]  本局活跃: [accent]{active}s[]
            [lightgray]累计入服: [accent]{joined}[]  被踢出次数: [accent]{kicked}[]
            """.trimIndent().with(
                "name" to normalizedName(p.name),
                "sid" to shortIdOf(uuid),
                "team" to p.team().localized(),
                "afk" to afkText,
                "mvp" to formatMvp(mvp),
                "weight" to formatDouble(weight, 3),
                "credit" to formatMvp(credit),
                "creditMul" to formatMvp(creditMul),
                "breach" to breach,
                "online" to formatDurationHuman(onlineDuration),
                "active" to formatDouble(activeSeconds.toDouble(), 1),
                "joined" to joined,
                "kicked" to kicked,
            )
        )
    }
}

command("rt", "重新计算并分配队伍") {
    aliases = listOf("reteam", "rebalanceTeam")
    body {
        val sender = player
        if (sender != null && !sender.hasPermission(permissionReTeam)) {
            returnReply("[red]你没有权限使用该命令".with())
        }
        if (!balancerEnabled) returnReply("[yellow]当前MVP智能分队未启用".with())
        if (!state.rules.pvp) returnReply("[yellow]仅PVP模式可用".with())
        runRoundRebalance()
        reply("[green]已重新计算并分配队伍".with())
    }
}

command("addMvp", "管理指令：调整玩家MVP（后台可用）") {
    aliases = listOf("am")
    usage = "<ShortID> <Num(支持正负)>"
    body {
        val sender = player
        if (sender != null && !sender.hasPermission(permissionAddMvp)) {
            returnReply("[red]你没有权限使用该命令".with())
        }
        val shortId = arg.getOrNull(0)?.trim().orEmpty()
        if (shortId.isEmpty()) returnReply("[red]请输入玩家ShortID".with())

        val delta = arg.getOrNull(1)?.toDoubleOrNull()
            ?: returnReply("[red]Num 必须是数字".with())
        if (kotlin.math.abs(delta) <= EPS) {
            returnReply("[red]Num 不能为0".with())
        }

        val target = resolveByShortId(shortId)
            ?: returnReply("[red]找不到对应玩家，请确认ShortID".with())
        val before = playerHistory(target.uuid)
        val after = before + delta

        if (delta > 0.0) {
            if (delta > addMvpMaxDelta + EPS) {
                returnReply("[red]正数Num 不能超过 {limit}".with("limit" to formatMvp(addMvpMaxDelta)))
            }
            if (before > addMvpRejectAbove + EPS) {
                returnReply("[red]目标当前MVP({before})已超过 {limit}，拒绝增加".with(
                    "before" to formatMvp(before),
                    "limit" to formatMvp(addMvpRejectAbove)
                ))
            }
            if (after > addMvpRejectAbove + EPS) {
                returnReply("[red]增加后将达到 {after}，超过 {limit}，拒绝增加".with(
                    "after" to formatMvp(after),
                    "limit" to formatMvp(addMvpRejectAbove)
                ))
            }
        } else {
            if (after < -EPS) {
                returnReply("[red]扣减后MVP将变为负数({after})，拒绝操作".with(
                    "after" to formatMvp(after)
                ))
            }
        }

        setPlayerHistory(target.uuid, after)
        mvpNameCache[target.uuid] = target.displayName
        saveHistoryToFile()
        val action = if (delta > 0.0) "增加" else "扣减"
        reply("[green]已为 {name}[green]({sid}) {action}MVP({delta}): {before} -> {after}".with(
            "name" to target.realName,
            "sid" to target.shortId,
            "action" to action,
            "delta" to formatMvp(delta),
            "before" to formatMvp(before),
            "after" to formatMvp(after)
        ))
    }
}

command("recover", "信用<1时向管理申请恢复信用") {
    aliases = listOf("rc", "恢复")
    body {
        val p = player ?: returnReply("[red]仅玩家可用".with())
        val uuid = p.uuid()
        val credit = playerCredit(uuid)
        if (credit >= 1.0 - EPS) {
            returnReply("[yellow]你的信用为 {credit}，无需申请恢复".with("credit" to formatMvp(credit)))
        }
        if (recoverRequests.containsKey(uuid)) {
            val req = recoverRequests.getValue(uuid)
            returnReply("[yellow]你已提交过恢复申请，请等待管理处理 ({time})".with(
                "time" to formatTime(req.requestAt)
            ))
        }

        val real = realName(uuid)
        recoverRequests[uuid] = RecoverRequest(
            uuid = uuid,
            requestAt = System.currentTimeMillis(),
            requestName = real,
            creditSnapshot = credit,
            breachSnapshot = playerBreach(uuid)
        )
        saveRecoverRequests()

        reply("[green]已提交恢复申请，等待管理审核。当前信用: {credit}".with("credit" to formatMvp(credit)))
    }
}

command("doRecover", "管理/后台: 查看并处理信用恢复申请") {
    aliases = listOf("dr", "恢复列表")
    usage = "[approve/reject <ShortID/UUID> [Num]]"
    body {
        val sender = player
        if (sender != null && !sender.hasPermission(permissionDoRecover)) {
            returnReply("[red]你没有权限使用该命令".with())
        }

        val operatorName = sender?.let { normalizedName(it.name) } ?: "Console"
        val actionResult = handleRecoverActionArgs(arg, operatorName)
        if (actionResult != null) {
            reply(actionResult.with())
            return@body
        }

        if (sender == null) {
            reply(recoverListText().with())
            return@body
        }

        openRecoverMenu(sender, arg.firstOrNull()?.toIntOrNull() ?: 1)
    }
}
onEnable {
    loadHistoryFromFile()
    loadCreditFromFile()
    loadRecoverRequests()
    resetRoundState()
    Groups.player.forEach { sessionJoinAt.putIfAbsent(it.uuid(), System.currentTimeMillis()) }
    VoteEvent.registerCanVoteFilter(this) { player, _ ->
        !isAfk(player.uuid())
    }
    registerDoubtVoteCommand()
}

onDisable {
    VoteEvent.unregisterCanVoteFilter(this)
    clearAfkVisuals()
    afkStates.clear()
    sessionJoinAt.clear()
    unregisterDoubtVoteCommand()
    saveHistoryToFile()
    saveCreditToFile()
    saveRecoverRequests()
}

listen<EventType.WorldLoadEvent> {
    resetRoundState()
    if (!balancerEnabled) return@listen
    launch {
        delay(roundRebalanceDelaySeconds.coerceAtLeast(0) * 1000L)
        runRoundRebalance()
    }
}

listen<EventType.ResetEvent> {
    resetRoundState()
}

listen<EventType.PlayerJoin> {
    val player = it.player
    ensureRoundStat(player)
    sessionJoinAt[player.uuid()] = System.currentTimeMillis()
    val state = afkStateOf(player)
    state.idleTicks = 0f
    state.afk = false
    state.restoreName = ""
    player.unit()?.let { unit ->
        state.lastX = unit.x
        state.lastY = unit.y
    }
}

listen<EventType.PlayerLeave> {
    ensureRoundStat(it.player)
    sessionJoinAt.remove(it.player.uuid())
    afkStates.remove(it.player.uuid())
    lastControlActionAt.remove(it.player.uuid())
}

registerActionFilter { action ->
    val actor = action.player ?: return@registerActionFilter true
    if (isControlAction(action.type)) {
        lastControlActionAt[actor.uuid()] = System.currentTimeMillis()
    }
    true
}

listen<EventType.BlockBuildEndEvent> {
    if (!balancerEnabled || it.breaking) return@listen
    val player = it.unit?.player ?: return@listen
    if (player.team() == teams.spectateTeam) return@listen

    val block = it.tile.block()
    val rule = classifyBuildRule(block) ?: return@listen
    val stat = ensureRoundStat(player)
    applyBuildRule(stat, rule)
}

listen(EventType.Trigger.update) {
    if (!balancerEnabled || state.gameOver) return@listen
    val now = System.currentTimeMillis()
    val threshold2 = activeVelocityThreshold * activeVelocityThreshold
    val afkIdleTicks = afkIdleSeconds.coerceAtLeast(1f) * 60f
    val afkMoveDistance2 = afkMoveDistanceThreshold.coerceAtLeast(0f).let { it * it }
    Groups.player.forEach { player ->
        val afkState = afkStateOf(player)
        if (player.team() == teams.spectateTeam) {
            restoreAfkName(player, afkState)
            afkState.idleTicks = 0f
            player.unit()?.let { unit ->
                afkState.lastX = unit.x
                afkState.lastY = unit.y
            }
            return@forEach
        }
        val unit = player.unit() ?: return@forEach
        val stat = ensureRoundStat(player)
        val velocity2 = unit.vel.len2()
        val hasLastPos = !(afkState.lastX.isNaN() || afkState.lastY.isNaN())
        val movedDistance2 = if (hasLastPos) {
            val dx = unit.x - afkState.lastX
            val dy = unit.y - afkState.lastY
            dx * dx + dy * dy
        } else {
            Float.MAX_VALUE
        }
        afkState.lastX = unit.x
        afkState.lastY = unit.y

        val workingNow = unit.activelyBuilding() || unit.mining()
        val controlActiveNow = isControlRecentlyActive(player.uuid(), now)
        val movedNow = velocity2 > threshold2 || movedDistance2 > afkMoveDistance2 || workingNow || controlActiveNow
        val wasAfk = afkState.afk
        if (movedNow) {
            afkState.idleTicks = 0f
            restoreAfkName(player, afkState)
            if (wasAfk) {
                reassignAfterAfkRecover(player)
            }
        } else {
            afkState.idleTicks += Time.delta
            if (afkState.idleTicks >= afkIdleTicks) {
                markAfk(player, afkState)
            }
        }
        ensureAfkName(player, afkState)

        if (velocity2 > threshold2 || workingNow || controlActiveNow) {
            stat.activeTicks += Time.delta
        }
    }
}

listen<EventType.GameOverEvent> {
    if (!balancerEnabled || gameOverHandled) return@listen
    gameOverHandled = true
    val winner = settleRoundMvp()
    broadcastMvpResult(winner)
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.After) {
    if (!balancerEnabled || !state.rules.pvp) return@listenTo
    if (team == teams.spectateTeam) return@listenTo
    if (isAfk(player.uuid())) return@listenTo

    val allTeams = availablePvpTeams()
    if (allTeams.isEmpty()) return@listenTo

    oldTeam?.let { old ->
        if (old != teams.spectateTeam && old in allTeams) {
            team = old
            return@listenTo
        }
    }

    val playerCount = group.count {
        it.team() != teams.spectateTeam &&
            it.uuid() != player.uuid() &&
            !isAfk(it.uuid())
    } + 1
    val teamList = teamPoolFor(playerCount, allTeams)
    if (teamList.isEmpty()) return@listenTo
    val enforceMinTwo = shouldEnforceMinTwo(playerCount, allTeams.size)

    val balance = buildTeamBalance(teamList, group, excludeUuid = player.uuid())
    val target = pickTeamForAssign(balance, enforceMinTwo) ?: return@listenTo
    team = target
}

PermissionApi.registerDefault(permissionAddMvp, group = "@admin")
PermissionApi.registerDefault(permissionDoRecover, group = "@admin")
PermissionApi.registerDefault(permissionReTeam, group = "@admin")
PermissionApi.registerDefault("wayzer.vote.doubt")
