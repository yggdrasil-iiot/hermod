package yggdrasil.hermod.core

/** Minimal strict 3-part semver (no pre-release/build metadata — YAGNI). */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(s: String): SemVer {
            val parts = s.split(".")
            require(parts.size == 3) { "invalid semver '$s' (expected major.minor.patch)" }
            val nums = parts.map { it.toIntOrNull() ?: throw IllegalArgumentException("invalid semver '$s'") }
            require(nums.all { it >= 0 }) { "invalid semver '$s' (negative component)" }
            return SemVer(nums[0], nums[1], nums[2])
        }
        fun parseOrNull(s: String): SemVer? = try { parse(s) } catch (e: Exception) { null }
    }
}

/** A version requirement on a step. Exact | Latest | Caret (npm-style). */
sealed interface VersionSpec {
    fun matches(v: SemVer): Boolean
    data class Exact(val v: SemVer) : VersionSpec { override fun matches(v: SemVer) = v == this.v }
    data object Latest : VersionSpec { override fun matches(v: SemVer) = true }
    data class Caret(val base: SemVer) : VersionSpec {
        override fun matches(v: SemVer): Boolean {
            if (v < base) return false
            val upper = when {
                base.major >= 1 -> SemVer(base.major + 1, 0, 0)
                base.minor >= 1 -> SemVer(0, base.minor + 1, 0)
                else -> SemVer(0, 0, base.patch + 1)
            }
            return v < upper
        }
    }
    companion object {
        fun parse(s: String): VersionSpec = when {
            s == "latest" -> Latest
            s.startsWith("^") -> Caret(SemVer.parse(s.substring(1)))
            else -> Exact(SemVer.parse(s))
        }
        fun parseOrNull(s: String): VersionSpec? = try { parse(s) } catch (e: Exception) { null }
    }
}
