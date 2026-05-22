package wayzer.user

import arc.util.serialization.Base64Coder
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import mindustry.net.Administration
import java.security.MessageDigest
import java.time.Duration

val md5Digest = MessageDigest.getInstance("md5")!!
fun shortStr(str: String): String {
    fun md5Md5(bs: ByteArray) = synchronized(md5Digest) {
        // md5(md5(bs)+bs)
        md5Digest.update(md5Digest.digest(bs))
        md5Digest.digest(bs)
    }

    val bs = md5Md5(str.toByteArray())
    return Base64Coder.encode(bs).sliceArray(0..2).map {
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

private fun normalizeShortId(id: String) = id.trim()

val shortIdOverrideByUuid = mutableMapOf<String, String>()
val uuidByShortIdOverride = mutableMapOf<String, String>()

/**
 * @return true if set success, false if shortId already used by another uuid
 */
fun setShortIdOverride(uuid: String, shortId: String): Boolean {
    val id = normalizeShortId(shortId)
    if (id.isEmpty()) return false
    uuidByShortIdOverride[id]?.let { usedBy ->
        if (usedBy != uuid) return false
    }
    shortIdOverrideByUuid[uuid]?.let { old ->
        uuidByShortIdOverride.remove(normalizeShortId(old), uuid)
    }
    shortIdOverrideByUuid[uuid] = id
    uuidByShortIdOverride[id] = uuid
    return true
}

fun clearShortIdOverride(uuid: String) {
    shortIdOverrideByUuid.remove(uuid)?.let { old ->
        uuidByShortIdOverride.remove(normalizeShortId(old), uuid)
    }
}

fun clearAllShortIdOverride() {
    shortIdOverrideByUuid.clear()
    uuidByShortIdOverride.clear()
}

fun Player.shortID() = shortIdOverrideByUuid[uuid()] ?: shortStr(uuid())

val shortIDs: Cache<String, String> = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(60)).build()
listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    val new = shortStr(uuid)
    val old = shortIDs.getIfPresent(new)
    shortIDs.put(new, uuid)
    if (old != null && old != uuid)
        logger.warning("3位ID碰撞: $uuid $old")
}

onEnable {
    PlayerData.IGetUidByShortId.provide(this, object : PlayerData.IGetUidByShortId {
        override fun getShortId(data: PlayerData): String =
            shortIdOverrideByUuid[data.uuid] ?: shortStr(data.uuid)

        override fun getUidByShortId(id: String): String? =
            uuidByShortIdOverride[normalizeShortId(id)]
                ?: Groups.player.find { it.uuid() == id || it.shortID() == id }?.uuid()
                ?: shortIDs.getIfPresent(id)
    })
}

registerVarForType<Player>().apply {
    registerChild("shortID", "uuid 3位前缀,可以展现给其他玩家") { it.shortID() }
    registerChild("suffix.9shortID", "名字后缀：3位ID") { " [gray]${it.shortID()}[]" }
}
registerVarForType<Administration.PlayerInfo>().apply {
    registerChild("shortID", "uuid 3位前缀,可以展现给其他玩家") { shortStr(it.id) }
}
