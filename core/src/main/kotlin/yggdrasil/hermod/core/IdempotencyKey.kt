package yggdrasil.hermod.core

/** Idempotency key-expression evaluator. v0.2: shared by runtime dedup (and future registry lint). */
object IdempotencyKey {
    /** Supports the "row:<field>" form. */
    fun derive(keyExpression: String, record: Map<String, String?>): String? {
        if (!keyExpression.startsWith("row:")) return null
        return record[keyExpression.removePrefix("row:")]
    }
}
