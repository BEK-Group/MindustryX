@file:Depends("coreMindustry/scoreboard")

package coreMindustry

// Compatibility stub for legacy id `coreMindustry/scorebroad`.
// Actual scoreboard logic is provided by `coreMindustry/scoreboard`.
onEnable {
    logger.info("scorebroad compatibility bridge enabled (delegate to coreMindustry/scoreboard)")
}
