package wayzer.cmds

import mindustry.Vars

command("js", "UNSAFE: 在服务器运行js") {
    permission = dotId
    body {
        val js = arg.joinToString(" ")
        reply("[red]JS> []{cmd}".with("cmd" to js))
        Vars.player = player
        try {
            reply(mods.scripts.runConsole(js).asPlaceHoldString())
        } finally {
            Vars.player = null
        }
    }
}