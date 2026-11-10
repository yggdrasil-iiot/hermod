package yggdrasil.hermod.authoring

import yggdrasil.hermod.registry.CanvasReadiness
import yggdrasil.hermod.registry.ContractValidator
import yggdrasil.hermod.registry.ManifestLoader
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * POST /api/contracts/validate — given an authored contract (ManifestLoader-JSON shape), return the
 * runtime-safety verdict (ContractValidator) AND the canvas-readiness diagnostics (CanvasReadiness C1–C5)
 * plus the derived risk badge. The two checks are distinct (spec §5.4): `valid` gates publish, `complete`
 * gates palette admission.
 */
@RestController
@RequestMapping("/api")
class ValidateController {
    @PostMapping("/contracts/validate")
    fun validate(@RequestBody body: String): ValidationResponse {
        val contract = ManifestLoader.fromJson(body)
        val v = ContractValidator.validate(contract)
        val readiness = CanvasReadiness.check(contract)
        return ValidationResponse(
            valid = v.ok,
            errors = v.errors,
            readiness = readiness.map { mapOf("code" to it.code, "message" to it.message) },
            complete = readiness.isEmpty(),
            risk = CanvasReadiness.risk(contract),
        )
    }
}
