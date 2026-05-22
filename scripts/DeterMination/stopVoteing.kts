@file:Depends("wayzer/vote", "投票实现")

package DeterMination

import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.PermissionApi
import wayzer.VoteEvent
import java.util.concurrent.atomic.AtomicReference

name = "StopVoteing"

private val permissionStopVoteing = "determination.vote.stopvoteing"
private val permissionPassVoteing = "determination.vote.passvoteing"

private fun currentVote(): VoteEvent? {
    // VoteEvent.active is internal in current wayzer版本，使用反射兼容读取
    val companion = runCatching {
        VoteEvent::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
    }.recoverCatching {
        VoteEvent::class.java.getField("Companion").get(null)
    }.getOrNull() ?: return null

    val ref = runCatching {
        val f = companion.javaClass.getDeclaredField("active")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        f.get(companion) as? AtomicReference<VoteEvent?>
    }.recoverCatching {
        val f = VoteEvent::class.java.getDeclaredField("active")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        f.get(null) as? AtomicReference<VoteEvent?>
    }.getOrNull() ?: return null
    return ref.get()
}

command("stopvoteing", "直接终止当前投票(按失败处理)".with()) {
    aliases = listOf("st")
    requirePermission(permissionStopVoteing)
    body {
        val active = currentVote()
            ?: returnReply("[yellow]当前没有进行中的投票".with())
        active.succeed = false
        active.cancelled = true
        broadcast("[red]当前投票已被管理员终止，结果按失败处理".with())
    }
}

command("passvoteing", "直接通过当前投票(按成功处理)".with()) {
    aliases = listOf("pt")
    requirePermission(permissionPassVoteing)
    body {
        val active = currentVote()
            ?: returnReply("[yellow]当前没有进行中的投票".with())
        active.succeed = true
        active.cancelled = true
        broadcast("[green]当前投票已被管理员强制通过".with())
    }
}

PermissionApi.registerDefault(permissionStopVoteing, group = "@admin")
PermissionApi.registerDefault(permissionPassVoteing, group = "@admin")
