package wayzer

import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.define.ScriptDsl
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.*
import coreMindustry.MenuBuilder
import coreMindustry.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import mindustry.Vars
import mindustry.gen.Groups
import mindustry.gen.Player
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

fun Player.voteText(zh: String, en: String): String =
    if (locale.orEmpty().lowercase(Locale.ROOT).replace('-', '_').startsWith("zh")) zh else en

@Suppress("MemberVisibilityCanBePrivate")
class VoteEvent(
    scope: CoroutineScope,
    val starter: Player,
    val voteDesc: PlaceHoldContext,
    val extDesc: String = "",
    val supportSingle: Boolean = false,
    val canVote: (Player) -> Boolean = { !it.dead() },
    val canSee: (Player) -> Boolean = { true },
    val requireNum: (all: Double) -> Double = { floor(it * 0.6) },
    val fastSuccess: Boolean = true,
    val extDescText: PlaceHoldContext? = null,
) : Event, Event.Cancellable {
    // Binary compatibility for old cached scripts compiled before `requireNum` was added.
    @Suppress("unused")
    constructor(
        scope: CoroutineScope,
        starter: Player,
        voteDesc: PlaceHoldContext,
        extDesc: String = "",
        supportSingle: Boolean = false,
        canVote: (Player) -> Boolean = { !it.dead() },
        canSee: (Player) -> Boolean = { true },
        fastSuccess: Boolean = true,
    ) : this(
        scope,
        starter,
        voteDesc,
        extDesc,
        supportSingle,
        canVote,
        canSee,
        { all -> floor(all * 0.6) },
        fastSuccess,
        null
    )

    enum class Action { Agree, Disagree, Ignore, Quit, Join }

    val voted = mutableMapOf<Player, Boolean?>()
    var succeed = false
    val endTime: Instant = Instant.now() + voteTime
    private val requireAllVotes by lazy {
        listOf(2.0, 4.0, 8.0).all { sample ->
            abs(requireNum(sample) - sample) < 1e-6
        }
    }
    override var cancelled
        get() = !mainJob.isActive
        set(value) {
            if (value) mainJob.cancel()
        }

    private fun effectiveRequireNum(all: Double): Double {
        val normalizedAll = all.coerceAtLeast(0.0)
        val raw = requireNum(normalizedAll)
        if (requireAllVotes) return raw.coerceAtLeast(0.0)
        val maxNeed = (normalizedAll - 1.0).coerceAtLeast(0.0)
        return raw.coerceAtMost(maxNeed).coerceAtLeast(0.0)
    }

    private fun isObserver(player: Player): Boolean = player.team().id == 255

    private fun thresholdBaseWeight(): Double {
        // 观战玩家不应按完整活跃票权抬高阈值，因此统一按半票折算。
        val observerCanVoteWeight = allCanVote().filter(::isObserver).sumOf(::voteWeight)
        return (allCanVoteWeight() - middle() - observerCanVoteWeight + observerCanVoteWeight * 0.5)
            .coerceAtLeast(0.0)
    }

    suspend fun awaitResult(): Boolean {
        mainJob.join()
        return succeed
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mainJob = scope.launch(Dispatchers.game + CoroutineName("Vote Service"), CoroutineStart.LAZY) main@{
        if ((coolDowns[starter.uuid()] ?: 0) > System.currentTimeMillis()) {
            starter.sendMessage("[yellow]你刚发起的投票投票失败，投票冷却中".with())
            return@main
        }
        emitAsync {
            if (supportSingle && allCanVote().run { isEmpty() || singleOrNull() == starter }) {
                if (System.currentTimeMillis() - lastAction > 60_000) {
                    broadcastVisible("[yellow]单人快速投票{type}成功".with("type" to voteDesc))
                    lastAction = System.currentTimeMillis()
                    succeed = true
                    return@emitAsync
                } else broadcastVisible("[red]距离上一玩家离开或上一投票成功不足1分钟,快速投票失败".with())
            }
            if (!active.compareAndSet(null, this@VoteEvent)) {
                return@emitAsync cancel()
            }
            launch {
                try {
                    awaitCancellation()
                } finally {
                    active.compareAndSet(this@VoteEvent, null)
                }
            }
            //文字投票
            fun voteTextFor(player: Player): PlaceHoldString {
                val voteTextTemplate = player.voteText(
                    """
                    [yellow]{starter.name}[yellow]发起{type}[yellow]投票
                    {ext}
                    [yellow]你可以在投票结束前使用文字投票，[green]赞成(y/1)[][yellow]中立(.)[][red]反对(n/0)[]投票{delayTip}
                    [lightgray]计票方式: 按票权加权(可为小数)，状态将显示加权票数与通过阈值。[]
                    """.trimIndent(),
                    """
                    [yellow]{starter.name}[yellow] started a {type}[yellow] vote
                    {ext}
                    [yellow]Vote in chat before it ends: [green]yes(y/1)[][yellow]neutral(.)[][red]no(n/0)[]{delayTip}
                    [lightgray]Counting method: weighted by vote power (decimals allowed); status shows weighted votes and the required threshold.[]
                    """.trimIndent()
                )
                val delayTip = if (menuDelay <= 0) "".asPlaceHoldString()
                else "\n[green] (若未投票,{delay}秒后将弹窗提示)".with("delay" to menuDelay)
                return voteTextTemplate.with(
                    "starter" to starter,
                    "type" to voteDesc,
                    "ext" to renderExtDesc(player),
                    "delayTip" to delayTip
                )
            }
            allCanSee().filter(::canSeeNow).forEach { it.sendMessage(voteTextFor(it)) }
            allCanVote().filter(::canSeeNow).forEach { it.sendMessage(voteTextFor(it), MsgType.Announce) }
            vote(starter, Action.Agree)
            //弹窗投票
            if (menuDelay >= 0) allCanVote().forEach {
                launch(Dispatchers.game) {
                    delay(menuDelay * 1000L)
                    if (it in voted) return@launch
                    openMenu(it)
                }
            }
            //投票超时处理
            val actionHandler = launch { actionHandler() }
            select {
                actionHandler.onJoin {}
                onTimeout(voteTime.toMillis()) {
                    actionHandler.cancel()
                    withCheckVoted {
                        val all = thresholdBaseWeight()
                        val minVoteWeight = allCanVoteWeight() / 2
                        if (votedWeight() < minVoteWeight && agree() < effectiveRequireNum(minVoteWeight)) {
                            broadcastVisible("[yellow]投票参与人数过少".with())
                        } else {
                            succeed = agree() >= effectiveRequireNum(all)
                        }
                    }
                }
            }

            if (!succeed) coolDowns[starter.uuid()] = System.currentTimeMillis() + voteCoolDown.toMillis()
            val finishVisible = allCanSee()
            if (finishVisible.isNotEmpty()) {
                finishVisible.forEach {
                    val t = if (succeed) {
                        it.voteText(
                            "[yellow]{starter.name}[yellow]发起{type}[yellow]投票成功. {status}",
                            "[yellow]{starter.name}[yellow] started a {type}[yellow] vote: passed. {status}"
                        )
                    } else {
                        it.voteText(
                            "[yellow]{starter.name}[yellow]发起{type}[yellow]投票失败. {status}",
                            "[yellow]{starter.name}[yellow] started a {type}[yellow] vote: failed. {status}"
                        )
                    }
                    it.sendMessage(t.with("starter" to starter, "type" to voteDesc, "status" to status(it)))
                }
            }
        }
        coroutineContext.cancelChildren()
    }

    fun canVoteNow(player: Player): Boolean {
        if (!canVote(player)) return false
        allExtraCanVoteFilters().forEach { filter ->
            val allow = runCatching { filter(player, this) }.getOrElse {
                script.logger.warning("Vote external canVote filter failed: ${it.message}")
                true
            }
            if (!allow) return false
        }
        return true
    }

    fun canSeeNow(player: Player): Boolean = canSee(player)

    fun voteWeight(player: Player): Double = companionVoteWeight(player)
    fun voteMvpCount(player: Player): Double = companionMvpCount(player)
    fun allCanVote() = Groups.player.filter(::canVoteNow)
    fun allCanSee() = Groups.player.filter(::canSeeNow)
    private fun broadcastVisible(message: PlaceHoldContext) {
        val visible = allCanSee()
        if (visible.isNotEmpty()) {
            broadcast(message, players = visible, quite = true)
        }
    }
    fun allCanVoteWeight() = allCanVote().sumOf(::voteWeight)
    fun votedWeight() = voted.keys.filter(::canVoteNow).sumOf(::voteWeight)
    fun agree() = voted.entries.filter { it.value == true && canVoteNow(it.key) }.sumOf { voteWeight(it.key) }
    fun middle() = voted.entries.filter { it.value == null && canVoteNow(it.key) }.sumOf { voteWeight(it.key) }
    fun disagree() = voted.entries.filter { it.value == false && canVoteNow(it.key) }.sumOf { voteWeight(it.key) }
    fun notVote() = (allCanVoteWeight() - votedWeight()).coerceAtLeast(0.0)
    private fun formatWeight(value: Double): String {
        val rounded = round(value * 100.0) / 100.0
        return "%.2f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
    }
    fun status() = withCheckVoted {
        val agreeW = agree()
        val middleW = middle()
        val disagreeW = disagree()
        val notVoteW = notVote()
        val all = thresholdBaseWeight()
        val need = effectiveRequireNum(all)
        "[green]\uE804${formatWeight(agreeW)} [yellow]\uE853${formatWeight(middleW)} [red]\uE805${formatWeight(disagreeW)} [grey]\uE88F${formatWeight(notVoteW)} [accent]阈值:${formatWeight(need)}"
    }

    private fun renderExtDesc(player: Player): String = extDescText?.toPlayer(player) ?: if (extDesc.isBlank()) "" else extDesc.with().toPlayer(player)

    fun statusFor(player: Player?): String = player?.let { status(it) } ?: status()

    private fun status(player: Player): String = withCheckVoted {
        val agreeW = agree()
        val middleW = middle()
        val disagreeW = disagree()
        val notVoteW = notVote()
        val all = thresholdBaseWeight()
        val need = effectiveRequireNum(all)
        val threshold = player.voteText("阈值", "Required")
        "[green]\uE804${formatWeight(agreeW)} [yellow]\uE853${formatWeight(middleW)} [red]\uE805${formatWeight(disagreeW)} [grey]\uE88F${formatWeight(notVoteW)} [accent]$threshold:${formatWeight(need)}"
    }

    inline fun <T> withCheckVoted(body: () -> T): T {
        voted.entries.removeIf { !canVoteNow(it.key) }
        return body()
    }

    fun vote(p: Player, action: Action) {
        handleAction.trySend(p to action)
    }

    suspend fun openMenu(p: Player) {
        MenuBuilder<Unit>(p.voteText("投票", "Vote")) {
            msg = "[yellow]{starter.name}[yellow]发起{type}[yellow]投票\n{ext}".with(
                "starter" to starter,
                "type" to voteDesc,
                "ext" to renderExtDesc(p)
            ).toPlayer(p)
            option(p.voteText("赞成", "Yes")) { vote(p, Action.Agree) }
            option(p.voteText("中立", "Neutral")) { vote(p, Action.Ignore) }
            option(p.voteText("反对", "No")) { vote(p, Action.Disagree) }
            newRow()
            option(p.voteText("待定", "Later")) {
                p.sendMessage(p.voteText("[yellow]将在20s后再次提示", "[yellow]A vote prompt will be shown again in 20s"))
                script.launch(Dispatchers.game) {
                    delay(20_000)
                    if (active.get() == this@VoteEvent && p !in voted) openMenu(p)
                }
            }
        }.sendTo(p, 60_000)
    }

    private val handleAction = Channel<Pair<Player, Action>>(Channel.UNLIMITED)
    private suspend fun actionHandler() = coroutineScope {
        var shouldStop = false
        for ((player, event) in handleAction) {
            when (event) {
                Action.Join -> if (canVoteNow(player)) {
                    launch(Dispatchers.gamePost) { openMenu(player) }
                }

                Action.Quit -> voted.remove(player)
                Action.Agree, Action.Disagree, Action.Ignore -> {
                    if (!canVoteNow(player)) {
                        player.sendMessage("[red]你不能对此投票".with())
                        continue
                    }
                    voted[player] = when (event) {
                        Action.Agree -> true
                        Action.Disagree -> false
                        else -> null
                    }
                    val weight = formatWeight(voteWeight(player))
                    val source = if (player.admin) {
                        "[accent]admin[]"
                    } else {
                        player.voteText("月mvp", "monthly MVP") + "=${formatWeight(voteMvpCount(player))}"
                    }
                    player.sendMessage(
                        "[green]投票成功[] [lightgray](票权:{weight}, 来源:{source})[]\n{status}".with(
                            "weight" to weight,
                            "source" to source,
                            "status" to status(player)
                        )
                    )
                }
            }

            //fast path
            withCheckVoted {
                val all = thresholdBaseWeight()
                when {
                    fastSuccess && agree() >= this@VoteEvent.effectiveRequireNum(all) -> {
                        succeed = true
                        shouldStop = true
                    }

                    all - disagree() < this@VoteEvent.effectiveRequireNum(all) -> {
                        succeed = false
                        shouldStop = true
                    }

                    else -> {}
                }
            }
            if (shouldStop) break
        }
        handleAction.close()
    }

    init {
        mainJob.start()
    }

    object VoteCommands : Commands()

    companion object : Event.Handler() {
        internal val script = thisContextScript()
        private val voteTime by script.config.key(Duration.ofSeconds(60)!!, "投票时间")
        private val voteCoolDown by script.config.key(Duration.ofMinutes(5)!!, "投票失败冷却时间")
        private val menuDelay by script.config.key(20, "弹窗投票显示时间,单位秒", "0为立即显示，-1纯文字投票")
        private val adminDefaultVoteWeight by script.config.key(2, "管理员默认票权")
        private val mvpHistoryFileName by script.config.key("mvpTeamBalancer-history.tsv", "MVP历史文件名(位于scripts/data)")
        private val mvpHistoryDefault by script.config.key(0.5, "未命中历史记录时的默认MVP值")
        private val mvpHistoryRefreshSeconds by script.config.key(5, "MVP历史缓存刷新周期(秒)")

        internal val active = AtomicReference<VoteEvent?>(null)
        internal var lastAction = 0L //最后一次玩家退出或投票成功时间,用于处理单人投票
        internal val coolDowns = mutableMapOf<String, Long>()
        private val extraCanVoteFilters = LinkedHashMap<Any, (Player, VoteEvent) -> Boolean>()
        @Volatile private var mvpHistoryCache = emptyMap<String, Double>()
        @Volatile private var mvpHistoryLoadedAt = 0L

        @Synchronized
        fun registerCanVoteFilter(owner: Any, filter: (Player, VoteEvent) -> Boolean) {
            extraCanVoteFilters[owner] = filter
        }

        @Synchronized
        fun unregisterCanVoteFilter(owner: Any) {
            extraCanVoteFilters.remove(owner)
        }

        @Synchronized
        internal fun allExtraCanVoteFilters(): List<(Player, VoteEvent) -> Boolean> {
            return extraCanVoteFilters.values.toList()
        }

        private fun weightByMvpCount(mvpCount: Double): Double = when {
            mvpCount < 6.0 -> 1.0
            else -> (1.5 + (mvpCount - 6.0) * 0.125).coerceAtMost(4.0)
        }

        private fun resolveMvpHistoryFile() = run {
            val scriptsDataFile = Vars.dataDirectory.child("scripts").child("data").child(mvpHistoryFileName)
            if (scriptsDataFile.exists()) return@run scriptsDataFile
            val legacyFile = Vars.dataDirectory.child(mvpHistoryFileName)
            if (legacyFile.exists()) return@run legacyFile
            scriptsDataFile
        }

        private fun refreshMvpHistoryIfNeeded(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val refreshMs = mvpHistoryRefreshSeconds.coerceAtLeast(1) * 1000L
            if (!force && now - mvpHistoryLoadedAt < refreshMs) return
            val file = resolveMvpHistoryFile()
            val next = mutableMapOf<String, Double>()
            if (file.exists()) {
                runCatching { file.readString() }.onSuccess { text ->
                    text.lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val parts = line.split('\t', limit = 4)
                        if (parts.size < 2) return@forEach
                        val uuid = parts[0].trim()
                        val monthMvp = parts[1].trim().toDoubleOrNull() ?: return@forEach
                        if (uuid.isNotEmpty()) {
                            next[uuid] = monthMvp.coerceAtLeast(0.0)
                        }
                    }
                }.onFailure {
                    script.logger.warning("Vote weight: read ${mvpHistoryFileName} failed: ${it.message}")
                }
            }
            mvpHistoryCache = next
            mvpHistoryLoadedAt = now
        }

        private fun mvpCount(uuid: String): Double {
            refreshMvpHistoryIfNeeded()
            return mvpHistoryCache[uuid] ?: mvpHistoryDefault
        }

        fun companionMvpCount(player: Player): Double {
            return mvpCount(player.uuid()).coerceAtLeast(0.0)
        }

        fun companionVoteWeight(player: Player): Double {
            if (player.admin) return adminDefaultVoteWeight.coerceAtLeast(1).toDouble()
            return weightByMvpCount(mvpCount(player.uuid())).coerceAtLeast(1.0)
        }

        fun highMvpVoteLockBypass(player: Player, thresholdExclusive: Double = 6.0): Boolean {
            return companionMvpCount(player) > thresholdExclusive
        }
    }
}

@Deprecated("use VoteEvent")
object VoteService {
    @ScriptDsl
    fun Script.addSubVote(
        desc: String, usage: String, vararg aliases: String, body: suspend CommandContext.() -> Unit
    ) {
        VoteEvent.VoteCommands += CommandInfo(this, aliases.first(), desc.with()) {
            this.usage = usage
            this.aliases = aliases.toList()
            body(body)
            if (permission.isEmpty()) permission = "wayzer.vote." + aliases.first().lowercase()
        }
    }

    fun start(
        starter: Player,
        voteDesc: PlaceHoldContext,
        extDesc: String = "",
        supportSingle: Boolean = false,
        canVote: (Player) -> Boolean = { !it.dead() },
        canSee: (Player) -> Boolean = { true },
        requireNum: (all: Double) -> Double = { floor(it * .6) },
        fastSuccess: Boolean = true,
        extDescText: PlaceHoldContext? = null,
        onSuccess: suspend (Map<Player, Boolean?>) -> Unit
    ) {
        VoteEvent.script.launch(Dispatchers.game) {
            val event = VoteEvent(this, starter, voteDesc, extDesc, supportSingle, canVote, canSee, requireNum, fastSuccess, extDescText)
            if (event.awaitResult()) onSuccess(event.voted)
        }
    }
}
