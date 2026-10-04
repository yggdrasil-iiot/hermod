package koshei.conductor

import com.netflix.conductor.client.worker.Worker
import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskResult
import koshei.dispatch.DispatchAssembly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockPollersTest {

    private class NamedWorker(private val type: String) : Worker {
        override fun getTaskDefName(): String = type
        override fun execute(task: Task): TaskResult = TaskResult(task)
    }

    private val starts = mutableListOf<List<String>>()
    private val pollers = BlockPollers(
        workerFor = { NamedWorker(it) },
        start = { ws -> starts += ws.map { it.taskDefName } },
    )

    @Test
    fun `boot starts one poller per type, a refresh starts only the newly published ones`() {
        assertEquals(listOf("db.read", "merge"), pollers.sync(listOf("db.read", "merge")))
        assertEquals(listOf("io.example.greet"), pollers.sync(listOf("db.read", "io.example.greet", "merge")))
        assertEquals(listOf(listOf("db.read", "merge"), listOf("io.example.greet")), starts)
        assertEquals(setOf("db.read", "merge", "io.example.greet"), pollers.polled())
    }

    @Test
    fun `an unchanged registry starts nothing`() {
        pollers.sync(listOf("db.read"))
        assertEquals(emptyList(), pollers.sync(listOf("db.read")))
        assertEquals(1, starts.size)
    }

    @Test
    fun `a failed start is retried on the next refresh`() {
        var fail = true
        val flaky = BlockPollers(workerFor = { NamedWorker(it) }, start = { if (fail) error("conductor down") })
        assertFailsWith<IllegalStateException> { flaky.sync(listOf("io.example.greet")) }
        assertEquals(emptySet(), flaky.polled())
        fail = false
        assertEquals(listOf("io.example.greet"), flaky.sync(listOf("io.example.greet")))
    }

    @Test
    fun `the compensate task type never gets a forward poller`() {
        assertEquals(listOf("db.read"), pollers.sync(listOf(COMPENSATE_TASK, "db.read")))
    }

    @Test
    fun `forward task types cover every builtin, distinct and sorted`() {
        val types = forwardTaskTypes(DispatchAssembly.builtinContracts.values.toList())
        assertEquals(types.sorted().distinct(), types)
        assertTrue(types.containsAll(listOf("db.read", "transform.map", "db.upsert", "notify.email", "actuate", "merge")))
        assertTrue(COMPENSATE_TASK !in types)
    }
}
