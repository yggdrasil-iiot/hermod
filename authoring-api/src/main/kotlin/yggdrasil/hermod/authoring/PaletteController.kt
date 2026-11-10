package yggdrasil.hermod.authoring

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class PaletteController(private val palette: PaletteService) {
    @GetMapping("/palette") fun palette(): List<PaletteCard> = palette.palette()
    @GetMapping("/blocks") fun blocks(): List<BlockRow> = palette.allBlocks()
}
