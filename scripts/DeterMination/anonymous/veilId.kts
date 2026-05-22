@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/nameExt", "名字扩展")
@file:Depends("wayzer/user/shortID", "短ID")
@file:Depends("wayzer/user/suffix", "后缀")
@file:Depends("coreMindustry/menu", "菜单")

package DeterMination.anonymous

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.contextScript
import coreMindustry.MenuV2
import coreMindustry.renderPaged
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.CommandInfo
import arc.util.serialization.Base64Coder
import arc.util.Time
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.user.NameExt
import wayzer.user.ShortID
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.delay
import arc.util.Strings

val veilEnabled by config.key(true, "是否启用每局匿名")
val namesFile by config.key("", "随机名字库(绝对路径,UTF-8,每行一个；留空时使用脚本内置default.txt)")
val voteUnanonymous by config.key(true, "是否允许投票解除匿名(/vote unanonymous)")
val announceOnRoundStart by config.key(false, "每局开始是否广播匿名提示")
val historyRoundLimit by config.key(30, "历史对局记录保留局数")

private val shortIdLen = 3
private val shortIdChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private var roundActive = false
private var revealed = false
private var roundGeneration = 0

private val anonNameByUuid = mutableMapOf<String, String>()
private val anonShortIdByUuid = mutableMapOf<String, String>()
private val baseNameUseCount = mutableMapOf<String, Int>()
private val realNameByUuid = mutableMapOf<String, String>()
private val lastTeamByUuid = mutableMapOf<String, String>()
private val roundParticipantUuids = mutableSetOf<String>()
private var shuffledNames: List<String> = emptyList()
private var nameIndex = 0
private var teamColorRefreshTicks = 0f
@Savable(false)
private var historySerial = 0
@Savable(false)
private var historySavedGeneration = -1

private data class RoundPlayerInfo(
    val uuid: String,
    val team: String,
    val anonName: String?,
    val realName: String,
    val anonShortId: String?,
    val realShortId: String,
)

private data class RoundHistory(
    val id: Int,
    val map: String,
    val reason: String,
    val endAt: Long,
    val players: List<RoundPlayerInfo>,
)

@Savable(false)
private val historyRounds = ArrayDeque<RoundHistory>()

private val fallbackNames = listOf(
    "旅人", "陌生人", "匿名者", "观察者", "路过", "新兵", "工匠", "矿工", "工程师", "指挥官",
    "Wanderer", "Stranger", "Anon", "Observer", "Visitor"
)

private fun isPvpMode() = state.rules.pvp
private fun shouldAnon() = veilEnabled && isPvpMode() && roundActive && !revealed
private val bundledNamesFile by lazy { Config.rootDir.resolve("DeterMination/anonymous/default.txt") }

private val nameExt by lazy { runCatching { contextScript<NameExt>() }.getOrNull() }
private val shortIdExt by lazy { runCatching { contextScript<ShortID>() }.getOrNull() }
private val md5Digest = MessageDigest.getInstance("md5")

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

private fun registerSuffixMaskCompat() {
    runCatching {
        val policyClass = Class.forName("wayzer.user.SuffixMaskPolicy")
        val companion = policyClass.getDeclaredField("Companion").get(null)
        val provideMethod = companion.javaClass.methods.firstOrNull { it.name == "provide" && it.parameterCount == 2 }
            ?: return
        val policyImpl = Proxy.newProxyInstance(
            policyClass.classLoader,
            arrayOf(policyClass),
        ) { proxy, method, args ->
            when (method.name) {
                "hideSuffix" -> shouldAnon()
                "toString" -> "VeilIDSuffixMaskProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
        provideMethod.invoke(companion, this, policyImpl)
    }.onFailure {
        logger.info("VeilID: 未接入后缀隐藏策略(${it.javaClass.simpleName}: ${it.message})")
    }
}

private fun setNameOverrideCompat(uuid: String, name: String) {
    invokeCompat(nameExt, "setNameOverride", uuid, name)
}

private fun clearNameOverrideCompat(uuid: String) {
    invokeCompat(nameExt, "clearNameOverride", uuid)
}

private fun clearAllNameOverrideCompat() {
    invokeCompat(nameExt, "clearAllNameOverride")
}

private fun setShortIdOverrideCompat(uuid: String, shortId: String): Boolean {
    return (invokeCompat(shortIdExt, "setShortIdOverride", uuid, shortId) as? Boolean) ?: false
}

private fun clearShortIdOverrideCompat(uuid: String) {
    invokeCompat(shortIdExt, "clearShortIdOverride", uuid)
}

private fun clearAllShortIdOverrideCompat() {
    invokeCompat(shortIdExt, "clearAllShortIdOverride")
}

private fun updateNameCompat(player: Player) {
    val invoked = invokeCompat(nameExt, "updateName", player) != null
    if (!invoked) {
        player.name = anonNameByUuid[player.uuid()] ?: realNameByUuid[player.uuid()] ?: player.name
    }
}

private fun readNames(file: File): List<String> = runCatching {
    file.readLines(Charsets.UTF_8)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()
}.getOrElse {
    emptyList()
}

private fun loadNames(): List<String> {
    val configured = namesFile.trim()
    val configuredFile = configured.takeIf { it.isNotEmpty() }?.let(::File)

    val source = sequenceOf(configuredFile, bundledNamesFile)
        .filterNotNull()
        .distinctBy { it.absolutePath }
        .firstOrNull { readNames(it).isNotEmpty() }

    val list = if (source != null) {
        readNames(source)
    } else {
        if (configured.isNotEmpty()) {
            logger.warning("VeilID: 名字库不可用($configured)，且内置名字库不存在或为空，改用内置fallback")
        } else {
            logger.warning("VeilID: 内置名字库不存在或为空，改用内置fallback")
        }
        fallbackNames
    }
    return list.shuffled(Random(System.nanoTime()))
}

private fun nextAnonName(): String {
    val base = shuffledNames.getOrNull(nameIndex++) ?: fallbackNames.random()
    val used = (baseNameUseCount[base] ?: 0) + 1
    baseNameUseCount[base] = used
    return if (used == 1) base else "$base#$used"
}

private fun randomShortId(len: Int = shortIdLen): String = buildString(len) {
    repeat(len) { append(shortIdChars[Random.nextInt(shortIdChars.length)]) }
}

private fun shortIdFromUuid(uuid: String): String {
    fun md5Md5(bs: ByteArray) = synchronized(md5Digest) {
        md5Digest.update(md5Digest.digest(bs))
        md5Digest.digest(bs)
    }

    val bytes = md5Md5(uuid.toByteArray())
    return Base64Coder.encode(bytes).sliceArray(0..2).map {
        when (it) {
            'k' -> 'K'
            'S' -> 's'
            'l' -> 'L'
            '+' -> 'A'
            '/' -> 'B'
            else -> it
        }
    }.joinToString("")
}

private fun colorizeWithTeam(player: Player, text: String): String {
    val color = player.team().color.toString()
    return "[#$color]$text[]"
}

private fun ensureAnonShortId(uuid: String): String {
    anonShortIdByUuid[uuid]?.let { return it }
    repeat(10_000) {
        val id = randomShortId()
        if (setShortIdOverrideCompat(uuid, id)) {
            anonShortIdByUuid[uuid] = id
            return id
        }
    }
    val fallback = randomShortId(4)
    if (setShortIdOverrideCompat(uuid, fallback)) {
        anonShortIdByUuid[uuid] = fallback
        return fallback
    }
    error("VeilID: 无法为 $uuid 分配唯一 shortID")
}

private fun updateTeamRecord(player: Player) {
    val uuid = player.uuid()
    roundParticipantUuids += uuid
    lastTeamByUuid[uuid] = player.team().name
}

private fun refreshAnonOnTeamChanged(player: Player) {
    val uuid = player.uuid()
    val currentTeam = player.team().name
    val previousTeam = lastTeamByUuid[uuid]
    if (previousTeam == currentTeam) return
    if (shouldAnon()) {
        applyAnon(player)
    } else {
        updateTeamRecord(player)
    }
}

private fun saveRoundHistory(reason: String) {
    if (historySavedGeneration == roundGeneration) return
    val uuids = (roundParticipantUuids + realNameByUuid.keys + anonNameByUuid.keys + anonShortIdByUuid.keys + lastTeamByUuid.keys)
        .distinct()
    if (uuids.isEmpty()) {
        historySavedGeneration = roundGeneration
        return
    }

    val players = uuids.map { uuid ->
        val online = Groups.player.find { it.uuid() == uuid }
        val team = lastTeamByUuid[uuid] ?: online?.team()?.name ?: "unknown"
        val realName = realNameByUuid[uuid] ?: online?.let { Strings.stripColors(it.name) } ?: "unknown"
        RoundPlayerInfo(
            uuid = uuid,
            team = team,
            anonName = anonNameByUuid[uuid],
            realName = realName,
            anonShortId = anonShortIdByUuid[uuid],
            realShortId = shortIdFromUuid(uuid),
        )
    }.sortedWith(compareBy<RoundPlayerInfo> { it.team }.thenBy { it.realName })

    historySerial++
    historyRounds.addFirst(
        RoundHistory(
            id = historySerial,
            map = state.map.name(),
            reason = reason,
            endAt = System.currentTimeMillis(),
            players = players
        )
    )
    while (historyRounds.size > historyRoundLimit.coerceAtLeast(1)) {
        historyRounds.removeLast()
    }
    historySavedGeneration = roundGeneration
}

private fun renderRoundHistory(round: RoundHistory): String {
    val lines = round.players.mapIndexed { idx, info ->
        val anonName = info.anonName ?: "-"
        val anonShort = info.anonShortId ?: "-"
        "[lightgray]{idx}. [accent]{team}[] [yellow]{anonName}[]({anonShort}) [white]->[] [green]{realName}[]".with(
            "idx" to (idx + 1),
            "team" to info.team,
            "anonName" to anonName,
            "anonShort" to anonShort,
            "realName" to info.realName,
        ).toString()
    }
    val body = if (lines.isEmpty()) listOf("[gray]本局无玩家记录[]") else lines
    return buildString {
        appendLine("[gold]第${round.id}局[]  [lightgray]地图: ${round.map}")
        appendLine("[lightgray]结束原因: ${round.reason}  玩家数: ${round.players.size}")
        appendLine()
        append(body.joinToString("\n"))
    }
}

private fun readHistoryRoundsSafe(): List<RoundHistory>? {
    return runCatching { historyRounds.toList() }
        .onFailure {
            logger.warning("VeilID: read history rounds failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        .getOrNull()
}

private fun renderRoundSummary(round: RoundHistory): String {
    return "[yellow]#${round.id}[] [white]${round.map}[] [gray]${round.players.size}人[] [gray]${round.reason}"
}

private fun renderRoundListPage(rounds: List<RoundHistory>, page: Int, pageSize: Int): String {
    val safePageSize = pageSize.coerceAtLeast(1)
    val total = rounds.size
    val maxPage = ((total + safePageSize - 1) / safePageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(1, maxPage)
    val from = (currentPage - 1) * safePageSize
    val to = minOf(from + safePageSize, total)
    val items = if (from >= to) {
        listOf("[gray]暂无可显示记录[]")
    } else {
        rounds.subList(from, to).map(::renderRoundSummary)
    }
    return "[green]匿名历史对局[] [lightgray]第{page}/{max}页 共{size}局[]\n{items|joinLines}".with(
        "page" to currentPage,
        "max" to maxPage,
        "size" to total,
        "items" to items,
    ).toString()
}

private fun applyAnon(player: Player) {
    if (!shouldAnon()) return
    val uuid = player.uuid()
    realNameByUuid.putIfAbsent(uuid, player.name)
    updateTeamRecord(player)
    val name = anonNameByUuid.getOrPut(uuid) { nextAnonName() }
    setNameOverrideCompat(uuid, colorizeWithTeam(player, name))
    ensureAnonShortId(uuid)
    updateNameCompat(player)
}

private fun beginRound() {
    roundGeneration++
    roundActive = true
    revealed = false
    historySavedGeneration = -1

    // 清理上局残留(防止热重载/异常状态)
    clearAllNameOverrideCompat()
    clearAllShortIdOverrideCompat()
    anonNameByUuid.clear()
    anonShortIdByUuid.clear()
    baseNameUseCount.clear()
    realNameByUuid.clear()
    lastTeamByUuid.clear()
    roundParticipantUuids.clear()
    Groups.player.forEach { realNameByUuid[it.uuid()] = it.name }
    Groups.player.forEach { updateTeamRecord(it) }

    shuffledNames = loadNames()
    nameIndex = 0
}

private fun scheduleAnonApplyRetry() {
    val gen = roundGeneration
    launch(Dispatchers.gamePost) {
        // 换图流程里在线玩家会在稍后重新add，做短时重试确保能套上匿名
        repeat(20) {
            if (!veilEnabled || !roundActive || revealed || gen != roundGeneration) return@launch
            Groups.player.forEach { applyAnon(it) }
            delay(150)
        }
    }
}

private fun revealAll(reason: String, operator: Player? = null) {
    if (revealed && reason != "worldLoad") return
    if (roundActive) saveRoundHistory(reason)
    revealed = true

    clearAllNameOverrideCompat()
    clearAllShortIdOverrideCompat()
    anonNameByUuid.clear()
    anonShortIdByUuid.clear()
    baseNameUseCount.clear()
    shuffledNames = emptyList()
    nameIndex = 0

    Groups.player.forEach {
        val base = realNameByUuid[it.uuid()]
        if (base != null) it.name = base
        updateNameCompat(it)
    }

    val msg = when (reason) {
        "vote" -> "[yellow]投票通过，已解除匿名(全员)".with()
        "admin" -> "[yellow]管理员已强制解除匿名(全员)".with()
        "gameover" -> "[yellow]本局结束，已解除匿名(等待换图期间为实名)".with()
        "disable" -> "[yellow]VeilID 已停用，已解除匿名".with()
        else -> null
    }
    if (msg != null) broadcast(msg, quite = true)
    operator?.sendMessage("[green]已解除匿名".with())
}

private fun anonymizeAll(reason: String, operator: Player? = null) {
    if (!veilEnabled || !roundActive) return
    revealed = false

    clearAllNameOverrideCompat()
    clearAllShortIdOverrideCompat()
    anonNameByUuid.clear()
    anonShortIdByUuid.clear()
    baseNameUseCount.clear()
    shuffledNames = loadNames()
    nameIndex = 0

    Groups.player.forEach {
        realNameByUuid.putIfAbsent(it.uuid(), it.name)
        updateTeamRecord(it)
        applyAnon(it)
    }

    val msg = when (reason) {
        "vote" -> "[yellow]投票通过，已重新启用匿名(全员)".with()
        "admin" -> "[yellow]管理员已强制重新启用匿名(全员)".with()
        else -> null
    }
    if (msg != null) broadcast(msg, quite = true)
    operator?.sendMessage("[green]已重新启用匿名".with())
}

listen<EventType.WorldLoadEvent> {
    if (!veilEnabled || !isPvpMode()) {
        roundActive = false
        revealed = true
        revealAll("worldLoad")
        return@listen
    }
    beginRound()

    Groups.player.forEach { applyAnon(it) }
    scheduleAnonApplyRetry()
    if (announceOnRoundStart) {
        broadcast("[yellow]本局已启用匿名(随机名字+随机短ID)".with(), quite = true)
    }
}

listen<EventType.PlayEvent> {
    // 双保险：某些换图路径下WorldLoad阶段玩家列表为空，Play后重试一次
    if (!veilEnabled || !isPvpMode() || !roundActive || revealed) return@listen
    scheduleAnonApplyRetry()
}

listen<EventType.PlayerJoin> {
    if (!isPvpMode()) return@listen
    updateTeamRecord(it.player)
    applyAnon(it.player)
}

listen<EventType.PlayerLeave> {
    if (!isPvpMode()) return@listen
    updateTeamRecord(it.player)
}

listen(EventType.Trigger.update) {
    if (!isPvpMode()) {
        if (roundActive && !revealed) revealAll("worldLoad")
        return@listen
    }
    teamColorRefreshTicks += Time.delta
    if (teamColorRefreshTicks < 15f) return@listen
    teamColorRefreshTicks = 0f
    Groups.player.forEach { refreshAnonOnTeamChanged(it) }
}

listen<EventType.GameOverEvent> {
    if (!veilEnabled || !isPvpMode()) return@listen
    // 游戏结束到自动换图等待阶段：解除匿名
    revealAll("gameover")
}

onEnable {
    registerSuffixMaskCompat()

    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "unanonymous", "解除匿名(全员)".with()) {
        aliases = listOf("deanon", "reveal", "解除匿名", "解除")
        requirePermission("determination.vote.unanonymous")
        body {
            if (!isPvpMode()) returnReply("[red]仅PVP模式可用".with())
            if (!voteUnanonymous) returnReply("[red]当前服务器未开启投票解除匿名".with())
            if (!veilEnabled) returnReply("[red]VeilID 未启用".with())
            if (!roundActive) returnReply("[red]当前不在对局中".with())
            if (revealed) returnReply("[yellow]当前已解除匿名".with())

            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            val event = VoteEvent(
                script, starter,
                voteDesc = "解除匿名(全员)".with(),
                extDesc = "[gray]通过后本局全员恢复真实名字与 shortID，下一张图开局将重新匿名".with().toString(),
                supportSingle = true,
            )
            if (event.awaitResult()) {
                revealAll("vote")
            }
        }
    }

    VoteEvent.VoteCommands += CommandInfo(script, "anonymous", "重新启用匿名(全员)".with()) {
        aliases = listOf("匿名")
        requirePermission("determination.vote.anonymous")
        body {
            if (!isPvpMode()) returnReply("[red]仅PVP模式可用".with())
            if (!veilEnabled) returnReply("[red]VeilID 未启用".with())
            if (!roundActive) returnReply("[red]当前不在对局中".with())
            if (!revealed) returnReply("[yellow]当前已处于匿名状态".with())

            val starter = player ?: returnReply("[red]仅玩家可发起投票".with())
            val minAgree = if (Groups.player.count { !it.dead() } <= 1) 1 else 2
            val event = VoteEvent(
                script, starter,
                voteDesc = "重新启用匿名(全员)".with(),
                extDesc = "[gray]至少需要2人赞成，若当前仅1名可投票玩家则直接通过".with().toString(),
                supportSingle = true,
                requireNum = { minAgree.toDouble() },
            )
            if (event.awaitResult()) {
                anonymizeAll("vote")
            }
        }
    }
}

command("unanonymous", "管理指令: 强制解除匿名(全员)") {
    requirePermission("determination.admin.unanonymous")
    body {
        if (!isPvpMode()) returnReply("[red]仅PVP模式可用".with())
        if (!veilEnabled) returnReply("[red]VeilID 未启用".with())
        if (revealed) returnReply("[yellow]当前已解除匿名".with())
        revealAll("admin", operator = player)
    }
}

command("anonymous", "管理指令: 强制重新启用匿名(全员)") {
    aliases = listOf("匿名")
    requirePermission("determination.admin.anonymous")
    body {
        if (!isPvpMode()) returnReply("[red]仅PVP模式可用".with())
        if (!veilEnabled) returnReply("[red]VeilID 未启用".with())
        if (!roundActive) returnReply("[red]当前不在对局中".with())
        if (!revealed) returnReply("[yellow]当前已处于匿名状态".with())
        anonymizeAll("admin", operator = player)
    }
}

command("realname", "后台指令: 查询真名/真shortid") {
    aliases = listOf("真名")
    requirePermission("determination.admin.unanonymous")
    body {
        if (player != null) returnReply("[red]仅后台可用".with())
        if (arg.isEmpty()) returnReply("[red]参数错误: /realname <名字/shortid>".with())

        val query = arg.joinToString(" ").trim()
        if (query.isEmpty()) returnReply("[red]参数错误: /realname <名字/shortid>".with())

        val byShort = PlayerData.findByShortId(query)?.player?.uuid()
        val matched = Groups.player.toList().filter { p ->
            val uuid = p.uuid()
            val anonName = anonNameByUuid[uuid]
            val realName = realNameByUuid[uuid] ?: p.name
            val display = Strings.stripColors(p.name)
            val anonShort = anonShortIdByUuid[uuid]
            val realShort = shortIdFromUuid(uuid)

            uuid.equals(query, ignoreCase = true) ||
                display.equals(query, ignoreCase = true) ||
                realName.equals(query, ignoreCase = true) ||
                (anonName?.equals(query, ignoreCase = true) == true) ||
                realShort.equals(query, ignoreCase = true) ||
                (anonShort?.equals(query, ignoreCase = true) == true) ||
                (byShort != null && byShort == uuid)
        }

        if (matched.isEmpty()) returnReply("[yellow]未找到目标: {q}".with("q" to query))

        val lines = matched.map { p ->
            val uuid = p.uuid()
            val anonName = anonNameByUuid[uuid] ?: "-"
            val realName = realNameByUuid[uuid] ?: Strings.stripColors(p.name)
            val anonShort = anonShortIdByUuid[uuid] ?: "-"
            val realShort = shortIdFromUuid(uuid)
            val nowShort = PlayerData[p].shortId
            "[white]{name}[] [gray](uuid={uuid})[] -> 真名:[accent]{realName}[] 真shortid:[accent]{realShort}[] 当前shortid:[yellow]{nowShort}[] 匿名名:[gray]{anonName}[] 匿名shortid:[gray]{anonShort}[]".with(
                "name" to Strings.stripColors(p.name),
                "uuid" to uuid,
                "realName" to realName,
                "realShort" to realShort,
                "nowShort" to nowShort,
                "anonName" to anonName,
                "anonShort" to anonShort
            ).toString()
        }

        reply(
            "[green]查询结果({count})\n{list|joinLines}".with(
                "count" to lines.size,
                "list" to lines
            )
        )
    }
}

command("veilhistory", "管理指令: 查看匿名历史对局") {
    aliases = listOf("vh", "匿名历史", "匿历")
    requirePermission("determination.admin.veilhistory")
    body {
        val rounds = readHistoryRoundsSafe()
            ?: returnReply(
                "[red]读取匿名历史失败，请重载脚本后重试: [gold]sa load DeterMination/anonymous/veilId --noCache[]".with()
            )
        if (rounds.isEmpty()) returnReply("[yellow]暂无历史对局记录".with())
        val page = arg.firstOrNull()?.toIntOrNull() ?: 1
        val textView = renderRoundListPage(rounds, page, 8)
        val viewer = player ?: returnReply(
            textView.with()
        )

        val menuError = runCatching {
            MenuV2(viewer) {
                title = "匿名历史对局"
                renderPaged(rounds, initialPage = page, prePage = 1, key = "veilHistory") { round ->
                    msg = renderRoundHistory(round)
                    option("第${round.id}局 / ${round.players.size}人") { refresh() }
                }
                option("关闭") { close() }
            }.send().awaitWithTimeout()
        }.exceptionOrNull()
        if (menuError != null) {
            logger.warning("VeilID: veilhistory menu failed: ${menuError.javaClass.simpleName}: ${menuError.message}")
            returnReply(
                "[yellow]菜单打开失败，已切换文本模式\n{text}".with("text" to textView)
            )
        }
    }
}

PermissionApi.registerDefault("determination.admin.unanonymous", group = "@admin")
PermissionApi.registerDefault("determination.admin.anonymous", group = "@admin")
PermissionApi.registerDefault("determination.admin.veilhistory", group = "@admin")
PermissionApi.registerDefault("determination.vote.unanonymous", group = "@admin")
PermissionApi.registerDefault("determination.vote.anonymous")

onDisable {
    // 退出脚本时，确保不残留匿名状态
    if (veilEnabled) {
        revealed = false
        roundActive = true
        revealAll("disable")
    } else {
        clearAllNameOverrideCompat()
        clearAllShortIdOverrideCompat()
        Groups.player.forEach { updateNameCompat(it) }
    }
    // 清理可能遗留的单人覆盖(保险)
    Groups.player.forEach {
        clearNameOverrideCompat(it.uuid())
        clearShortIdOverrideCompat(it.uuid())
        updateNameCompat(it)
    }
}
