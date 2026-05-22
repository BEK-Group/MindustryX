package kcp

import cf.wayzer.scriptAgent.define.annotations.ImportData
import cf.wayzer.scriptAgent.events.ScriptCompileEvent
import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.maven.Dependency

val library = "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0"
val pluginFile by lazy {
    DependencyManager {
        val dep = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:${Config.kotlinVersion}"
        require(Dependency.parse(dep), resolveChild = false)
        load()
        getFiles().single()
    }
}

@OptIn(SAExperimentalApi::class)
listenTo<ScriptCompileEvent> {
    if (!script.scriptInfo.dependsOn(thisScript.scriptInfo)) return@listenTo
    runCatching {
        javaClass.methods.firstOrNull { m ->
            m.name == "registerImportData" && m.parameterTypes.size == 1
        }?.invoke(this, ImportData(ImportData.Type.MavenDepends, library))
    }
    runCatching {
        javaClass.methods.firstOrNull { m ->
            m.name == "addCompileOptions" && m.parameterTypes.size == 1
        }?.invoke(this, "-Xplugin=${pluginFile.absolutePath}")
    }
}
