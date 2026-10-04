# 계획 A — publish 봉쇄 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업로드된 플러그인 jar 가 publish 검사 단계에서 코드를 실행하지 못하게 하고, publish 표면을 끌 수 있게 하고, 제어면을 루프백에만 열게 한다.

**Architecture:** 설계 문서 `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` §14 첫 행과 §16 계획 A 를 구현한다.
지금 publish 검사(`handlerLoadCheck`)는 `PluginLoader.load` 를 부르는데, 이 함수는 `Class.forName(fqcn, true, …)` 로 static 초기화를
돌리고 `newInstance()` 로 생성자까지 돌린다. 설계 문서는 `initialize=false` 만 적었지만 그것만으로는 생성자가 여전히 돈다.
그래서 publish 검사 전용 `PluginLoader.probe` 를 새로 둔다 — 초기화 없이 클래스를 읽고, `Block` 타입과 인자 없는 생성자가 있는지만
보고, 인스턴스를 만들지 않고, 클래스로더를 닫는다. 실행 경로(`HandlerRegistry` → `load`)는 그대로 둔다(publish 된 블록은 워커에서
실행되는 것이 설계된 동작이다). 여기에 publish 끄기 스위치(기본 켜짐, 기존 게이트·E2E·문서 호환)와 루프백 바인딩(기본
`127.0.0.1`)을 더한다.

**Tech Stack:** Kotlin 2.2 / JDK 21, Spring Boot 3.3 (WebMvcTest, relaxed binding), JUnit 5, `javax.tools` (시험 중 Java 픽스처 컴파일)

**범위 밖:** CLI `publish` (DB 자격 증명을 가진 로컬 운영자 도구, `app/.../Cli.kt:125`) — 같은 `DispatchAssembly` 를 거치므로 Task 2 로
검사 단계의 코드 실행은 함께 막히지만, CLI 자체를 끄지는 않는다. 인증 도입도 범위 밖이다(설계 §14, PoC 한계로 명시).

---

## 파일 구조

| 파일 | 할 일 | 책임 |
|---|---|---|
| `dispatch/src/main/kotlin/koshei/dispatch/PluginLoader.kt` | 수정 | `probe()` 추가: 코드를 실행하지 않는 publish 검사 |
| `dispatch/src/main/kotlin/koshei/dispatch/DispatchAssembly.kt:48` | 수정 | `handlerLoadCheck` 를 `load` 대신 `probe` 로 |
| `runtime/src/test/kotlin/koshei/runtime/FixtureBlockJar.kt` | 수정 | 픽스처 소스에 static 초기화·생성자 본문을 넣을 수 있게 |
| `runtime/src/test/kotlin/koshei/runtime/PluginLoaderTest.kt` | 수정 | `probe` 시험 셋 |
| `runtime/src/test/kotlin/koshei/runtime/PublishProbeWiringTest.kt` | 생성 | `DispatchAssembly` 가 publish 때 코드를 돌리지 않음을 증명 |
| `authoring-api/src/main/kotlin/koshei/authoring/PublishController.kt` | 수정 | `koshei.publish.disabled` 면 403 |
| `authoring-api/src/test/kotlin/koshei/authoring/PublishDisabledTest.kt` | 생성 | 끈 상태 시험 |
| `authoring-api/src/main/resources/application.yml` | 수정 | `server.address` 기본 `127.0.0.1` |
| `authoring-api/src/test/kotlin/koshei/authoring/ServerBindingConfigTest.kt` | 생성 | 기본 바인딩 주소 시험 |
| `docs/usage.md` §4 | 수정 | 환경 키 두 줄 + 보안 메모 |

`PluginLoaderTest` 와 픽스처는 `:runtime` 시험에 있다(`:dispatch` 에는 `:sdk` 를 컴파일 경로로 쓰는 픽스처가 없고, 기존 시험도 여기
있다). 새 시험도 그 옆에 둔다.

---

## Chunk 1: 코드를 실행하지 않는 publish 검사

### Task 1: 픽스처에 부작용을 넣을 수 있게 하기

**Files:**
- Modify: `runtime/src/test/kotlin/koshei/runtime/FixtureBlockJar.kt`

- [ ] **Step 1: 픽스처 소스를 함수로 바꾸고 static 초기화·생성자 본문 인자를 받게 한다**

`private val javaSource = """ ... """.trimIndent()` 를 아래 함수로 바꾼다.

```kotlin
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
```

`build` 의 시그니처와 소스 쓰기 줄을 바꾼다.

```kotlin
    fun build(
        withManifest: Boolean = true,
        staticInit: String = "",
        ctorBody: String = "",
        classModifiers: String = "public",
        ctorModifiers: String = "public",
    ): File {
```

```kotlin
        val srcFile = File(srcDir, "FixtureBlock.java").apply { writeText(javaSource(staticInit, ctorBody, classModifiers, ctorModifiers)) }
```

기존 호출(`build(withManifest = true)`, `build(withManifest = false)`)은 기본값으로 그대로 동작한다.

- [ ] **Step 2: 기존 시험이 그대로 통과하는지 본다**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.PluginLoaderTest'`
Expected: `BUILD SUCCESSFUL` (2 tests)

- [ ] **Step 3: Commit**

```bash
git add runtime/src/test/kotlin/koshei/runtime/FixtureBlockJar.kt
git commit -m "test: let the plugin fixture plant side effects and vary class/ctor modifiers"
```

### Task 2: `PluginLoader.probe`

**Files:**
- Modify: `dispatch/src/main/kotlin/koshei/dispatch/PluginLoader.kt`
- Test: `runtime/src/test/kotlin/koshei/runtime/PluginLoaderTest.kt`

- [ ] **Step 1: 실패하는 시험 셋을 쓴다**

`PluginLoaderTest` 클래스 안, 기존 시험 뒤에 추가한다. import 에 `import kotlin.test.assertNull` 과
`import kotlin.test.assertFailsWith` 를 더한다.

```kotlin
    @Test fun `probe accepts a Block without running its static initializer or constructor`() {
        val clinitKey = "koshei.fixture.clinit.${System.nanoTime()}"
        val ctorKey = "koshei.fixture.ctor.${System.nanoTime()}"
        val jar = FixtureBlockJar.build(
            withManifest = false,
            staticInit = "System.setProperty(\"$clinitKey\", \"ran\");",
            ctorBody = "System.setProperty(\"$ctorKey\", \"ran\");",
        )

        PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN)   // must not throw

        assertNull(System.getProperty(clinitKey), "probe must not run the plugin's static initializer")
        assertNull(System.getProperty(ctorKey), "probe must not construct the plugin")
    }

    @Test fun `probe rejects a class that is not a Block`() {
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe("ignored.jar", "java.lang.String") }
    }

    @Test fun `probe rejects an abstract Block the worker could not instantiate`() {
        val jar = FixtureBlockJar.build(withManifest = false, classModifiers = "public abstract")
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe rejects a Block whose no-arg constructor is not public`() {
        val jar = FixtureBlockJar.build(withManifest = false, ctorModifiers = "private")
        assertFailsWith<IllegalArgumentException> { PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN) }
    }

    @Test fun `probe releases the jar so it can be deleted`() {
        val jar = FixtureBlockJar.build(withManifest = false)
        PluginLoader().probe(jar.absolutePath, FixtureBlockJar.FQCN)
        kotlin.test.assertTrue(jar.delete(), "probe must close its classloader (Windows keeps an open jar locked)")
    }
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.PluginLoaderTest'`
Expected: 컴파일 실패 — `Unresolved reference 'probe'`

- [ ] **Step 3: `probe` 를 구현한다**

`PluginLoader` 클래스 안, `load` 위에 추가한다. import 에 `import java.lang.reflect.Modifier` 를 더한다. 클래스 KDoc 뒤에 한 줄을 덧붙인다: `[probe] is the publish-time check; [load] is the execution path.`

```kotlin
    /**
     * Publish-time check that runs NO plugin code: resolves [fqcn] from the jar WITHOUT initialization
     * (`initialize=false`, so no static initializer), then checks by reflection only (never instantiated) that
     * [load] could instantiate it — a public, concrete [Block] with a public no-arg constructor — and closes the
     * loader so the candidate jar is released. A jar published through an unauthenticated surface must not
     * execute during the check (design §14). Trade-off: a class whose static initializer or constructor THROWS
     * now passes publish and fails at its first execution instead (a failed activity), because detecting that
     * would mean running the code.
     */
    fun probe(jarPath: String, fqcn: String) {
        URLClassLoader(arrayOf(File(jarPath).toURI().toURL()), parent).use { loader ->
            val cls = Class.forName(fqcn, false, loader)
            require(Block::class.java.isAssignableFrom(cls)) { "$fqcn is not a koshei.sdk.Block" }
            require(Modifier.isPublic(cls.modifiers) && !cls.isInterface && !Modifier.isAbstract(cls.modifiers)) {
                "$fqcn must be a public concrete class"
            }
            val ctor = runCatching { cls.getDeclaredConstructor() }.getOrNull()
            require(ctor != null && Modifier.isPublic(ctor.modifiers)) { "$fqcn needs a public no-arg constructor" }
        }
    }
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.PluginLoaderTest'`
Expected: `BUILD SUCCESSFUL` (7 tests)

- [ ] **Step 5: Commit**

```bash
git add dispatch/src/main/kotlin/koshei/dispatch/PluginLoader.kt runtime/src/test/kotlin/koshei/runtime/PluginLoaderTest.kt
git commit -m "feat(dispatch): add PluginLoader.probe, a publish check that runs no plugin code"
```

### Task 3: publish 검사를 `probe` 로 배선

**Files:**
- Modify: `dispatch/src/main/kotlin/koshei/dispatch/DispatchAssembly.kt:35-48`
- Create: `runtime/src/test/kotlin/koshei/runtime/PublishProbeWiringTest.kt`

- [ ] **Step 1: 실패하는 시험을 쓴다**

이 시험은 DB 가 없다. `Registry.publish` 는 `handlerLoadCheck` 를 부른 **뒤에** `index.find` 에서 DB 에 닿으므로, 인덱스가 던지는
예외를 받아 넘기고 나서 부작용이 없었는지만 본다.

```kotlin
package koshei.runtime

import koshei.dispatch.DispatchAssembly
import koshei.registry.BlockIndex
import koshei.registry.BlockStore
import koshei.registry.ManifestLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The registry every engine builds (DispatchAssembly) must check a publish candidate WITHOUT running its code.
 * No DB: Registry.publish consults handlerLoadCheck before the index, and the index here throws — the test
 * only cares that the plugin's static initializer and constructor never ran on the way.
 */
class PublishProbeWiringTest {
    @Test fun `publish-time handler check runs no plugin code`() {
        val clinitKey = "koshei.wiring.clinit.${System.nanoTime()}"
        val ctorKey = "koshei.wiring.ctor.${System.nanoTime()}"
        val jar = FixtureBlockJar.build(
            withManifest = false,
            staticInit = "System.setProperty(\"$clinitKey\", \"ran\");",
            ctorBody = "System.setProperty(\"$ctorKey\", \"ran\");",
        )
        val storeRoot = File.createTempFile("koshei-probe-store", "").let { it.delete(); it.mkdirs(); it }
        val registry = DispatchAssembly.buildRegistry(
            BlockIndex { error("no database in this test") },
            BlockStore(storeRoot),
        )
        val contract = ManifestLoader.fromYaml(FixtureBlockJar.manifestYaml)

        runCatching { registry.publish(jar, contract) }   // fails at the index — expected

        assertNull(System.getProperty(clinitKey), "publish must not run the plugin's static initializer")
        assertNull(System.getProperty(ctorKey), "publish must not construct the plugin")
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.PublishProbeWiringTest'`
Expected: FAIL — `publish must not run the plugin's static initializer` (지금 배선은 `load` 라 static 초기화와 생성자가 돈다)

- [ ] **Step 3: 배선을 바꾼다**

`DispatchAssembly.buildRegistry` 의 KDoc 과 한 줄을 바꾼다.

```kotlin
    /**
     * Construct a [Registry] over the given index/store with the static builtins (plus optional [extraBuiltins]
     * for test fixtures) and a PluginLoader-based handlerLoadCheck. The check is [PluginLoader.probe]: it proves
     * the forwardHandler is a public concrete [koshei.sdk.Block] with a public no-arg ctor WITHOUT running any plugin code — publish is
     * reachable from an unauthenticated API, so the candidate jar must not execute during the check (design §14).
     */
```

```kotlin
            handlerLoadCheck = { jar, fqcn -> loader.probe(jar.absolutePath, fqcn) },
```

같은 뜻의 낡은 주석 둘도 고친다.
- `runtime/src/main/kotlin/koshei/runtime/RuntimeAssembly.kt:39-41` KDoc 의 "a publish probe: the forwardHandler must classload as a
  koshei.sdk.Block from the candidate jar" 를 "a publish probe (PluginLoader.probe): the forwardHandler must be a public concrete
  koshei.sdk.Block with a public no-arg ctor, checked without running plugin code" 로.
- `dispatch/src/main/kotlin/koshei/dispatch/HandlerRegistry.kt:15-19` 주석의 "publish-time handlerLoadCheck already gates most bad
  plugins" 뒤에 "(it cannot catch a throwing static initializer or constructor — those surface here, at first execution)" 를 덧붙인다.

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.PublishProbeWiringTest' --tests 'koshei.runtime.PluginLoaderTest'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 실행 경로가 그대로인지 본다 (Docker 필요 — Testcontainers Postgres)**

Run: `./gradlew :runtime:test --tests 'koshei.runtime.HandlerRegistryTest'`
Expected: `BUILD SUCCESSFUL` — 실제 publish(이제 probe) 후 `HandlerRegistry` 가 `load` 로 플러그인을 실행하고 캐시한다.

- [ ] **Step 6: Commit**

```bash
git add dispatch/src/main/kotlin/koshei/dispatch/DispatchAssembly.kt dispatch/src/main/kotlin/koshei/dispatch/HandlerRegistry.kt runtime/src/main/kotlin/koshei/runtime/RuntimeAssembly.kt runtime/src/test/kotlin/koshei/runtime/PublishProbeWiringTest.kt
git commit -m "fix(dispatch): check publish candidates with probe so upload runs no plugin code"
```

---

## Chunk 2: publish 끄기와 루프백 바인딩

### Task 4: publish 끄기 스위치

**Files:**
- Modify: `authoring-api/src/main/kotlin/koshei/authoring/PublishController.kt`
- Create: `authoring-api/src/test/kotlin/koshei/authoring/PublishDisabledTest.kt`

스위치 이름은 Spring 속성 `koshei.publish.disabled` 다. 환경 변수 `KOSHEI_PUBLISH_DISABLED=true` 가 relaxed binding 으로 이
속성이 된다(기존 `KOSHEI_*_DISABLED` 이름 관례와 맞고, 시험에서는 `@TestPropertySource` 로 켤 수 있다). 기본은 꺼지지 않음 —
기존 게이트(`run-authoring-gate.sh`), E2E, `docs/usage.md` 의 흐름을 깨지 않기 위해서다. 에피소드 배포는 이 값을 켠다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package koshei.authoring

import koshei.registry.BlockIndex
import koshei.registry.Registry
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

@WebMvcTest(PublishController::class)
@TestPropertySource(properties = ["koshei.publish.disabled=true"])
class PublishDisabledTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var registry: Registry
    @MockBean lateinit var index: BlockIndex

    @Test fun `disabled publish answers 403 and never touches the registry`() {
        val contract = MockMultipartFile("contract", "contract", MediaType.APPLICATION_JSON_VALUE, "{}".toByteArray())
        val jar = MockMultipartFile("jar", "x.jar", "application/java-archive", byteArrayOf(1, 2, 3))
        mvc.perform(multipart("/api/publish").file(contract).file(jar))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.errors[0]").value("publish is disabled on this deployment (KOSHEI_PUBLISH_DISABLED)"))
        verifyNoInteractions(registry)
    }
}
```

`contract` 칸이 `"{}"` 인 것은 의도다 — 끈 상태에서는 본문을 해석하기 전에 거절해야 한다.

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :authoring-api:test --tests 'koshei.authoring.PublishDisabledTest'`
Expected: FAIL — 상태 403 을 기대했는데 400(빈 계약 해석 실패) 또는 500 이 온다

- [ ] **Step 3: 컨트롤러에 스위치를 넣는다**

`PublishController.kt` 전체를 아래로 바꾼다(기존 동작은 스위치가 꺼져 있을 때 그대로다).

```kotlin
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
```

`PublishResponse(ok, errors)` 는 `Dto.kt:20` 의 `data class PublishResponse(val ok: Boolean, val errors: List<String> = emptyList())` 다.

- [ ] **Step 4: 새 시험과 기존 시험이 모두 통과하는지 본다**

Run: `./gradlew :authoring-api:test --tests 'koshei.authoring.PublishDisabledTest' --tests 'koshei.authoring.PublishControllerTest'`
Expected: `BUILD SUCCESSFUL` — 기존 `PublishControllerTest` 는 속성이 없어 기본 `false` 로 전과 같이 200/400.

- [ ] **Step 5: Commit**

```bash
git add authoring-api/src/main/kotlin/koshei/authoring/PublishController.kt authoring-api/src/test/kotlin/koshei/authoring/PublishDisabledTest.kt
git commit -m "feat(authoring-api): KOSHEI_PUBLISH_DISABLED closes /api/publish with 403"
```

### Task 5: 제어면을 루프백에만 열기

**Files:**
- Modify: `authoring-api/src/main/resources/application.yml`
- Create: `authoring-api/src/test/kotlin/koshei/authoring/ServerBindingConfigTest.kt`

기본 `127.0.0.1`, 환경 변수 `KOSHEI_BIND_ADDRESS` 로 바꿀 수 있다. 게이트 스크립트(curl)와 Vite 프록시는 `localhost:18090` 을
부른다. Windows 에서 `localhost` 는 `::1` 이 먼저 풀리지만, curl 과 Node 20+(`autoSelectFamily`, 여기는 Node 22)는 IPv4 로 넘어가므로
동작한다. 이 가정은 Task 7 에서 게이트 두 개로 실물 확인한다. 원격에서 부르던 배포가 있다면 이 값을 명시적으로 풀어야 한다 — 그것이 의도다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

전체 스프링 문맥(DB·Temporal 빈)을 띄우지 않고 설정 파일만 읽는다.

```kotlin
package koshei.authoring

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerBindingConfigTest {
    @Test fun `control plane binds to loopback unless KOSHEI_BIND_ADDRESS says otherwise`() {
        val props = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
        assertEquals("\${KOSHEI_BIND_ADDRESS:127.0.0.1}", props.getProperty("server.address"))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :authoring-api:test --tests 'koshei.authoring.ServerBindingConfigTest'`
Expected: FAIL — `expected: <${KOSHEI_BIND_ADDRESS:127.0.0.1}> but was: <null>`

- [ ] **Step 3: 설정을 바꾼다**

`application.yml` 의 `server:` 블록을 아래로 바꾼다.

```yaml
server:
  port: 18090
  # Loopback by default: the control plane has no authentication (design §14, PoC limit), so it must not be
  # reachable from the network unless an operator opts in explicitly with KOSHEI_BIND_ADDRESS (e.g. 0.0.0.0).
  address: ${KOSHEI_BIND_ADDRESS:127.0.0.1}
```

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: `./gradlew :authoring-api:test --tests 'koshei.authoring.ServerBindingConfigTest'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add authoring-api/src/main/resources/application.yml authoring-api/src/test/kotlin/koshei/authoring/ServerBindingConfigTest.kt
git commit -m "feat(authoring-api): bind the control plane to loopback by default"
```

### Task 6: 문서

**Files:**
- Modify: `docs/usage.md` §4 "Environment keys" 표 (현재 204-213행 부근)

- [ ] **Step 1: 환경 키 표에 두 줄을 더한다**

`| KOSHEI_PLUGIN_DIR | ... |` 줄 바로 뒤에 넣는다.

```markdown
| `KOSHEI_PUBLISH_DISABLED` | `true` (or `1`) closes `POST /api/publish` with 403 (no new plugin code accepted). Default: publish open |
| `KOSHEI_BIND_ADDRESS` | Address the control plane listens on. Default `127.0.0.1` (loopback only) |
```

- [ ] **Step 2: 표 바로 아래에 보안 메모를 더한다**

```markdown
> **Security note.** The control plane has no authentication (proof-of-concept). It therefore listens on loopback
> by default, and publish-time checks never run uploaded plugin code: the handler class is resolved without
> initialization and never instantiated until a block is executed. A published block *does* run, with that
> process's privileges, wherever it is executed (the worker) when a workflow uses it — close the surface with
> `KOSHEI_PUBLISH_DISABLED=true` on deployments that must not accept new plugin code.
```

- [ ] **Step 3: Commit**

```bash
git add docs/usage.md
git commit -m "docs: document KOSHEI_PUBLISH_DISABLED and KOSHEI_BIND_ADDRESS"
```

---

## Chunk 3: 퇴행 확인

### Task 7: 모듈 시험과 게이트

Docker 의 Postgres(15432)와 Temporal(7233)이 떠 있어야 한다(`docker compose up -d postgres temporal`, 이어 `bash scripts/init-db.sh`).

- [ ] **Step 1: 바뀐 모듈의 시험 전체**

Run: `./gradlew :dispatch:test :runtime:test :authoring-api:test`
Expected: `BUILD SUCCESSFUL`, 실패 0. 실패가 있으면 이 계획이 바꾼 것 때문인지(`git stash` 로 전후 비교) 먼저 가른다.

- [ ] **Step 2: authoring 게이트 — publish 가 기본값에서 그대로 열려 있는지**

Run: `bash scripts/run-authoring-gate.sh`
Expected: 마지막 줄 `[GATE] PASS`, exit 0. (API 로 publish → palette → 새 판 → deprecate 를 한 프로세스에서.)

- [ ] **Step 3: add-block 게이트 — 워커 실행 경로가 그대로인지**

Run: `bash scripts/run-add-block-gate.sh`
Expected: `[GATE] PASS`. 워커 로그에 `[PluginLoader] loaded io.example.GreetBlock via ...URLClassLoader` 가 여전히 찍힌다
(이 표지는 실행 경로 `load` 의 것이고, publish 검사 `probe` 는 찍지 않는다).

- [ ] **Step 4: 끈 상태와 바인딩을 실물로 확인**

```bash
KOSHEI_PUBLISH_DISABLED=true ./gradlew -q --no-daemon :authoring-api:run > /tmp/api-a.log 2>&1 &
```

`--no-daemon` 은 필수다 — 데몬이 뜬 상태면 포크된 JVM 이 이 셸의 `KOSHEI_*` 환경을 물려받지 못해 스위치가 먹지 않는다
(`run-authoring-gate.sh:88-89` 와 같은 이유). API 가 뜬 뒤(로그에 `Started AuthoringApplication`):

```bash
curl -s -o /dev/null -w "%{http_code}\n" -F contract=@scripts/fixtures/authoring/authored.json -F jar=@scripts/fixtures/authoring/canvasdemo-plugin.jar http://localhost:18090/api/publish
```
Expected: `403`

```bash
netstat -ano | grep ":18090" | grep LISTENING
```
Expected: 로컬 주소가 `127.0.0.1:18090` 뿐이고 `0.0.0.0:18090` 이나 `[::]:18090` 이 없다.

그 뒤 API 를 내린다(`jps -l | grep AuthoringApplication` 으로 JVM PID 를 찾아 `taskkill //F //PID <pid>`).

- [ ] **Step 5: 결과를 기록하고 Commit (코드 변경이 없으면 생략)**

게이트 결과를 PR 본문 재료로 남긴다: 두 게이트 PASS, 403 확인, 바인딩 확인.
