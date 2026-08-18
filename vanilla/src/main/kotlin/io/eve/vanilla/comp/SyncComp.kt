package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.util.io.*
import java.nio.FloatBuffer

@Component
abstract class SyncComp : Entityc {
    @Transient var lastUpdated = 0L
    @Transient var updateSpacing = 0L

    fun snapSync() {}
    fun snapInterpolation() {}
    fun readSync(read: Reads) {}
    fun writeSync(write: Writes) {}
    fun readSyncManual(buffer: FloatBuffer) {}
    fun writeSyncManual(buffer: FloatBuffer) {}
    fun afterSync() {}
    fun interpolate() {}

    fun isSyncHidden(team: mindustry.game.Team): Boolean = false

    fun handleSyncHidden() {}

    override fun update() {
        if ((mindustry.Vars.net.client() && !isLocal()) || isRemote()) {
            interpolate()
        }
    }

    override fun remove() {
        if (mindustry.Vars.net.client()) {
            mindustry.Vars.netClient.addRemovedEntity(id)
        }
    }
}