@file:Depends("javalin", "HTTP service")
@file:Depends("coreMindustry")

package javalin

import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.lib.broadcast

name = "VoiceBridge"

private val routePrefix by config.key("/voice-bridge", "HTTP route prefix")
private val sharedToken by config.key("", "Shared token for external voice bridge")
private val defaultSpeaker by config.key("Voice", "Default speaker name")
private val maxTextLength by config.key(200, "Maximum accepted text length")
private val maxSpeakerLength by config.key(32, "Maximum speaker name length")
private val logRequests by config.key(true, "Log incoming bridge requests")

private fun normalizePrefix(): String {
    val value = routePrefix.trim().ifEmpty { "/voice-bridge" }
    return if (value.startsWith('/')) value else "/$value"
}

private fun sanitize(raw: String, maxLength: Int): String {
    return raw.replace('\r', ' ').replace('\n', ' ').trim().take(maxLength.coerceAtLeast(1))
}

webRoutes {
    val prefix = normalizePrefix()

    get("$prefix/about") { ctx ->
        ctx.result("VoiceBridge ok prefix=$prefix")
    }

    post("$prefix/chat") { ctx ->
        val provided = ctx.header("X-Voice-Token") ?: ctx.formParam("token") ?: ctx.queryParam("token") ?: ""
        if (sharedToken.isNotBlank() && provided != sharedToken) {
            ctx.status(403)
            ctx.result("forbidden")
            return@post
        }

        val speaker = sanitize(
            ctx.formParam("speaker") ?: ctx.queryParam("speaker") ?: defaultSpeaker,
            maxSpeakerLength
        ).ifEmpty { defaultSpeaker }
        val text = sanitize(
            ctx.formParam("text") ?: ctx.queryParam("text") ?: ctx.body(),
            maxTextLength
        )
        if (text.isEmpty()) {
            ctx.status(400)
            ctx.result("missing text")
            return@post
        }

        broadcast("[violet][Voice][] [accent]$speaker[]: [white]$text".with(), quite = true)
        if (logRequests) logger.info("VoiceBridge broadcast from $speaker len=${text.length}")
        ctx.result("ok")
    }
}
