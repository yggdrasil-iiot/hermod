package koshei.authoring

import koshei.registry.ManifestLoader
import koshei.registry.Registry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.File

/**
 * POST /api/publish — multipart (`contract` JSON part + `jar` file part). The authoring UI owns the
 * contract; the jar supplies only the handler class. Delegates to [Registry.publish] (the single gating
 * path, spec §5.2): 200 {ok:true} on success, 400 {ok:false, errors} on a gating failure.
 *
 * `koshei.publish.disabled=true` (env `KOSHEI_PUBLISH_DISABLED=true`) closes this surface with 403 BEFORE the
 * body is parsed — deployments that must not accept new plugin code (the episode outer loop, design §14) set it.
 */
@RestController
@RequestMapping("/api")
class PublishController(
    private val registry: Registry,
    @Value("\${koshei.publish.disabled:false}") private val disabled: Boolean,
) {
    @PostMapping("/publish")
    fun publish(
        @RequestPart("contract") contract: MultipartFile,
        @RequestPart("jar") jar: MultipartFile,
    ): ResponseEntity<PublishResponse> {
        if (disabled) return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(PublishResponse(false, listOf("publish is disabled on this deployment (KOSHEI_PUBLISH_DISABLED)")))
        val parsed = ManifestLoader.fromJson(String(contract.bytes, Charsets.UTF_8))
        val tmp = File.createTempFile("publish-", ".jar")
        return try {
            jar.transferTo(tmp)
            val r = registry.publish(tmp, parsed)
            if (r.ok) ResponseEntity.ok(PublishResponse(true))
            else ResponseEntity.status(HttpStatus.BAD_REQUEST).body(PublishResponse(false, r.errors))
        } finally {
            tmp.delete()
        }
    }
}
