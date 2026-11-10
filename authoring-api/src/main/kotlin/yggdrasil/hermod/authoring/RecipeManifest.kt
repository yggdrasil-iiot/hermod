package yggdrasil.hermod.authoring

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecipeManifest(
    val kind: String = "", val ref: String = "", val version: String = "",
    val defRef: String = "", val contentSha256: String = "", val sourcePath: String = "", val publishedAt: Long = 0,
)
