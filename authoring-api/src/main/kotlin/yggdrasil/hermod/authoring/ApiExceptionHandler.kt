package yggdrasil.hermod.authoring

import com.fasterxml.jackson.core.JsonProcessingException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps client-fault parse failures to HTTP 400. The validate/publish endpoints call
 * `ManifestLoader.fromJson(...)` synchronously in the controller method on the client body; malformed
 * JSON / wrong shape throws [JsonProcessingException] and a bad enum throws [IllegalArgumentException]
 * (from `ManifestLoader.parseEnum`). Without this advice both surface as 500; the UI (`api.ts`) treats a
 * 400-with-body as a value-level rejection, so these belong as 400s. Generic `Exception` is intentionally
 * NOT caught — genuine server faults must remain 500.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(JsonProcessingException::class, IllegalArgumentException::class)
    fun handleBadRequest(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "invalid request")))
}
