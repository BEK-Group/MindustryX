@file:Depends("DeterMination")
@file:Depends("wayzer/map/betterTeam")
@file:Depends("coreMindustry/menu")

package DeterMination

import arc.util.Time
import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Event
import coreLibrary.lib.PermissionApi
import coreMindustry.MenuV2
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.map.BetterTeam
import kotlin.random.Random

name = "NewJoinGuideGate"

private data class GateSession(
    val password: String,
    val pages: List<String>,
    var failCount: Int = 0,
    var lastPromptAt: Long = 0L,
)

private val gateEnabled by config.key(true, "是否启用新玩家首入验证")
private val bypassPermission by config.key("determination.newjoin.gate.bypass", "绕过新手验证权限")
private val passedFileName by config.key("newJoinGuideGate-passed.tsv", "已通过验证玩家列表文件名(存放于scripts/data)")
private val enforceObserverIntervalTicks by config.key(30f, "强制观战校验间隔(tick)")

private val teams = contextScript<BetterTeam>()
private val passedUuids = mutableSetOf<String>()
private val sessions = mutableMapOf<String, GateSession>()
private val publicGuidePages by lazy { buildGuidePages(password = null) }
private var enforceTicks = 0f

private fun passedFile() = dataDirectory.child(passedFileName)

private fun loadPassed() {
    passedUuids.clear()
    val file = passedFile()
    if (!file.exists()) return
    val text = runCatching { file.readString() }.getOrElse {
        logger.warning("NewJoinGuideGate: read passed file failed: ${it.message}")
        return
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val uuid = line.substringBefore('\t').trim()
        if (uuid.isNotEmpty()) passedUuids += uuid
    }
}

private fun savePassed() {
    runCatching {
        val file = passedFile()
        file.parent().mkdirs()
        val content = buildString {
            appendLine("# uuid")
            passedUuids.sorted().forEach { appendLine(it) }
        }
        file.writeString(content, false)
    }.onFailure {
        logger.warning("NewJoinGuideGate: save passed file failed: ${it.message}")
    }
}

private fun hasBypass(player: Player): Boolean {
    val groups = buildList {
        add(player.uuid())
        if (player.admin) add("@admin")
    }
    return PermissionApi.check(groups, bypassPermission)
}

private fun shouldLock(player: Player): Boolean =
    gateEnabled && player.uuid() !in passedUuids && !hasBypass(player)

private fun lockToObserver(player: Player) {
    if (player.team() == teams.spectateTeam) return
    teams.changeTeam(player, teams.spectateTeam)
}

private fun unlockFromObserver(player: Player) {
    if (player.team() != teams.spectateTeam) return
    teams.changeTeam(player)
}

private fun buildGuidePages(password: String?): List<String> {
    val pages = mutableListOf(
        mutableListOf(
            "[accent]守则说明(1/4)[]",
            "",
            "[yellow]请勿更换地图：无火hex、飙车[]",
            "纯e矩阵，没有压力的生存图，别怪我没有提醒你",
            "",
            "mvp为本服服务器“分数”",
            "mvp可以拥有特权（比如mvp大于10时，一票等于2票）",
            "每局都会有一个本局分数最大的被选为mvp",
            "",
            "本局分数=建造方块数*活动分钟数/总分钟数*因子1*信用",
            "因子1:随着mvp的增长缓慢降低，初始为1.5",
            "信用:信用小时即作为权重，信用初始为2",
            "",
            "如果怀疑某人在刷分，输入 /vote doubt <uid>，将其信用减少",
            "输入/rank查看服务器榜单，输入/vote kg <uid>可以踢走一个人的同时回滚他的操作。",
            "输入/broad可以开关这显示"
        ),
        mutableListOf(
            "[accent]建造分细则(2/4)[]",
            "对建筑分的计算机制做更改，只计算工厂和兵厂建造数。",
            "",
            "T1,T2工厂，小石墨，小硅，粉碎机等只需铜铅的，每建造一个+1分。最多+100",
            "T3工厂 每建造一个+20分，最多+40",
            "钛科技，钍科技的，每建造一个+2分，最多+200分",
            "需要塑钢的，需要布的，需要合金的，每建造一个+2分，最多300分",
            "T4工厂，T5工厂，每建造一个+40分，最多80分",
            "",
            "[gray]继续翻页查看 Erekir 科技树。[]"
        ),
        mutableListOf(
            "[accent]建造分细则(3/4) Erekir[]",
            "只需铍，石墨的：每个2分，最多100分",
            "只需铍，石墨，钨：每个3分，最多100分",
            "需要钍的：每个4分，最多200分",
            "需要氧化物/碳化物/合金的，每个6分，最多200分",
            "T1~T2工厂每个10分，最多100分",
            "T3工厂每个30分，最多30分",
            "T4~T5工厂每个20分，最多100分",
            "",
            "防熊:输入/voterc rcp <uid> <time>投票回滚某人前time分钟干的事情（单位不行）"
        ),
        mutableListOf(
            "[accent]操作说明(4/4)[]",
            "你可以随时输入 /guide 查看这份守则。",
            "新玩家首次进入需要在前几页中找出隐藏密码。",
            "输入 [yellow]/unlockob <4位密码>[] 进行验证。",
            "验证通过后立即解除观战并正常分队。",
            "",
            "[gray]若菜单关闭，可输入 /guide 或 /obguide 再次查看。[]"
        )
    )

    if (password != null) {
        val token = "*pa\$\$w0rd:$password*"
        val rng = Random(password.hashCode())
        val pageIndex = rng.nextInt(pages.size)
        val lineIndex = rng.nextInt(pages[pageIndex].size + 1)
        pages[pageIndex].add(lineIndex, token)
    }
    return pages.map { it.joinToString("\n") }
}

private fun ensureSession(uuid: String): GateSession {
    return sessions.getOrPut(uuid) {
        val password = Random.nextInt(1000, 10000).toString()
        GateSession(password = password, pages = buildGuidePages(password))
    }
}

private suspend fun openGuideMenu(player: Player, pages: List<String>, keyPrefix: String = "newjoin-guide-") {
    val total = pages.size.coerceAtLeast(1)
    MenuV2(player, followup = true) {
        var page by stateKey(1, keyPrefix = keyPrefix)
        val safePage = page.coerceIn(1, total)
        title = "新玩家验证说明"
        msg = pages[safePage - 1]

        column(3) {
            option("<-") {
                page = (safePage - 1).coerceAtLeast(1)
                refresh()
            }
            option("$safePage/$total") { refresh() }
            option("->") {
                page = (safePage + 1).coerceAtMost(total)
                refresh()
            }
        }
        option("我已找到密码，输入 /unlockob <密码>") { close() }
        option("关闭") { close() }
    }.send().awaitWithTimeout()
}

private fun promptLocked(player: Player) {
    val now = System.currentTimeMillis()
    val session = ensureSession(player.uuid())
    if (now - session.lastPromptAt < 5000L) return
    session.lastPromptAt = now
    player.sendMessage("[yellow]你当前处于新玩家验证观战状态。请阅读说明并找出隐藏密码后输入 /unlockob <4位密码>。".with())
    launch {
        runCatching { openGuideMenu(player, session.pages, keyPrefix = "newjoin-guide-locked-") }
    }
}

onEnable {
    loadPassed()
}

onDisable {
    savePassed()
    sessions.clear()
}

listen<EventType.PlayerJoin> {
    val player = it.player
    if (!shouldLock(player)) return@listen
    lockToObserver(player)
    promptLocked(player)
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    if (!shouldLock(player)) return@listenTo
    team = teams.spectateTeam
}

listen(EventType.Trigger.update) {
    if (!gateEnabled) return@listen
    enforceTicks += Time.delta
    if (enforceTicks < enforceObserverIntervalTicks.coerceAtLeast(1f)) return@listen
    enforceTicks = 0f
    Groups.player.forEach { player ->
        if (!shouldLock(player)) return@forEach
        lockToObserver(player)
    }
}

command("obguide", "查看新玩家验证说明") {
    aliases = listOf("guide", "guideob", "新手验证")
    body {
        val player = player ?: returnReply("[red]仅玩家可用".with())
        if (shouldLock(player)) {
            promptLocked(player)
            return@body
        }
        launch {
            runCatching { openGuideMenu(player, publicGuidePages, keyPrefix = "newjoin-guide-public-") }
        }
    }
}

command("unlockob", "输入新玩家验证密码并解除观战") {
    aliases = listOf("uob", "解除ob", "解锁观战", "验证密码")
    usage = "<4位密码>"
    body {
        val player = player ?: returnReply("[red]仅玩家可用".with())
        if (!shouldLock(player)) {
            return@body reply("[yellow]你当前无需验证。".with())
        }

        val input = arg.firstOrNull()?.trim()
            ?: returnReply("[red]用法: /unlockob <4位密码>".with())
        if (!input.matches(Regex("\\d{4}"))) {
            return@body reply("[red]请输入4位数字密码".with())
        }

        val session = ensureSession(player.uuid())
        if (input != session.password) {
            session.failCount += 1
            val tip = if (session.failCount >= 3) "（可输入 /guide 重新查看）" else ""
            return@body reply("[red]密码错误$tip".with())
        }

        passedUuids += player.uuid()
        sessions.remove(player.uuid())
        savePassed()
        unlockFromObserver(player)
        reply("[green]验证通过，已解除观战。祝你游戏愉快！".with())
    }
}

PermissionApi.registerDefault(bypassPermission, group = "@admin")
