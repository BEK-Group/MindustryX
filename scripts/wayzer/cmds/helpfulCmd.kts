package wayzer.cmds

import arc.graphics.Color
import arc.graphics.Colors
import mindustry.entities.Effect

command("showColor", "显示所有颜色".with()) {
    body {
        reply(Colors.getColors().joinToString("[],") { "[#${it.value}]${it.key}" }.with())
    }
}

command("dosBanClear", "清理dosBan".with()) {
    permission = dotId
    body {
        reply("[yellow]被ban列表: {list}".with("list" to netServer.admins.dosBlacklist))
        netServer.admins.dosBlacklist.clear()
        reply("[green]已清空DosBan".with())
    }
}

val map by lazy {
    Fx::class.java.fields.filter { it.type == Effect::class.java }
        .associate { it.name to (it.get(null) as Effect) }
}

command("showEffect", "显示粒子效果".with()) {
    usage = "[-a 全体可见] [类型=列出] [半径=10] [颜色=red]"
    body {
        val all = checkArg("-a")
        val type = arg.getOrNull(0)?.let { map[it] }
            ?: returnReply("[red]请输入类型: {list}".with("list" to map.keys))
        val arg1 = arg.getOrNull(1)?.toFloatOrNull() ?: 10f
        val arg2 = arg.getOrNull(2)?.let { Color.valueOf(it) } ?: Color.red

        if (all && !hasPermission("wayzer.ext.showEffect")) replyNoPermission()
        if (all) Call.effect(type, player!!.x, player!!.y, arg1, arg2)
        else Call.effect(player!!.con, type, player!!.x, player!!.y, arg1, arg2)
    }
}