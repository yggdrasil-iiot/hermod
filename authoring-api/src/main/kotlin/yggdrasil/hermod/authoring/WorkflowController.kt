package yggdrasil.hermod.authoring

import yggdrasil.hermod.compiler.CompileException
import yggdrasil.hermod.compiler.WorkflowCompiler
import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.WorkflowStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/workflows")
class WorkflowController(private val store: WorkflowStore, private val registry: Registry) {

    @PostMapping("/validate")
    fun validate(@RequestBody def: WorkflowDef): ValidateResult = compileOrDiag(def)

    @PostMapping
    fun save(@RequestBody def: WorkflowDef, @RequestParam version: String): ResponseEntity<Any> {
        val v = compileOrDiag(def)
        if (!v.valid) return ResponseEntity.badRequest().body(v)
        val r = store.save(def, version)
        return if (r.ok) ResponseEntity.ok(SaveResponse(def.name, version))
               else ResponseEntity.badRequest().body(ValidateResult(false, listOf(r.error ?: "save failed"), 0))
    }

    @GetMapping fun list() = store.list()

    @GetMapping("/{name}/{version}")
    fun get(@PathVariable name: String, @PathVariable version: String): ResponseEntity<WorkflowDef> =
        store.get(name, version)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    private fun compileOrDiag(def: WorkflowDef): ValidateResult =
        try { val ir = WorkflowCompiler.compile(def, registry); ValidateResult(true, emptyList(), ir.nodes.size) }
        catch (e: CompileException) {
            val diags = e.diagnostics.ifEmpty { listOf(e.message ?: "compile failed") }
            ValidateResult(false, diags, 0)
        }
}
