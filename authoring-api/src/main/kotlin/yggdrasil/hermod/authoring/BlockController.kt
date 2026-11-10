package yggdrasil.hermod.authoring

import yggdrasil.hermod.registry.BlockIndex
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * POST /api/blocks/{id}/{version}/deprecate — soft-delete (palette-hide only; resolution stays intact for
 * pin/replay safety, spec §5.3). Returns 204 when a row was flipped, 404 when no such (id,version) exists
 * (carry-forward from Chunk 2 review: surface unknown id/version rather than a silent success).
 */
@RestController
@RequestMapping("/api")
class BlockController(private val index: BlockIndex) {
    @PostMapping("/blocks/{id}/{version}/deprecate")
    fun deprecate(@PathVariable id: String, @PathVariable version: String): ResponseEntity<Void> {
        val affected = index.deprecate(id, version)
        return if (affected > 0) ResponseEntity.noContent().build()
        else ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }
}
