@file:Depends("DeterMination")
@file:Depends("wayzer")
@file:Depends("wayzer/vote")
@file:Depends("wayzer/map/betterTeam")
@file:Depends("wayzer/user/shortID")
@file:Depends("coreMindustry/menu")

package DeterMination

import arc.util.Strings
import coreLibrary.lib.PermissionApi
import coreLibrary.lib.asPlaceHoldString
import coreLibrary.lib.event.RequestPermissionEvent
import coreLibrary.lib.util.calPage
import coreLibrary.lib.util.menu
import coreMindustry.MenuV2
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import wayzer.map.BetterTeam

name = "AltAccountGuard"

private val permissionList = "determination.altguard.list"
private val defaultBypassPermission = "determination.altguard.bypass"

private val smallAccountJoinThreshold by config.key(50, "判定小号的进入次数阈值(严格小于)")
private val forceObserverForSmallAccount by config.key(false, "是否强制小号观战")
private val mapsLikePageSize by config.key(8, "界面每页显示大号条目数")
private val listOnlyWithAlts by config.key(true, "列表仅显示存在小号的大号条目")
private val bypassPermission by config.key(defaultBypassPermission, "小号限制豁免权限节点")
private val blockVotePermissionPrefix by config.key("wayzer.vote.", "投票权限拦截前缀")
private val ipTimeWindowDays by config.key(30, "若IP可解析时间，仅使用最近N天")
private val fallbackRecentIpCount by config.key(10, "无法解析IP时间时，仅使用最近N条IP")

private val teams = contextScript<BetterTeam>()

data class AccountView(
    val uuid: String,
    val name: String,
    val shortId: String,
    val timesJoined: Int
)

data class AltRelation(
    val main: AccountView,
    val alts: List<AccountView>
)

data class AltSnapshot(
    val relations: List<AltRelation>,
    val smallToMain: Map<String, String>
)

private class UnionFind(ids: Collection<String>) {
    private val parent = mutableMapOf<String, String>()

    init {
        ids.forEach { parent[it] = it }
    }

    fun find(id: String): String {
        val p = parent[id] ?: run {
            parent[id] = id
            id
        }
        if (p == id) return id
        val root = find(p)
        parent[id] = root
        return root
    }

    fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }
}

@Savable(false)
var snapshot = AltSnapshot(emptyList(), emptyMap())

@Savable(false)
val restrictedOnline = mutableSetOf<String>()

@Savable(false)
var voteCompanion: Any? = null

@Savable(false)
var voteActiveGetter: java.lang.reflect.Method? = null

private fun normalizeIp(ip: String?): String? {
    val value = ip?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.equals("<unknown>", ignoreCase = true)) return null
    return value
}

private data class TimedIp(val ip: String, val timeMs: Long)

private fun parseEpochMillis(raw: String): Long? {
    val value = raw.toLongOrNull() ?: return null
    val millis = when {
        raw.length == 10 -> value * 1000L
        raw.length == 13 -> value
        value in 1_000_000_000L..9_999_999_999L -> value * 1000L
        else -> value
    }
    val now = System.currentTimeMillis()
    if (millis < 946684800000L || millis > now + 86_400_000L) return null
    return millis
}

private fun parseTimedIp(raw: String): TimedIp? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    for (sep in charArrayOf('|', '@', '#')) {
        val idx = value.lastIndexOf(sep)
        if (idx <= 0 || idx >= value.lastIndex) continue
        val left = value.substring(0, idx).trim()
        val right = value.substring(idx + 1).trim()

        parseEpochMillis(right)?.let { ts ->
            val ip = normalizeIp(left) ?: return@let
            return TimedIp(ip, ts)
        }
        parseEpochMillis(left)?.let { ts ->
            val ip = normalizeIp(right) ?: return@let
            return TimedIp(ip, ts)
        }
    }
    return null
}

private fun ensureVoteAccessorReady() {
    if (voteCompanion != null && voteActiveGetter != null) return
    runCatching {
        val companion = VoteEvent::class.java.getDeclaredField("Companion").apply {
            isAccessible = true
        }.get(null)
        val getter = companion.javaClass.methods.firstOrNull {
            it.parameterCount == 0 &&
                    it.returnType.name == "java.util.concurrent.atomic.AtomicReference" &&
                    it.name.startsWith("getActive")
        }
        voteCompanion = companion
        voteActiveGetter = getter
    }
}

private fun currentVoteEvent(): VoteEvent? {
    ensureVoteAccessorReady()
    val companion = voteCompanion ?: return null
    val getter = voteActiveGetter ?: return null
    val ref = runCatching { getter.invoke(companion) }.getOrNull()
        as? java.util.concurrent.atomic.AtomicReference<*>
        ?: return null
    return ref.get() as? VoteEvent
}

private fun resolveName(info: Administration.PlayerInfo): String {
    val online = Groups.player.find { it.uuid() == info.id }?.name
    val plain = Strings.stripColors(online ?: info.lastName ?: "").trim()
    return plain.ifEmpty { "<unknown>" }
}

private fun resolveShortId(info: Administration.PlayerInfo): String {
    val online = Groups.player.find { it.uuid() == info.id }
    if (online != null) return PlayerData[online].shortId
    return PlayerData.IGetUidByShortId.getOrNull()
        ?.getShortId(PlayerData(resolveName(info), info.id))
        ?: info.id.take(3)
}

private fun collectIps(info: Administration.PlayerInfo): Set<String> {
    val recentFirst = mutableListOf<String>()
    normalizeIp(info.lastIP)?.let(recentFirst::add)
    for (i in info.ips.size - 1 downTo 0) {
        normalizeIp(info.ips[i])?.let(recentFirst::add)
    }
    val distinctRecent = recentFirst.distinct()
    if (distinctRecent.isEmpty()) return emptySet()

    val timedIps = distinctRecent.mapNotNull(::parseTimedIp)
    if (timedIps.isNotEmpty()) {
        val cutoff = System.currentTimeMillis() - ipTimeWindowDays.coerceAtLeast(1).toLong() * 86_400_000L
        return timedIps.asSequence()
            .filter { it.timeMs >= cutoff }
            .map { it.ip }
            .toSet()
    }

    return distinctRecent
        .take(fallbackRecentIpCount.coerceAtLeast(1))
        .toSet()
}

private fun buildSnapshot(): AltSnapshot {
    val infos = mutableListOf<Administration.PlayerInfo>()
    netServer.admins.playerInfo.values().forEach { infos += it }
    if (infos.isEmpty()) return AltSnapshot(emptyList(), emptyMap())

    val infoById = infos.associateBy { it.id }
    val accountById = infos.associate { info ->
        info.id to AccountView(
            uuid = info.id,
            name = resolveName(info),
            shortId = resolveShortId(info),
            timesJoined = info.timesJoined
        )
    }
    val ipToIds = mutableMapOf<String, MutableSet<String>>()
    infos.forEach { info ->
        collectIps(info).forEach { ip ->
            ipToIds.getOrPut(ip) { linkedSetOf() }.add(info.id)
        }
    }

    val uf = UnionFind(accountById.keys)
    ipToIds.values.forEach { ids ->
        val base = ids.firstOrNull() ?: return@forEach
        ids.drop(1).forEach { uf.union(base, it) }
    }

    val component = mutableMapOf<String, MutableList<String>>()
    accountById.keys.forEach { id ->
        component.getOrPut(uf.find(id)) { mutableListOf() }.add(id)
    }

    val relations = mutableListOf<AltRelation>()
    val smallToMain = mutableMapOf<String, String>()
    component.values.forEach { ids ->
        if (ids.size < 2) return@forEach
        val sorted = ids.sortedWith(
            compareByDescending<String> { accountById[it]?.timesJoined ?: 0 }
                .thenBy { it }
        )
        val mainId = sorted.firstOrNull() ?: return@forEach
        val main = accountById[mainId] ?: return@forEach
        val alts = sorted.drop(1).mapNotNull { id ->
            val node = accountById[id] ?: return@mapNotNull null
            if (node.timesJoined >= smallAccountJoinThreshold) return@mapNotNull null
            node
        }.sortedWith(
            compareByDescending<AccountView> { it.timesJoined }
                .thenBy { it.uuid }
        )
        if (alts.isEmpty() && listOnlyWithAlts) return@forEach

        relations += AltRelation(main, alts)
        alts.forEach { small ->
            val oldMain = smallToMain[small.uuid]
            if (oldMain == null) {
                smallToMain[small.uuid] = main.uuid
            } else {
                val oldInfo = infoById[oldMain]
                val newInfo = infoById[main.uuid]
                val oldScore = oldInfo?.timesJoined ?: 0
                val newScore = newInfo?.timesJoined ?: 0
                if (newScore > oldScore || (newScore == oldScore && main.uuid < oldMain)) {
                    smallToMain[small.uuid] = main.uuid
                }
            }
        }
    }

    val fixedRelations = relations.map { relation ->
        relation.copy(alts = relation.alts.filter { smallToMain[it.uuid] == relation.main.uuid })
    }.filter { it.alts.isNotEmpty() || !listOnlyWithAlts }
        .sortedWith(
            compareByDescending<AltRelation> { it.main.timesJoined }
                .thenByDescending { it.alts.size }
                .thenBy { it.main.uuid }
        )

    return AltSnapshot(fixedRelations, smallToMain)
}

private fun hasBypass(player: Player, groupHint: List<String>? = null): Boolean {
    val groups = groupHint?.takeIf { it.isNotEmpty() } ?: buildList {
        add(player.uuid())
        if (player.admin) add("@admin")
    }
    return PermissionApi.check(groups, bypassPermission)
}

private fun isBlocked(player: Player, groupHint: List<String>? = null): Boolean {
    return player.uuid() in restrictedOnline && !hasBypass(player, groupHint)
}

private fun updateRestrictedOnline(applyObserver: Boolean) {
    val next = mutableSetOf<String>()
    Groups.player.forEach { player ->
        if (player.uuid() !in snapshot.smallToMain) return@forEach
        next += player.uuid()
        if (hasBypass(player)) return@forEach
        if (applyObserver && forceObserverForSmallAccount && player.team() != teams.spectateTeam) {
            teams.changeTeam(player, teams.spectateTeam)
            player.sendMessage("[yellow]检测到同IP小号关系，已强制观战")
        }
    }
    restrictedOnline.clear()
    restrictedOnline.addAll(next)
}

private fun refreshState(applyObserver: Boolean) {
    snapshot = buildSnapshot()
    updateRestrictedOnline(applyObserver)
}

private fun relationText(relation: AltRelation): String = buildString {
    appendLine("${relation.main.name}+${relation.main.shortId}:")
    relation.alts.forEach { alt ->
        appendLine("  - ${alt.name}+${alt.shortId}")
    }
}.trimEnd()

private fun filterRelations(relations: List<AltRelation>, keyword: String?): List<AltRelation> {
    val k = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: return relations
    return relations.filter { relation ->
        fun AccountView.hit(): Boolean =
            name.contains(k, ignoreCase = true) ||
                    shortId.contains(k, ignoreCase = true) ||
                    uuid.contains(k, ignoreCase = true)
        relation.main.hit() || relation.alts.any { it.hit() }
    }
}

private fun pageSlice(relations: List<AltRelation>, page: Int, prePage: Int): Triple<Int, Int, List<AltRelation>> {
    if (relations.isEmpty()) return Triple(0, 0, emptyList())
    val (realPage, totalPage) = calPage(page, prePage, relations.size)
    val from = (realPage - 1) * prePage
    val to = (realPage * prePage).coerceAtMost(relations.size)
    return Triple(realPage, totalPage, relations.subList(from, to))
}

private fun renderPageMessage(relations: List<AltRelation>, page: Int, prePage: Int): String {
    val (realPage, totalPage, items) = pageSlice(relations, page, prePage)
    if (items.isEmpty()) return "[yellow]未找到符合条件的小号关系"
    return buildString {
        appendLine("[accent]同IP小号关系[]")
        appendLine("[lightgray]共${relations.size}组[]")
        appendLine()
        items.forEachIndexed { index, relation ->
            if (index > 0) appendLine()
            append(relationText(relation))
            appendLine()
        }
        appendLine()
        append("[lightgray]第${realPage}/${totalPage}页[]")
    }
}

private suspend fun openPlayerMenu(player: Player, relations: List<AltRelation>, initialPage: Int, keyword: String?) {
    val prePage = mapsLikePageSize.coerceAtLeast(1)
    MenuV2(player, followup = true) {
        var selectedPage by stateKey(initialPage.coerceAtLeast(1), keyPrefix = "alts-")
        title = if (keyword.isNullOrBlank()) "小号关系列表" else "小号关系列表($keyword)"
        msg = renderPageMessage(relations, selectedPage, prePage)
        val (realPage, totalPage, _) = pageSlice(relations, selectedPage, prePage)
        column(3) {
            option("<-") {
                selectedPage = (if (realPage <= 1) 1 else realPage - 1)
                refresh()
            }
            option(
                if (totalPage <= 0) "0/0" else "$realPage/$totalPage"
            ) { refresh() }
            option("->") {
                selectedPage = if (totalPage <= 0) 1 else (realPage + 1).coerceAtMost(totalPage)
                refresh()
            }
        }
        option("关闭") { close() }
    }.send().awaitWithTimeout()
}

onEnable {
    refreshState(applyObserver = true)
    VoteEvent.registerCanVoteFilter(this) { player, _ ->
        !isBlocked(player)
    }
}

onDisable {
    restrictedOnline.clear()
    VoteEvent.unregisterCanVoteFilter(this)
}

listen<EventType.PlayerJoin> {
    refreshState(applyObserver = true)
    val player = it.player
    if (isBlocked(player)) {
        player.sendMessage("[yellow]检测到你被标记为同IP小号，已自动剥夺投票权")
    }
}

listen<EventType.PlayerLeave> {
    restrictedOnline.remove(it.player.uuid())
}

listenTo<RequestPermissionEvent> {
    if (!permission.startsWith(blockVotePermissionPrefix)) return@listenTo
    val player = subject as? Player ?: return@listenTo
    if (!isBlocked(player, group)) return@listenTo
    directReturn(PermissionApi.Result.Reject)
}

listen<EventType.PlayerChatEvent>(insert = true) {
    val active = currentVoteEvent() ?: return@listen
    if (!isBlocked(it.player)) return@listen
    when (it.message.lowercase()) {
        "赞成", "反对", "中立", "y", "1", "n", "0", "." -> {
            active.voted.remove(it.player)
            it.player.sendMessage("[red]你被标记为同IP小号，无法参与投票")
        }
    }
}

listen(EventType.Trigger.update) {
    val active = currentVoteEvent() ?: return@listen
    val blocked = active.voted.keys.toList().filter { isBlocked(it) }
    if (blocked.isEmpty()) return@listen
    blocked.forEach { active.voted.remove(it) }
}

command("alts", "查看同IP小号关系".asPlaceHoldString()) {
    aliases = listOf("smallalts", "小号")
    usage = "[page/filter] [page]"
    requirePermission(permissionList)
    body {
        refreshState(applyObserver = false)
        val page = arg.lastOrNull()?.toIntOrNull() ?: 1
        val keyword = arg.getOrNull(0)?.takeUnless { it == page.toString() }
        val filtered = filterRelations(snapshot.relations, keyword)
        val prePage = mapsLikePageSize.coerceAtLeast(1)

        val player = player ?: run {
            if (filtered.isEmpty()) returnReply("[yellow]未找到符合条件的小号关系".asPlaceHoldString())
            returnReply(menu("同IP小号关系", filtered, page, prePage) {
                relationText(it).asPlaceHoldString()
            })
        }

        openPlayerMenu(player, filtered, page, keyword)
    }
}

PermissionApi.registerDefault(permissionList, group = "@admin")
PermissionApi.registerDefault(defaultBypassPermission, group = "AltGuardBypass")
