package koshei.authoring

import koshei.registry.ManifestLoader
import koshei.registry.Registry
import org.slf4j.LoggerFactory
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
 * `koshei.publish.disabled=true` (env `KOSHEI_PUBLISH_DISABLED=true`) closes this surface with 403 before the
 * contract is parsed or the jar is staged (for well-formed requests; a request missing a part still fails with
 * 400 during argument binding) — deployments that must not accept new plugin code set it. Note that Spring
 * still reads the full multipart body (up to the configured multipart size limit) into `contract`/`jar` before
 * this method runs, so the switch prevents staging and registration of the plugin but not the upload itself.
 */
@RestController
@RequestMapping("/api")
class PublishController(
    private val registry: Registry,
    @Value("\${koshei.publish.disabled:false}") private val disabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info("[publish] POST /api/publish is {}", if (disabled) "DISABLED (koshei.publish.disabled)" else "open")
    }

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
