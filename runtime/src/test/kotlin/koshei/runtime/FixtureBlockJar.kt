package koshei.runtime

import koshei.sdk.Block
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Test helper: compile a tiny Java [Block] impl at test time (no Kotlin compiler needed in-test)
 * against the live :sdk classpath, jar the .class (+ optional manifest), and return the jar. The
 * fixture class is NEVER on the test/parent classpath — it exists only inside the jar — so loading
 * it exercises real classloader isolation.
 */
object FixtureBlockJar {
    const val FQCN = "io.fixture.FixtureBlock"
    const val ID = "io.fixture.block"
    const val VERSION = "1.0.0"

    /** Java source for the fixture. [staticInit] and [ctorBody] are spliced in verbatim so a test can plant
     *  an observable side effect (e.g. System.setProperty) and prove whether publish-time probing ran it;
     *  [classModifiers] / [ctorModifiers] let a test build a class the worker could not instantiate. */
    private fun javaSource(staticInit: String, ctorBody: String, classModifiers: String, ctorModifiers: String) = """
        package io.fixture;
        import koshei.sdk.Block;
        import koshei.sdk.BlockInput;
        import koshei.sdk.BlockOutput;
        $classModifiers class FixtureBlock implements Block {
            static { $staticInit }
            $ctorModifiers FixtureBlock() { $ctorBody }
            public String getId() { return "$ID"; }
            public BlockOutput forward(BlockInput input) {
                return new BlockOutput(
                    java.util.Collections.singletonList(
                        java.util.Collections.singletonMap("hello", (String) "world")),
                    java.util.Collections.emptyMap());
            }
        }
    """.trimIndent()

    /** A valid plugin manifest for the fixture (passes ContractValidator: NATURAL + REVERSIBLE/NONE). */
    val manifestYaml = """
        id: $ID
        version: $VERSION
        category: transform
        forward: { handler: "$FQCN" }
        idempotency: { strategy: NATURAL }
        compensation: { reversibility: REVERSIBLE, kind: NONE }
        retry: { maxAttempts: 3, backoff: { initialMs: 100, maxMs: 1000 } }
        sideEffects: [NONE]
    """.trimIndent()

    /** Build a jar with the compiled fixture .class and, when [withManifest], a manifests/block.yaml. */
    fun build(
        withManifest: Boolean = true,
        staticInit: String = "",
        ctorBody: String = "",
        classModifiers: String = "public",
        ctorModifiers: String = "public",
    ): File {
        val work = File.createTempFile("koshei-fixture", "").let { it.delete(); it.mkdirs(); it }
        val srcDir = File(work, "src/io/fixture").apply { mkdirs() }
        val srcFile = File(srcDir, "FixtureBlock.java").apply { writeText(javaSource(staticInit, ctorBody, classModifiers, ctorModifiers)) }
        val classesDir = File(work, "classes").apply { mkdirs() }

        val sdkCp = File(Block::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("no system Java compiler available (need a JDK, not a JRE)")
        val rc = compiler.run(null, null, null, "-classpath", sdkCp, "-d", classesDir.absolutePath, srcFile.absolutePath)
        check(rc == 0) { "javac failed (rc=$rc) compiling fixture Block" }

        val jar = File(work, "fixture-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            val classFile = File(classesDir, "io/fixture/FixtureBlock.class")
            check(classFile.exists()) { "compiled class not found: $classFile" }
            jos.putNextEntry(JarEntry("io/fixture/FixtureBlock.class"))
            jos.write(classFile.readBytes()); jos.closeEntry()
            if (withManifest) {
                jos.putNextEntry(JarEntry("manifests/block.yaml"))
                jos.write(manifestYaml.toByteArray()); jos.closeEntry()
            }
        }
        return jar
    }
}
