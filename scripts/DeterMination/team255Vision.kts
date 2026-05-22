@file:Depends("DeterMination")

package DeterMination

import arc.struct.Bits
import kotlinx.coroutines.Dispatchers
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import java.lang.reflect.Array
import java.lang.reflect.Constructor
import java.lang.reflect.Field

name = "Team255Vision"

private val team255VisionEnabled by config.key(true, "Enable team 255 fog reveal")
private val team255VisionIntervalMs by config.key(250L, "Team 255 fog reveal refresh interval (ms)")
private val team255VisionNeedObserver by config.key(true, "Only maintain fog reveal when team 255 has players")
private val team255VisionLogFailure by config.key(true, "Log warning on reflection failure")
private val team255VisionForceClearUnit by config.key(true, "Force team 255 players to have no unit, preventing dynamic fog override")

private val observerTeam = Team.all[255]!!
private var warnedBridgeFailure = false

private class Fog255Bridge {
    private val fogDataClass: Class<*> by lazy { Class.forName("mindustry.game.FogControl\$FogData") }
    private val fogField: Field by lazy {
        Vars.fogControl.javaClass.getDeclaredField("fog").apply { isAccessible = true }
    }
    private val fogDataCtor: Constructor<*> by lazy {
        fogDataClass.getDeclaredConstructor().apply { isAccessible = true }
    }
    private val readField: Field by lazy {
        fogDataClass.getDeclaredField("read").apply { isAccessible = true }
    }
    private val writeField: Field by lazy {
        fogDataClass.getDeclaredField("write").apply { isAccessible = true }
    }
    private val staticDataField: Field by lazy {
        fogDataClass.getDeclaredField("staticData").apply { isAccessible = true }
    }

    private fun getOrCreateFogArray(): Any {
        val current = fogField.get(Vars.fogControl)
        if (current != null) return current
        val created = Array.newInstance(fogDataClass, 256)
        fogField.set(Vars.fogControl, created)
        return created
    }

    private fun getOrCreateData(array: Any): Any {
        val current = Array.get(array, observerTeam.id)
        if (current != null) return current
        val created = fogDataCtor.newInstance()
        Array.set(array, observerTeam.id, created)
        return created
    }

    private fun fill(bits: Bits, len: Int) {
        bits.clear()
        if (len > 0) bits.set(0, len)
    }

    fun fillObserverVision() {
        val width = world.width()
        val height = world.height()
        if (width <= 0 || height <= 0) return
        val len = width * height
        val fogArray = getOrCreateFogArray()
        val data = getOrCreateData(fogArray)
        fill(readField.get(data) as Bits, len)
        fill(writeField.get(data) as Bits, len)
        fill(staticDataField.get(data) as Bits, len)
    }
}

private val fog255Bridge = Fog255Bridge()

private fun hasObserverPlayer(): Boolean =
    Groups.player.find { it.team() == observerTeam } != null

private fun shouldMaintainVision(): Boolean {
    if (!team255VisionEnabled) return false
    if (!state.rules.pvp || !state.rules.fog) return false
    if (team255VisionNeedObserver && !hasObserverPlayer()) return false
    return true
}

private fun normalizeObserverPlayers() {
    if (!team255VisionForceClearUnit) return
    Groups.player.forEach { player ->
        if (player.team() == observerTeam && !player.dead()) {
            player.clearUnit()
        }
    }
}

private fun ensureObserverVision(force: Boolean = false) {
    if (!force && !shouldMaintainVision()) return
    runCatching {
        normalizeObserverPlayers()
        fog255Bridge.fillObserverVision()
        warnedBridgeFailure = false
    }.onFailure { error ->
        if (!warnedBridgeFailure && team255VisionLogFailure) {
            warnedBridgeFailure = true
            logger.warning("Team255Vision: fill fog data failed: ${error.message}")
        }
    }
}

onEnable {
    ensureObserverVision(force = true)
}

listen<EventType.WorldLoadEvent> {
    launch(Dispatchers.game) {
        delay(1_000L)
        ensureObserverVision(force = true)
    }
}

listen<EventType.PlayerJoin> {
    if (it.player.team() == observerTeam) ensureObserverVision(force = true)
}

onEnable {
    loop(Dispatchers.game) {
        val interval = team255VisionIntervalMs.coerceAtLeast(80L)
        delay(interval)
        ensureObserverVision()
    }
}
