@file:Depends("coreMindustry")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with

name = "DeterMination Scripts"

private val showLoadSummary by config.key(true, "加载后显示模块状态统计")
private val feedbackGroup by config.key("188709300", "问题反馈QQ群")
private val detailLimit by config.key(5, "失败详情最多显示条数")
private val summaryDelaySeconds by config.key(4, "加载完成提示延迟(秒)")

private fun collectLoadSummary() {
    val scripts = ScriptRegistry.allScripts { it.id.startsWith("$id/") }.sortedBy { it.id }
    val loadSuccess = scripts.count { it.failReason == null && it.scriptState.loaded }
    val enabledScripts = scripts.filter { it.scriptState.enabled }
    val enabled = enabledScripts.size
    val failed = scripts.filter { it.failReason != null }
    val pending = scripts.size - enabled - failed.size
    val okLines = enabledScripts.take(detailLimit.coerceAtLeast(0)).map { "[green]- ${it.id}[]" }
    val hiddenOk = enabledScripts.size - okLines.size
    val failLines = failed.take(detailLimit.coerceAtLeast(0)).map {
        "[red]- ${it.id}[] : ${it.failReason.orEmpty()}"
    }
    val hiddenFail = failed.size - failLines.size
    val msg = buildString {
        appendLine("[yellow]==== [accent]DeterMination 脚本加载完成[] ====")
        appendLine("[green]成功: $enabled[] [red]失败: ${failed.size}[] [gray]未启用: $pending[]")
        if (okLines.isNotEmpty()) {
            appendLine("[green]已启用脚本(最多显示${detailLimit}条):")
            appendLine("{ok|joinLines}")
            if (hiddenOk > 0) appendLine("[gray]... 还有 $hiddenOk 个已启用脚本未显示")
        }
        appendLine("[yellow]反馈群: [accent]$feedbackGroup[]")
        if (failLines.isNotEmpty()) {
            appendLine("[scarlet]失败详情:")
            append("{fails|joinLines}")
            if (hiddenFail > 0) appendLine("\n[gray]... 还有 $hiddenFail 条失败详情未显示")
        }
    }.with("ok" to okLines, "fails" to failLines)
    logger.info("    ____       __            __  ____             __  _           ")
    logger.info("   / __ \\___  / /____  _____/  |/  (_)___  ____ _/ /_(_)___  ____ ")
    logger.info("  / / / / _ \\/ __/ _ \\/ ___/ /|_/ / / __ \\/ __ `/ __/ / __ \\/ __ \\")
    logger.info(" / /_/ /  __/ /_/  __/ /  / /  / / / / / / /_/ / /_/ / /_/ / / / /")
    logger.info("/_____/\\___/\\__/\\___/_/  /_/  /_/_/_/ /_/\\__,_/\\__/_/\\____/_/ /_/ ")
    logger.info("反馈群: $feedbackGroup")
    logger.info("统计: 共找到${scripts.size}脚本,加载成功$loadSuccess,启用成功$enabled,出错${failed.size}")
    broadcast(msg, MsgType.InfoMessage, quite = true)
    logger.info("DeterMination load summary: success=$enabled fail=${failed.size} pending=$pending, feedbackGroup=$feedbackGroup")
}

onEnable {
    if (!showLoadSummary) return@onEnable
    // 延迟一小段时间，等待同模块脚本状态稳定后再汇总
    launch {
        delay(summaryDelaySeconds.coerceAtLeast(0) * 1000L)
        collectLoadSummary()
    }
}
