package wayzer.user

import cf.wayzer.placehold.DynamicVar
import coreLibrary.lib.util.ServiceRegistry
import mindustry.gen.Iconc

val logVersion by config.key(false, "记录玩家的版本信息")

interface SuffixMaskPolicy {
    fun hideSuffix(player: Player): Boolean

    companion object : ServiceRegistry<SuffixMaskPolicy>()
}

private var suffixMaskProvider: ((Player) -> Boolean)? = null

fun registerSuffixMaskProvider(provider: (Player) -> Boolean) {
    suffixMaskProvider = provider
}

fun clearSuffixMaskProvider() {
    suffixMaskProvider = null
}

private fun Player.shouldHideSuffix(): Boolean =
    suffixMaskProvider?.invoke(this) == true || SuffixMaskPolicy.getOrNull()?.hideSuffix(this) == true

val cache = mutableMapOf<String, String>()
listen<EventType.PlayerLeave> { cache.remove(it.player.uuid()) }
fun Player.getSuffix(): String? {
    if (shouldHideSuffix()) return null
    cache[uuid()]?.let { return it }
    launch {
        cache[uuid()] = when {
            hasPermission("suffix.admin") -> "${Iconc.admin}"
            hasPermission("suffix.vip") -> "[gold]V[]"
            else -> return@launch
        }
    }
    return null
}

@Savable
val clientType = mutableMapOf<String, Char>()
customLoad(::clientType) { clientType.putAll(it) }
listen<EventType.PlayerLeave> { clientType.remove(it.player.uuid()) }
onEnable {
    netServer.addPacketHandler("ARC") { p, v ->
        if (logVersion)
            logger.info("ARC ${p.name} $v")
        clientType[p.uuid()] = Iconc.blockArc
    }
    netServer.addPacketHandler("MDTX") { p, v ->
        if (logVersion)
            logger.info("MDTX ${p.name} $v")
        clientType[p.uuid()] = 'X'
    }
    netServer.addPacketHandler("fooCheck") { p, v ->
        if (logVersion)
            logger.info("FOO ${p.name} $v")
        clientType[p.uuid()] = '⒡'
    }
}
onDisable {
    suffixMaskProvider = null
    netServer.getPacketHandlers("ARC").clear()
    netServer.getPacketHandlers("MDTX").clear()
    netServer.getPacketHandlers("fooCheck").clear()
}


registerVarForType<Player>().apply {

    registerChild("suffix.s2-clientType", "客户端类型后缀",  { p -> if (p.shouldHideSuffix()) null else clientType[p.uuid()] })
    registerChild("suffix.s3-computer", "电脑玩家后缀",  { p -> if (p.shouldHideSuffix()) null else ''.takeIf { !p.con.mobile } })
    registerChild("suffix.s5-group", "权限组后缀",  { it.getSuffix() })
}

PermissionApi.registerDefault("suffix.admin", group = "@admin")
PermissionApi.registerDefault("suffix.vip", group = "@vip")
