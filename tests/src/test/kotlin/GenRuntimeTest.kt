package io.eve.ktannot.test

import io.eve.ktannot.gen.*
import io.eve.ktannot.testcontent.Coord
import io.eve.ktannot.testcontent.PrintStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenRuntimeTest {

    @Test
    fun coordBitPacking() {
        // x(24 bits) + health(8 bits) + alive(1 bit)
        val packed = Coord.get(42, 7, true)
        assertEquals(42, Coord.x(packed))
        assertEquals(7, Coord.health(packed).toInt())
        assertTrue(Coord.alive(packed))
        // setter 往返
        val p2 = Coord.x(packed, 100)
        assertEquals(100, Coord.x(p2))
        assertEquals(7, Coord.health(p2).toInt())
    }

    @Test
    fun packetWriteRead() {
        val p = PingCallPacket()
        p.id = 99
        val w = Writes()
        p.write(w)
        // Ping targets=both: 原版 write 只在 server 端写 player,客户端不读;
        // 这里直接模拟"客户端收到的数据流"(仅 id,player 已被服务端过滤),验证 handled 反序列化字段。
        val p2 = PingCallPacket()
        val r = Reads()
        r.feed(listOf(99))
        p2.read(r, 0)
        // handled 使用内嵌 READ(独立实例);把输入喂给它
        (p2 as Packet).setReadInput(listOf(99))
        p2.handled()
        assertEquals(99, p2.id)
    }

    @Test
    fun logicStatementRegistered() {
        var found = false
        for (prov in LogicIO.allStatements) {
            if (prov.get() is PrintStatement) found = true
        }
        assertTrue(found)
        // 注册名为 "print"
        val st = LogicIO.read(arrayOf("print", "hello"), 2)
        assertTrue(st is PrintStatement)
    }
}
