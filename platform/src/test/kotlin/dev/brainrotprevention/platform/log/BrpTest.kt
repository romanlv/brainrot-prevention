package dev.brainrotprevention.platform.log

import org.junit.Assert.assertEquals
import org.junit.Test

class BrpTest {
    @Test
    fun markPutsIdSecondAndTimesLast() {
        val line = Brp.format("mark", 5, 7, listOf("x" to 1, "id" to "m0"))
        assertEquals("{\"ev\":\"mark\",\"id\":\"m0\",\"x\":1,\"t\":5,\"rt\":7}", line)
    }

    @Test
    fun encodesNestedValuesAndEscapes() {
        val line = Brp.format(
            "decision",
            1,
            2,
            listOf(
                "target" to "a\"b",
                "reasons" to listOf(mapOf("source" to "QUICK_BLOCK", "limit" to null)),
                "ok" to true,
                "until" to null,
            ),
        )
        assertEquals(
            "{\"ev\":\"decision\",\"target\":\"a\\\"b\",\"reasons\":[{\"source\":\"QUICK_BLOCK\",\"limit\":null}]," +
                "\"ok\":true,\"until\":null,\"t\":1,\"rt\":2}",
            line,
        )
    }

    @Test
    fun eventUsesInjectedClocksAndSink() {
        val lines = mutableListOf<String>()
        val oldSink = Brp.sink
        val oldP = Brp.policyNow
        val oldR = Brp.realNow
        try {
            Brp.sink = { lines += it }
            Brp.policyNow = { 100L }
            Brp.realNow = { 50L }
            Brp.event("boot")
        } finally {
            Brp.sink = oldSink
            Brp.policyNow = oldP
            Brp.realNow = oldR
        }
        assertEquals(listOf("{\"ev\":\"boot\",\"t\":100,\"rt\":50}"), lines)
    }
}
