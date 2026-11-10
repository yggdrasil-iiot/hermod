package yggdrasil.hermod.blocks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

object Json {
    val mapper = jacksonObjectMapper()
    fun write(value: Any?): String = mapper.writeValueAsString(value)
    inline fun <reified T> read(s: String): T = mapper.readValue(s)
}
