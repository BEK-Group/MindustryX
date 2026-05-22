@file:Depends("DeterMination")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import java.time.Duration

name = "QAndABroadcast"

private val qAndAEnabled by config.key(true, "Enable tips broadcast")
private val qAndAFileName by config.key("QandA.txt", "Tips text file name")
private val qAndABroadcastIntervalMinutes by config.key(5, "Tips broadcast interval (minutes, <=0 to disable)")

private val defaultTipsText = """
使用doubt只会增加玩家获得新mvp的难度，不会削减已有的mvp。
因为信用的初始值是2，所以即使被质疑一两次，您也无需申诉
信用申诉请使用/recover !不然跑群里也没用，信用>1不用申诉（初始值为2）
如果有人正在搞破坏，/vote sg <uid>可以禁用他的建造功能和控兵功能
发现某人是小号但是没有被系统识别？告诉管理员可以自动合并
小号会继承大号的mvp，但是小号的mvp增加不会反哺大号
挂机180秒才能进入afk模式
被投票的人和投票发起者在投票期间都不能建造，防止乱拆。如果mvp>=6则豁免此规则
mvp代表的是在本服务器的游玩经验，而不是实力
ip相同的，进入次数最多的人为大号！
如果您的进服次数>100，则不会被认为是小号噢
新版插件装上后，很多人都来给我扣黑锅，请不要这样
插件有问题联系风
本服务器的新插件由开放爱公司赞助
mvp的获取和队伍的输赢没有任何关系！
别想着沙盒刷mvp
使用/vote doubt来制裁刷建造分的人
左上角的broad可以查看你的mvp,信用和本局建造分
使用/dinfo查看个人信息
使用/vote rg <uid> <时间(min)>可以回滚某人在x分钟前到现在的所有建筑操作！
使用/vote kg <uid> 可以回滚建筑并踢出某人，常用于拆家的
使用/vote 匿名 可以打开匿名
使用/rank 打开排行榜
在命令前带/t是不会被识别的！投票也一样
mvp越大，投票权重也越大
使用/vote Miner <单位> <数量> <team>来为某个队伍增加矿机，比如mono
使用/guide查看详细说明！
使用/hm可以发送私信，也可以发送给多个人，并支持一键向多个人回复
""".trimIndent()

private val defaultTipsTextEn = """
/vote doubt only makes it harder for a player to gain new MVP; it does not remove existing MVP.
Credit starts at 2, so you usually do not need to appeal after one or two doubt votes.
Use /recover ! for credit appeals; asking in the group will not help. If your credit is above 1, you do not need to appeal.
If someone is griefing, /vote sg <uid> can disable their building and unit control.
If an alt account was not detected automatically, tell an admin so it can be merged.
Alt accounts inherit the main account's MVP, but MVP gained by an alt does not flow back to the main account.
You need to be idle for 180 seconds before entering AFK mode.
The vote target and vote starter cannot build during the vote to prevent griefing. MVP >= 6 bypasses this restriction.
MVP represents experience on this server, not raw skill.
For players with the same IP, the account with the most joins is treated as the main account.
If you have joined more than 100 times, you will not be treated as an alt account.
After the new plugin was installed, many people blamed me for unrelated issues. Please do not do that.
Contact Feng if there is a plugin issue.
This server's new plugin is sponsored by OpenAI.
MVP gain has nothing to do with whether your team wins or loses.
Do not try to farm MVP in sandbox.
Use /vote doubt to punish players farming build score.
The broad display in the top-left shows your MVP, credit, and current build score.
Use /dinfo to view your personal information.
Use /vote rg <uid> <time(min)> to roll back all building actions by someone from X minutes ago until now.
Use /vote kg <uid> to roll back buildings and kick someone, commonly used against griefers.
Use /vote 匿名 to enable anonymity.
Use /rank to open the leaderboard.
Commands prefixed with /t are not recognized, including votes.
Higher MVP gives higher vote weight.
Use /vote Miner <unit> <amount> <team> to add miner units such as mono to a team.
Use /guide to view the detailed guide.
Use /hm to send private messages, send to multiple players, and reply to multiple people in one step.
""".trimIndent()

private val defaultTipTranslations by lazy {
    parseTips(defaultTipsText).zip(parseTips(defaultTipsTextEn)).toMap()
}

@Savable(false)
private val pendingTips = mutableListOf<String>()

private fun tipsFile() = dataDirectory.child(qAndAFileName)

private fun parseTips(text: String): List<String> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .distinct()
    .toList()

private fun ensureTipsFile() {
    val file = tipsFile()
    if (file.exists()) return
    runCatching {
        file.parent().mkdirs()
        file.writeString(defaultTipsText + "\n", false)
    }.onFailure {
        logger.warning("QAndABroadcast: create tips file failed: ${it.message}")
    }
}

private fun refillTips() {
    ensureTipsFile()
    val file = tipsFile()
    val loaded = runCatching {
        parseTips(file.readString())
    }.onFailure {
        logger.warning("QAndABroadcast: read tips file failed: ${it.message}")
    }.getOrDefault(emptyList())
    val tips = loaded.ifEmpty { parseTips(defaultTipsText) }
    pendingTips.clear()
    pendingTips.addAll(tips.shuffled())
}

private fun nextTip(): String? {
    if (pendingTips.isEmpty()) refillTips()
    if (pendingTips.isEmpty()) return null
    return pendingTips.removeAt(pendingTips.lastIndex)
}

private fun isZhLocale(locale: String?): Boolean = locale.orEmpty().lowercase().replace('-', '_').startsWith("zh")

private fun tipTextFor(player: Player, text: String): String =
    if (isZhLocale(player.locale)) text else defaultTipTranslations[text] ?: text

private fun tipMessage(player: Player, text: String): String {
    val mapped = tipTextFor(player, text)
    return if (isZhLocale(player.locale)) {
        "[gold]你知道吗: {text}".with("text" to mapped).toString()
    } else {
        "[gold]Did you know: {text}".with("text" to mapped).toString()
    }
}

private fun sendTipTo(player: Player) {
    if (!qAndAEnabled) return
    val tip = nextTip() ?: return
    player.sendMessage(tipMessage(player, tip))
}

private fun broadcastTip() {
    if (!qAndAEnabled) return
    if (Groups.player.count() <= 0) return
    val tip = nextTip() ?: return
    Groups.player.forEach { it.sendMessage(tipMessage(it, tip)) }
}

onEnable {
    ensureTipsFile()
    refillTips()
}

onEnable {
    loop(Dispatchers.game) {
        val intervalMinutes = qAndABroadcastIntervalMinutes.coerceAtLeast(0)
        if (!qAndAEnabled || intervalMinutes <= 0) {
            delay(Duration.ofMinutes(1).toMillis())
            return@loop
        }
        delay(Duration.ofMinutes(intervalMinutes.toLong()).toMillis())
        broadcastTip()
    }
}

listen<EventType.PlayerJoin> {
    sendTipTo(it.player)
}
