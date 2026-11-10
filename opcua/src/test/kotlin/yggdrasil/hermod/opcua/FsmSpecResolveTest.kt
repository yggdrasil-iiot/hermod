package yggdrasil.hermod.opcua

import java.io.File
import kotlin.test.*

class FsmSpecResolveTest {
    private fun write(dir: File, name: String, unit: String, version: String) {
        File(dir, name).writeText("name: $name\nunit: $unit\nversion: $version\nstateNode: line1.stateCurrent\nstates: [{id: Idle, code: 4}]\ntransitions: []\n")
    }
    @Test fun `resolve matches by in-file unit and version, else null`(@org.junit.jupiter.api.io.TempDir root: File) {
        val fsmDir = File(root, "fsm").apply { mkdirs() }
        write(fsmDir, "a.yaml", "line1", "v1")
        write(fsmDir, "b.yaml", "line1", "v2")
        assertEquals("a.yaml", FsmSpec.resolve(root, "line1", "v1")?.name)
        assertEquals("b.yaml", FsmSpec.resolve(root, "line1", "v2")?.name)
        assertNull(FsmSpec.resolve(root, "line1", "v9"))
        assertNull(FsmSpec.resolve(root, "nope", "v1"))
    }
    @Test fun `resolve throws on ambiguous (two files, same unit+version)`(@org.junit.jupiter.api.io.TempDir root: File) {
        val fsmDir = File(root, "fsm").apply { mkdirs() }
        write(fsmDir, "a.yaml", "line1", "v1"); write(fsmDir, "dup.yaml", "line1", "v1")
        assertFailsWith<IllegalStateException> { FsmSpec.resolve(root, "line1", "v1") }
    }
}
