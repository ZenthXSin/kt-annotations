package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*

@Component
abstract class RotComp : Entityc {
    @SyncField(false) @SyncLocal var rotation = 0f
}