# 계획 S — 에피소드 바깥 루프를 새 저장소 koshchei 로 분리 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** koshei 안의 에피소드 바깥 루프(`:episode`, `:episode-runtime`, 워커·감시자·CLI 의 에피소드 부분, `/api/episodes`, Episodes 화면)를 새 비공개 저장소 `LivingLikeKrillin/koshchei` 로 옮기고 이름을 koshchei 로 바꾼 뒤, koshei 에서 지운다. 두 저장소는 저마다 역할이 하나가 된다: koshei = OT/IT 통합용 엔진 중립 사가 플랫폼, koshchei = picasso · narrator 위에서 예외 하나를 증상부터 해소까지 쫓는 결정적 상태기계.

**결정(사용자, 2026-10-05):**

| # | 결정 | 이 계획에서 뜻하는 것 |
|---|---|---|
| S1 | 둘은 성격이 다르다 → 저장소를 나눈다. 에피소드만 살린 쪽이 **koshchei** | 새 저장소 `LivingLikeKrillin/koshchei`(비공개, koshei 와 같음) |
| S2 | 이력은 새로 시작하고 출처를 적는다 | 첫 커밋 하나. README 와 설계 문서 머리에 「koshei `6e66dbe` 에서 분리(PR #4·#5), 이력은 koshei 에 있다」 |
| S3 | 패키지 · 환경변수 · DB · 큐 이름을 모두 koshchei 로 | 아래 「이름 대응표」. 단 외부 계약(narrator `narrator-tq` · `diagnose`, picasso 판 4 · ResultExport 판 1)은 그대로 |
| S4 | 제어면 · UI 는 에피소드 부분만 작은 앱으로 | `:api`(작은 Spring Boot), `ui/`(Episodes 화면만 있는 Vite React) |
| S5 | koshchei 가 서면 koshei 에서 에피소드 코드를 지운다 | 덩이 5 |

**원칙 — 옮기기와 이름 바꾸기만 한다.** 동작은 바꾸지 않는다. 예외는 둘이다: (1) koshchei 워커는 `KOSHCHEI_PICASSO=off` 면 할 일이 없으므로 시작을 거절한다(사가 워커가 없다). (2) koshei 의 `AutoCorrectGate` 는 에피소드 모드 검사를 잃는다 — 드리프트 자동 조치(OPC UA 레시피)와 koshchei(picasso 로봇)는 이제 다른 세계에 손을 댄다. 둘 다 이 문서에 적고 각 저장소 문서에 남긴다.

**Architecture:**
- koshchei 모듈: `:core`(옛 `:episode`, 순수 코어), `:runtime`(옛 `:episode-runtime` + `DataConverterSupport`/`RawJson` + `Db`), `:host`(워커 · 감시자 · CLI), `:api`(Spring Boot 제어면), `ui/`(React, Gradle 밖).
- 의존: `core ← runtime ← host`, `runtime ← api`. koshei 의 어떤 모듈에도 기대지 않는다(`:runtime` 의 `RawJson` 63줄과 `:blocks` 의 `Db` 19줄을 복사해 끊는다).
- Temporal 은 지금처럼 `localhost:7233` 을 쓴다(narrator 워커와 picasso 가 붙는 같은 서버). Postgres 는 koshchei 의 compose 가 `15433` 에 자기 DB `koshchei` 를 띄운다. koshchei 의 compose 에 Temporal 은 `profiles: [temporal]` 으로만 둔다(기본으로 켜면 koshei 의 7233 과 부딪친다).
- 포트: 제어면 `18190`, UI 개발 서버 `5174` (koshei 는 18090 · 5173 그대로).

**Tech Stack:** Kotlin 2.2.0 / JVM 21, Gradle 9.0.0, Temporal Java SDK 1.25.1, Jackson 2.17.2, Postgres JDBC 42.7.4 + Testcontainers 1.20.1(postgres:16), Spring Boot 3.3.4 + HikariCP 5.1.0, React 18.3 + Vite 5 + Vitest 2 + Playwright 1.49.

**작업 위치:**
- 새 저장소: `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshchei` (아래 `$K`). 원본은 worktree `C:\Users\Eisen\Desktop\Labs\[projects] koshchei\koshei-episode-core` (아래 `$S`), main `6e66dbe` 와 같은 트리.
- 덩이 5(koshei 정리)는 `$S` 에서 새 브랜치 `chore/remove-episode-loop` 로 한다.
- 쉘 변수(bash):
  ```bash
  S="/c/Users/Eisen/Desktop/Labs/[projects] koshchei/koshei-episode-core"
  K="/c/Users/Eisen/Desktop/Labs/[projects] koshchei/koshchei"
  ```

**금지:** `./gradlew --stop` · `clean` 금지(공유 데몬). 메인 체크아웃 `koshei` 는 건드리지 않는다. 산문(README · usage · 커밋 메시지 · PR 본문 · 설계 문서 문장)은 `delegating-prose-to-gemini` 스킬로 Gemini 가 쓰고 Claude 가 대조한다. 커밋 꼬리말은 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 한 줄뿐.

---

## 이름 대응표

| 범주 | koshei (지금) | koshchei |
|---|---|---|
| 패키지 | `koshei.episode` | `koshchei.core` |
| 패키지 | `koshei.episode.runtime` | `koshchei.runtime` |
| 패키지 | `koshei.runtime.{DataConverterSupport, RawJson, RawAwareJsonPayloadConverter}` | `koshchei.runtime.*` |
| 패키지 | `koshei.blocks.{Db, DbConnectionOverride}` | `koshchei.runtime.*` |
| 패키지 | `koshei.app` (에피소드 파일) | `koshchei.host` |
| 패키지 | `koshei.authoring` (에피소드 파일) | `koshchei.api` |
| 환경변수 | `KOSHEI_*` (에피소드가 읽는 16개: PICASSO · PICASSO_URL · PICASSO_AGENT_ID · PICASSO_TIMEOUT_MS · NARRATOR · EPISODE_POLICY · EPISODE_DB_USER · EPISODE_DB_PASS · WATCH_EXPORTS · WATCH_CARRY · WATCH_INTERVAL_MS · DB_URL · DB_USER · DB_PASS · BIND_ADDRESS · WORKER_NAME) | `KOSHCHEI_*` 같은 꼬리 |
| DB 기본값 | `jdbc:postgresql://localhost:15432/koshei`, `koshei`/`koshei` | `jdbc:postgresql://localhost:15433/koshchei`, `koshchei`/`koshchei` |
| DB 역할 | `koshei_episode_rt` | `koshchei_rt` |
| DB 표 · 시퀀스 · 색인 | `episode_*` | **그대로** (상표가 없다) |
| Temporal 큐 | `koshei-episode-tq` | `koshchei-episode-tq` |
| Temporal 큐 | `narrator-tq`, 액티비티 `diagnose` | **그대로** — narrator 계약 0.6 §2 |
| 워크플로 · 액티비티 · signal · update · query 이름 | `EpisodeWorkflow`, `episodeRecord` … | **그대로** (상표 없음, 재생 이력과 맞물림) |
| 워크플로 id 접두 | `ep:` | 그대로 |
| 시스템 속성 | `koshei.{repoRoot, updateFixtures, contractFixturesDir, writeReplayHistories, replaySet}` | `koshchei.*` |
| HTTP 헤더 | `X-Koshei-Operator` | `X-Koshchei-Operator` |
| UI localStorage | `koshei.operator` | `koshchei.operator` |
| 정책 파일 | `model/episode-policy/active.yaml` | `policy/active.yaml` |
| 권한 스크립트 | `scripts/episode-db-roles.sql` | `scripts/db-roles.sql` |
| 로그 머리 | `koshei watcher:` | `koshchei watcher:` |
| Mock 승인자 | `MockPicasso` 의 `approverId "koshei"` | `"koshchei"` |
| 포트 | 18090 · 5173 | 18190 · 5174 |

**커밋된 재생 이력(`replay/2026-10-04/*.json`)은 고치지 않는다.** 그 안에 `koshei-episode-tq` 와 생성기 identity `koshei-replay-generator` 가 적혀 있지만, 재생 시험은 `"replay"` 큐의 워커로 돌리고 payload 843개에는 상표도 클래스 이름도 없다(조사 2026-10-05). 바뀐 코드가 이 이력을 재생하는지가 바로 Task 3 의 시험이다.

## 상표 치환 sed (모든 Task 가 같은 파일을 쓴다)

`$K/../koshchei-rebrand.sed` (저장소 밖, 커밋하지 않는다). **순서가 뜻을 가진다** — 긴 패키지가 먼저다.

```sed
s/koshei\.episode\.runtime/koshchei.runtime/g
s/koshei\.episode/koshchei.core/g
s/koshei\.runtime/koshchei.runtime/g
s/koshei\.blocks/koshchei.runtime/g
s/koshei\.app/koshchei.host/g
s/koshei\.authoring/koshchei.api/g
s/koshei\.\(repoRoot\|updateFixtures\|contractFixturesDir\|writeReplayHistories\|replaySet\|operator\)/koshchei.\1/g
s/KOSHEI_/KOSHCHEI_/g
s/koshei-episode-tq/koshchei-episode-tq/g
s/koshei_episode_rt/koshchei_rt/g
s/X-Koshei-Operator/X-Koshchei-Operator/g
s/x-koshei-operator/x-koshchei-operator/g
s#model/episode-policy/active\.yaml#policy/active.yaml#g
s#scripts/episode-db-roles\.sql#scripts/db-roles.sql#g
s/koshei watcher:/koshchei watcher:/g
s/:episode-runtime:/:runtime:/g
s/:episode:/:core:/g
```

적용(디렉터리 `<dir>` 아래, 재생 이력 제외):

```bash
find <dir> -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.sql' -o -name '*.md' -o -name '*.yaml' -o -name '*.yml' -o -name '*.ts' -o -name '*.tsx' -o -name '*.json' \) \
  -not -path '*/replay/*' -not -path '*/node_modules/*' -print0 | xargs -0 sed -i -f "$K/../koshchei-rebrand.sed"
```

**`docs/` 에는 이 sed 를 그대로 쓰지 않는다.** 옮겨 온 설계 문서와 계획에는 koshei 사가 쪽 클래스(`koshei.runtime.*`, `koshei.blocks.*`, `koshei.app.*` 의 사가 파일)를 가리키는 역사적 참조가 있어, 일반 규칙(3-6행)이 그것까지 틀리게 바꾼다. `docs/` 에는 에피소드 이름 규칙만 쓴다: 1-2행(`koshei.episode*`)과 9행 이후(큐 · 역할 · 헤더 · 경로 · 로그 · Gradle 경로). 7행(시스템 속성)과 8행(`KOSHEI_` 일반 규칙)은 쓰지 않고, 에피소드 환경변수 16개만 이름을 하나씩 바꾼다 — 역사 문서 속 koshei 자신의 변수(`KOSHEI_PUBLISH_DISABLED`, `KOSHEI_AUTOCORRECT_*` 등)를 바꾸지 않기 위해서다.

치환 뒤 남은 상표는 손으로 본다: `grep -rn -i 'koshei' <dir> --exclude-dir=replay --exclude-dir=node_modules`. 남겨도 되는 것은 출처 문장(「koshei 에서 분리」)과 설계 문서 본문의 역사적 서술뿐이다.

---

## Chunk 1: 저장소 뼈대 · 코어 · 런타임

### Task 1: 저장소 뼈대

**Files (모두 Create, `$K` 아래):** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitignore`, `.gitattributes`, `docker-compose.yml`, `LICENSE.md`, `../koshchei-rebrand.sed`

- [ ] **Step 1: 디렉터리와 git**

```bash
mkdir -p "$K" && cd "$K" && git init -q -b main
cp -r "$S/gradle" "$K/gradle" && cp "$S/gradlew" "$S/gradlew.bat" "$S/LICENSE.md" "$K/"
```

위 「상표 치환 sed」 내용을 `$K/../koshchei-rebrand.sed` 로 쓴다.

- [ ] **Step 2: `settings.gradle.kts`**

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "koshchei"
```

**모듈은 여기서 넣지 않는다.** Gradle 9.0.0 은 디렉터리가 없는 포함 프로젝트를 오류로 멈춘다(「Configuring project … without an existing directory is not allowed」). 각 Task 가 디렉터리를 만든 뒤 자기 모듈을 `include(...)` 한 줄로 더한다: Task 2 `core`, Task 3 `runtime`, Task 4 `host`, Task 5 `api`.

- [ ] **Step 3: 루트 `build.gradle.kts`** — koshei 와 같다.

```kotlin
plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.spring") version "2.2.0" apply false
}
allprojects { repositories { mavenCentral() } }
```

- [ ] **Step 4: `gradle/libs.versions.toml` 을 줄인다** — koshei 의 것에서 다음 별칭만 남긴다: `temporal-sdk`, `temporal-testing`, `jackson-databind`, `jackson-kotlin`, `jackson-yaml`, `snakeyaml`, `postgresql`, `slf4j-simple`, `junit-jupiter`, `testcontainers-postgresql`, `spring-boot-starter-web`, `spring-boot-starter-test`, `hikaricp` (와 이들이 쓰는 `[versions]`). conductor · milo · tahu · anthropic 은 지운다.

- [ ] **Step 5: `.gitignore` · `.gitattributes`**

`.gitignore` 는 koshei 의 것을 복사하고 `# Node (authoring-ui)` 를 `# Node (ui)` 로 고친다. `.gitattributes`:

```
core/src/test/resources/** text eol=lf
runtime/src/test/resources/** text eol=lf
host/src/test/resources/** text eol=lf
api/src/test/resources/** text eol=lf
```

(이 기계는 `core.autocrlf=true` 다. 시험 자원이 CRLF 로 바뀌면 바이트를 비교하는 픽스처 시험이 깨진다.)

- [ ] **Step 6: `docker-compose.yml`**

```yaml
# koshchei's own Postgres. Temporal is shared by default (localhost:7233, the server narrator's worker and picasso use);
# `docker compose --profile temporal up -d` starts a private one only when nothing else holds 7233.
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: koshchei
      POSTGRES_USER: koshchei
      POSTGRES_PASSWORD: koshchei
    ports: ["15433:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U koshchei -d koshchei"]
      interval: 5s
      timeout: 5s
      retries: 20
  temporal:
    profiles: [temporal]
    image: temporalio/auto-setup:1.25.2
    environment:
      DB: postgres12
      DB_PORT: 5432
      POSTGRES_USER: koshchei
      POSTGRES_PWD: koshchei
      POSTGRES_SEEDS: postgres
    ports: ["7233:7233"]
    depends_on: [postgres]
```

(Temporal 이미지 판 `1.25.2` 는 koshei 의 `docker-compose.yml` 과 같다.)

- [ ] **Step 7: 빈 빌드가 도는지**

Run: `cd "$K" && ./gradlew -q help`
Expected: 오류 없이 끝남(아직 모듈이 없다).

커밋은 Task 3 끝에 한 번 한다(첫 커밋 = 출처 기록, S2).

### Task 2: `:core` (옛 `:episode`)

**Files:**
- Create: `$K/core/**` ← `$S/episode/**` (build 디렉터리 제외)
- Modify: `$K/core/build.gradle.kts`

- [ ] **Step 1: 복사하고 디렉터리를 패키지에 맞춘다**

```bash
mkdir -p "$K/core" && cd "$S/episode" && git ls-files | while read f; do mkdir -p "$K/core/$(dirname "$f")"; cp "$f" "$K/core/$f"; done
cd "$K/core/src" && for s in main test; do
  [ -d "$s/kotlin/koshei/episode" ] || continue
  mkdir -p "$s/kotlin/koshchei/core" && mv "$s/kotlin/koshei/episode"/* "$s/kotlin/koshchei/core/" && rmdir "$s/kotlin/koshei/episode" "$s/kotlin/koshei"
done
```

(`koshei/episode/` 아래에 하위 패키지가 없다 — `package koshei.episode` 55파일뿐, 조사 2026-10-05.)

- [ ] **Step 2: 상표 치환** — 「상표 치환 sed」를 `$K/core` 에 적용한다. `build.gradle.kts` 의 `koshei.updateFixtures` · `koshei.contractFixturesDir` 도 이 치환으로 바뀐다.

- [ ] **Step 3: 남은 상표 확인**

Run: `grep -rn -i koshei "$K/core" --exclude-dir=build`
Expected: 스무 줄 안팎 — KDoc 과 시험 이름의 「koshei」(CandidatesVersion · DiagnosisVerdicts · Dispatching · EpisodeHistory · EpisodeModel · Jcs · Snapshot 과 시험 몇 개), `ContractFixtures.kt:23` 의 「episode/build.gradle.kts sets it」, contract `README.md`. 시스템 이름은 `koshchei` 로, 경로는 `core/build.gradle.kts` 로 고친다. **다른 저장소의 문서를 인용한 낱말은 둔다** — 예: `EpisodeModel.kt:33` 의 「koshei 거절 열거값」은 narrator 계약 0.6 의 낱말이다(그 계약이 바뀌기 전까지).

- [ ] **Step 4: 시험**

`settings.gradle.kts` 에 `include("core")` 를 더하고:
Run: `cd "$K" && ./gradlew :core:test`
Expected: BUILD SUCCESSFUL, 시험 **323** 개 통과(`core/build/test-results/test/*.xml` 의 `tests=` 합).

### Task 3: `:runtime` (옛 `:episode-runtime` + 끊어 낸 두 파일)

**Files:**
- Create: `$K/runtime/**` ← `$S/episode-runtime/**`
- Create: `$K/runtime/src/main/kotlin/koshchei/runtime/DataConverterSupport.kt` ← `$S/runtime/src/main/kotlin/koshei/runtime/DataConverterSupport.kt`
- Create: `$K/runtime/src/test/kotlin/koshchei/runtime/RawJsonConverterTest.kt` ← `$S/runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt`
- Create: `$K/runtime/src/main/kotlin/koshchei/runtime/Db.kt` ← `$S/blocks/src/main/kotlin/koshei/blocks/Db.kt`
- Create: `$K/policy/active.yaml` ← `$S/model/episode-policy/active.yaml`
- Create: `$K/scripts/db-roles.sql` ← `$S/scripts/episode-db-roles.sql`
- Modify: `$K/runtime/build.gradle.kts`, `CommittedReplayTest.kt` (경로 조각)

- [ ] **Step 1: 복사**

```bash
mkdir -p "$K/runtime" && cd "$S/episode-runtime" && git ls-files | while read f; do mkdir -p "$K/runtime/$(dirname "$f")"; cp "$f" "$K/runtime/$f"; done
cd "$K/runtime/src" && for s in main test; do
  [ -d "$s/kotlin/koshei/episode/runtime" ] || continue
  mkdir -p "$s/kotlin/koshchei/runtime" && mv "$s/kotlin/koshei/episode/runtime"/* "$s/kotlin/koshchei/runtime/" && rmdir "$s/kotlin/koshei/episode/runtime" "$s/kotlin/koshei/episode" "$s/kotlin/koshei"
done
cp "$S/runtime/src/main/kotlin/koshei/runtime/DataConverterSupport.kt" "$K/runtime/src/main/kotlin/koshchei/runtime/"
cp "$S/runtime/src/test/kotlin/koshei/runtime/RawJsonConverterTest.kt" "$K/runtime/src/test/kotlin/koshchei/runtime/"
cp "$S/blocks/src/main/kotlin/koshei/blocks/Db.kt" "$K/runtime/src/main/kotlin/koshchei/runtime/"
mkdir -p "$K/policy" "$K/scripts"
cp "$S/model/episode-policy/active.yaml" "$K/policy/active.yaml"
cp "$S/scripts/episode-db-roles.sql" "$K/scripts/db-roles.sql"
```

(`koshei/episode/runtime/` 아래도 하위 패키지가 없다 — `package koshei.episode.runtime` 47파일뿐.)

- [ ] **Step 2: `runtime/build.gradle.kts`** — `:runtime` 의존을 없애고 `:core` 로 바꾼다.

```kotlin
plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("koshchei.repoRoot", rootProject.projectDir.absolutePath)
    System.getProperty("koshchei.writeReplayHistories")?.let { systemProperty("koshchei.writeReplayHistories", it) }
    System.getProperty("koshchei.replaySet")?.let { systemProperty("koshchei.replaySet", it) }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.snakeyaml)
    implementation(libs.postgresql)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.slf4j.simple)   // koshei's :runtime used to bring it; without it Testcontainers/Temporal log to NOP
}
```

`:host` 와 `:api` 가 `koshchei.core` 타입을 직접 쓰면(`EpisodeController` 가 코어 타입을 받는다) `implementation(project(":core"))` 를 `api(project(":core"))` 로 바꾸고 맨 위 `plugins` 에 `` `java-library` `` 를 더한다 — Task 4 · 5 에서 컴파일 오류로 드러나면 그때 한다.

- [ ] **Step 3: 상표 치환** — sed 를 `$K/runtime`, `$K/policy`, `$K/scripts` 에 적용한다(재생 이력 제외 조건이 들어 있다).

- [ ] **Step 4: 경로 조각과 DB 기본값을 손으로 고친다**
  - `CommittedReplayTest.kt`: `Path.of(System.getProperty("koshchei.repoRoot"), "episode-runtime", "src", "test", "resources", "replay")` 의 `"episode-runtime"` → `"runtime"`. 같은 모양의 경로 조각을 `grep -rn '"episode-runtime"\|"episode"' "$K/runtime/src"` 로 모두 찾아 고친다.
  - `Db.kt`: 기본값을 `jdbc:postgresql://localhost:15433/koshchei`, `koshchei`, `koshchei` 로.
  - `MockPicasso.kt`: `put("approverId", "koshei")` → `"koshchei"`. 이 값을 단정하는 시험이 있으면 함께.
  - `CommittedReplayTest.kt` 의 `IDENTITY = "koshei-replay-generator"` → `"koshchei-replay-generator"`(새로 쓰는 묶음에만 영향).
  - `EpisodeWorkersTest.kt` 의 `/etc/koshei/p.yaml` → `/etc/koshchei/p.yaml`.
  - `scripts/db-roles.sql` 머리 예시 `-U koshei -d koshei` → `-U koshchei -d koshchei`.

  - `RawJsonConverterTest.kt:29-30` 의 셋째 시험이 koshei 사가의 `WorkflowInput(workflowName = "x")`(`runtime/.../Messages.kt:8`)을 쓴다. 옮기지 않는 클래스다 — 시험 파일 안에 인자 없는 생성자가 없는 Kotlin data class(`private data class Probe(val name: String)`)를 두고 그것으로 바꾼다. 시험이 보는 것(Kotlin 을 아는 Jackson)은 그대로다.
  - `DataConverterSupport.kt` 의 KDoc 에서 사가 이야기(WorkflowInput/WorkflowOutput, saga, spike)를 빼고 「에피소드 워크플로의 `RawJson` 을 그대로 싣는다」로 줄인다.
  - **`Path.of(...)` 조각으로 쓴 경로**는 sed(슬래시 문자열만 바꾼다)도 위 grep 도 못 잡는다. 다음을 고친다:
    - `Path.of(repoRoot, "model", "episode-policy", "active.yaml")` → `Path.of(repoRoot, "policy", "active.yaml")`: `EpisodeEndToEndTest.kt:20`, `PicassoWindowEndToEndTest.kt:24`, `PolicyFileReaderTest.kt:84, 105`, `WatcherEndToEndTest.kt:44`.
    - `EpisodeWorkersTest.kt:12` 의 기본값 단정 `Path.of("model","episode-policy","active.yaml")` → `Path.of("policy","active.yaml")` (본 코드 `EpisodeWorkers.kt:92` 의 기본값을 sed 가 `policy/active.yaml` 로 바꾸므로 함께 바꿔야 한다).
    - `EpisodeDbRolesTest.kt:16` 의 `Path.of(repoRoot, "scripts", "episode-db-roles.sql")` → `"scripts", "db-roles.sql"`.
    - 확인: `grep -rn '"episode-policy"\|"episode-db-roles.sql"\|"episode-runtime"\|"model"' "$K/runtime/src"` 가 비어야 한다.

- [ ] **Step 5: 남은 상표 확인**

Run: `grep -rn -i koshei "$K/runtime" "$K/policy" "$K/scripts" --exclude-dir=build --exclude-dir=replay`
Expected: 없음(KDoc 의 「koshei 쪽」 같은 시스템 이름은 `koshchei` 로 고친다. picasso · narrator 문서를 인용한 줄은 그 문서의 낱말이면 둔다).

- [ ] **Step 6: 시험 — 재생 이력이 여기서 판가름 난다**

`settings.gradle.kts` 에 `include("runtime")` 를 더하고:
Run: `cd "$K" && ./gradlew :runtime:test`
Expected: BUILD SUCCESSFUL. 시험 **311** 개(옛 308 + `RawJsonConverterTest` 3), 건너뜀 1(재생 이력 생성기). `CommittedReplayTest` 의 재생 시험이 통과해야 한다.

**재생이 깨지면(예상하지 않지만):** 패키지 이름은 이력에 없으므로 원인은 큐 이름이다. 그때만 — 옛 묶음을 지우지 말고 이유를 기록한 뒤 사용자에게 묻는다(묶음 삭제는 R3 규칙상 `getVersion` 갈래와 함께만 한다).

- [ ] **Step 7: 첫 커밋** — 메시지는 Gemini 로(사실: koshei `6e66dbe` 에서 분리, PR #4 · #5, 이력은 koshei 에 있다, 이 커밋은 core 와 runtime). 꼬리말은 Claude 한 줄.

```bash
cd "$K" && git add -A && git commit -q -F <msg-file> && git log --oneline -1
```

---

## Chunk 2: `:host` — 워커 · 감시자 · CLI

### Task 4: `:host`

**Files:**
- Create: `$K/host/build.gradle.kts`
- Create: `$K/host/src/main/kotlin/koshchei/host/Worker.kt` (새로 씀 — koshei `Worker.kt` 의 공유 줄 55-57 · 132 와 에피소드 줄 71-95 만)
- Create: `$K/host/src/main/kotlin/koshchei/host/Cli.kt` (새로 씀 — koshei `Cli.kt` 의 `doEpisode` · `episodeFailed` · 사용법 두 줄만)
- Create: `Watcher.kt`, `EpisodeCli.kt`, `EpisodeDbLogin.kt` ← `$S/app/src/main/kotlin/koshei/app/`
- Create(test): `EpisodeCliTest.kt`, `EpisodeDbLoginTest.kt`, `WatcherConfigTest.kt` ← `$S/app/src/test/kotlin/koshei/app/`, `src/test/resources/picasso/run-1/*` ← `$S/app/src/test/resources/picasso/run-1/*`
- Modify: `$K/settings.gradle.kts` (`host` 포함)

- [ ] **Step 1: 그대로 옮기는 파일 셋과 시험 셋, 시험 자원** — 복사하고 sed 를 적용한다. 그 뒤 `grep -rn -i 'koshei\|saga\|workflow_def\|block registry' "$K/host"` 로 남은 것을 본다: `Watcher.kt:38`, `EpisodeCli.kt:111`, `EpisodeDbLogin` 의 KDoc(「워커가 블록 레지스트리 · workflow_def 도 읽으므로 따로 계정을 둔다」 — koshchei 에는 그 이유가 없다. 「에피소드 표만 쓰는 계정을 따로 줄 수 있다」로 줄인다).

- [ ] **Step 2: `host/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(libs.temporal.sdk)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
application { mainClass.set("koshchei.host.WorkerKt") }

// The Gradle daemon keeps the environment it started with, so forward every KOSHCHEI_* from the live shell into
// the forked JVM (the same rule koshei's control plane uses). The working directory is host/, so a relative default
// policy path would miss the repo's policy/: pass the absolute path when the variable is unset.
fun JavaExec.forwardKoshcheiEnv() {
    System.getenv().filterKeys { it.startsWith("KOSHCHEI_") }.forEach { (k, v) -> environment(k, v) }
    if (System.getenv("KOSHCHEI_EPISODE_POLICY") == null) {
        environment("KOSHCHEI_EPISODE_POLICY", rootProject.file("policy/active.yaml").absolutePath)
    }
}
tasks.named<JavaExec>("run") { forwardKoshcheiEnv() }
tasks.register<JavaExec>("watcher") {
    group = "application"
    mainClass.set("koshchei.host.WatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKoshcheiEnv()
}
tasks.register<JavaExec>("cli") {
    group = "application"
    mainClass.set("koshchei.host.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKoshcheiEnv()
}
```

(koshei `:app` 의 `jackson-kotlin` 의존은 `app/src/main` 어디서도 쓰이지 않았다 — 옮기지 않는다. 컴파일이 요구하면 그때 더한다.)

- [ ] **Step 3: 실패하는 시험 — 워커는 OFF 를 거절한다**

`$K/host/src/test/kotlin/koshchei/host/WorkerStartTest.kt`:

```kotlin
package koshchei.host

import koshchei.runtime.EpisodeRuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerStartTest {
    @Test
    fun `off is refused, since koshchei has nothing else to run`() {
        val e = assertFailsWith<IllegalStateException> { workerConfig(emptyMap()) }
        assertEquals(true, e.message!!.contains("KOSHCHEI_PICASSO"))
    }

    @Test
    fun `mock is accepted`() {
        workerConfig(mapOf("KOSHCHEI_PICASSO" to "mock"))
    }
}
```

Run: `./gradlew :host:test --tests 'koshchei.host.WorkerStartTest'`
Expected: 컴파일 실패 — `workerConfig` 가 없다.

- [ ] **Step 4: `Worker.kt`**

```kotlin
package koshchei.host

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import koshchei.runtime.DataConverterSupport
import koshchei.runtime.EPISODE_TASK_QUEUE
import koshchei.runtime.EpisodeRuntimeConfig
import koshchei.runtime.EpisodeStore
import koshchei.runtime.EpisodeWorkers
import koshchei.runtime.PicassoMode
import java.nio.file.Files

/** The episode worker's configuration; `off` is refused because koshchei runs nothing else (unlike koshei, whose saga worker ran beside it). */
fun workerConfig(env: Map<String, String>): EpisodeRuntimeConfig {
    val config = EpisodeRuntimeConfig.fromEnv(env)
    check(config.picasso != PicassoMode.OFF) {
        "KOSHCHEI_PICASSO is unset or off: name the approval window, mock (the test's) or picasso (the real one)"
    }
    return config
}

fun main() {
    val workerName = System.getenv("KOSHCHEI_WORKER_NAME") ?: "worker-1"
    val config = workerConfig(System.getenv())

    val service = WorkflowServiceStubs.newLocalServiceStubs() // localhost:7233
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val factory = WorkerFactory.newInstance(client)

    val login = EpisodeDbLogin.fromEnv(System.getenv())
    println("[$workerName] episode tables as DB user '${login.user}' (KOSHCHEI_EPISODE_DB_USER, else KOSHCHEI_DB_USER)")
    val store = EpisodeStore { login.connect() }
    store.ensureSchema()
    EpisodeWorkers.register(factory, config, store)
    if (config.picasso == PicassoMode.MOCK) {
        System.err.println("[$workerName] WARNING episode worker on $EPISODE_TASK_QUEUE with the TEST Mock picasso: remedies reach no robot (design §8.3, §11)")
    }
    if (config.picasso == PicassoMode.PICASSO) {
        // The URL only: approver ids stay out of logs (EpisodeActivitiesImpl), the configured agent id included.
        println("[$workerName] episode approvals go to ${config.window!!.url} as PERSON, or AGENT under the configured agent id; revalidation is UNKNOWN, so a person confirms each precondition")
    }
    // A missing file is read as MISSING on every decision, so every episode escalates POLICY_MISSING (design §8.2).
    if (!Files.exists(config.policyPath)) {
        System.err.println("[$workerName] WARNING episode policy ${config.policyPath.toAbsolutePath()} does not exist: every episode will escalate POLICY_MISSING (set KOSHCHEI_EPISODE_POLICY)")
    }
    println("[$workerName] episode worker on $EPISODE_TASK_QUEUE (narrator ${config.narrator}, policy ${config.policyPath})")
    factory.start()
}
```

(koshei 판과 줄 단위로 맞춰 본다: 옛 71-95 의 문장과 순서를 그대로 두고, 사가 바인딩 · 배포 폴링만 뺀다. `EpisodeRuntimeConfig.fromEnv` 의 인자 형이 `Map<String,String>` 이 아니면 그 형에 맞춘다.)

- [ ] **Step 5: `Cli.kt`** — koshei `Cli.kt` 54-96 의 `doEpisode` · `episodeFailed` 를 그대로 옮기고, 명령 이름 `episode` 를 없앤다(`koshchei` 의 CLI 는 에피소드뿐이다): `cli open …`, `cli agent-off …`.

```kotlin
package koshchei.host

import kotlin.system.exitProcess

/**
 * Development CLI (design §7.2): the watcher is what carries symptoms in production.
 *   open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]
 *   agent-off (<workflowId> | --all)
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "open", "agent-off" -> doEpisode(args)
        null -> usageError("command required (open|agent-off)")
        else -> usageError("unknown command: ${args[0]} (expected open|agent-off)")
    }
}
```

`doEpisode` 는 koshei 판에서 `args.getOrNull(1)` → `args.getOrNull(0)`, `args.drop(2)` → `args.drop(1)` 로 한 칸씩 당긴다. `usageError` 는 koshei 의 것(491-)에서 에피소드 사용법 두 줄만 남긴다(`episode ` 접두를 뺀다).

- [ ] **Step 6: 시험**

`settings.gradle.kts` 에 `include("host")` 를 더하고:
Run: `cd "$K" && ./gradlew :host:test`
Expected: 통과 **29** 개(옮긴 27 + `WorkerStartTest` 2). `EpisodeCliTest` 가 `episode open` 처럼 옛 명령 모양을 부르면 Step 5 의 새 모양으로 고친다 — 고친 줄을 커밋 메시지에 적는다.

동작이 달라진 두 곳도 커밋 메시지에 적는다: (1) 워커 로그 「(…, else the worker's own)」→「else KOSHCHEI_DB_USER」 (2) `forwardKoshcheiEnv` 가 `cli` 에도 정책 기본 경로를 넣는다(koshei 는 run · watcher 에만).

- [ ] **Step 7: 커밋** — `git add host settings.gradle.kts` 와 Gemini 커밋 메시지.

---

## Chunk 3: 제어면과 UI

### Task 5: `:api`

**Files:**
- Create: `$K/api/build.gradle.kts`, `src/main/resources/application.yml`
- Create: `$K/api/src/main/kotlin/koshchei/api/KoshcheiApiApplication.kt`, `DataSourceConfig.kt`
- Create: `EpisodeConfig.kt`, `EpisodeController.kt`, `EpisodeGateway.kt`, `ApiExceptionHandler.kt` ← `$S/authoring-api/src/main/kotlin/koshei/authoring/`
- Create(test): `EpisodeControllerTest.kt`, `EpisodeGatewayTest.kt` ← `$S/authoring-api/src/test/kotlin/koshei/authoring/`, `src/test/resources/picasso/run-1/{manifest.json, remedy-searches.jsonl}`
- Create(test): `ContextLoadsTest.kt` (새로)

- [ ] **Step 1: 옮기고 sed 적용.** `EpisodeGatewayTest.kt:51` 의 `Path.of(repoRoot, "model", "episode-policy", "active.yaml")` → `Path.of(repoRoot, "policy", "active.yaml")` (sed 가 못 잡는 조각 경로). `EpisodeGatewayTest.kt:48` 의 사가 · koshei 서술은 고친다. 남은 것 확인: `grep -rn -i 'koshei\|"episode-policy"' "$K/api"` 가 비어야 한다. `ApiExceptionHandler` 의 KDoc 은 publish 이야기이니 한 줄로 줄인다: 「본문 파싱 실패와 잘못된 인자는 400 이다. 일반 예외는 500 으로 둔다.」

- [ ] **Step 2: `api/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    application
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("koshchei.repoRoot", rootProject.projectDir.absolutePath)
}
dependencies {
    implementation(project(":runtime"))
    implementation(libs.temporal.sdk)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.temporal.testing)
}
application { mainClass.set("koshchei.api.KoshcheiApiApplicationKt") }
tasks.named<JavaExec>("run") {
    System.getenv().filterKeys { it.startsWith("KOSHCHEI_") }.forEach { (k, v) -> environment(k, v) }
}
```

- [ ] **Step 3: `KoshcheiApiApplication.kt` · `DataSourceConfig.kt` · `application.yml`**

```kotlin
package koshchei.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KoshcheiApiApplication

fun main(args: Array<String>) { runApplication<KoshcheiApiApplication>(*args) }
```

```kotlin
package koshchei.api

import com.zaxxer.hikari.HikariDataSource
import koshchei.runtime.Db
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DataSourceConfig {
    // Lazy like koshei's control plane: no connection at startup (minimumIdle 0, no startup probe), so the context
    // starts without the DB and a request that needs it gets the controller's 503.
    @Bean(destroyMethod = "close")
    fun dataSource(): HikariDataSource = HikariDataSource().apply {
        jdbcUrl = Db.url
        username = Db.user
        password = Db.pass
        poolName = "koshchei-api"
        maximumPoolSize = 5
        minimumIdle = 0
        initializationFailTimeout = -1
    }
}
```

```yaml
server:
  port: 18190
  # Loopback by default: the control plane has no authentication, so it must not be reachable from the network
  # unless an operator opts in with KOSHCHEI_BIND_ADDRESS (e.g. 0.0.0.0).
  address: ${KOSHCHEI_BIND_ADDRESS:127.0.0.1}
```

`EpisodeConfig` 의 읽기 DB 는 `EpisodeDbLogin` 이 아니라 koshei 때처럼 이 데이터소스다(바꾸지 않는다).

- [ ] **Step 4: `ContextLoadsTest.kt`**

```kotlin
package koshchei.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ContextLoadsTest {
    @Test fun `the context starts without Temporal or the DB`() {}
}
```

- [ ] **Step 5: 시험**

`settings.gradle.kts` 에 `include("api")` 를 더하고:
Run: `cd "$K" && ./gradlew :api:test`
Expected: 통과 **42** 개(`EpisodeControllerTest` 29 + `EpisodeGatewayTest` 12 + `ContextLoadsTest` 1). `@WebMvcTest` 는 같은 패키지의 `@SpringBootApplication` 을 찾는다 — 그래서 앱 클래스가 `koshchei.api` 에 있다.

- [ ] **Step 6: 커밋.**

### Task 6: `ui/`

**Files:**
- Create: `$K/ui/{package.json, index.html, vite.config.ts, vitest.config.ts, tsconfig.json, playwright.config.ts}`
- Create: `$K/ui/src/{main.tsx, styles.css}`
- Create: `$K/ui/src/views/episodes/*` ← `$S/authoring-ui/src/views/episodes/*` (10파일)
- Create: `$K/ui/e2e/{episodes.spec.ts, warmup.setup.ts}` ← `$S/authoring-ui/e2e/`

- [ ] **Step 0: 화면 · e2e 복사와 상표 치환**

```bash
mkdir -p "$K/ui/src/views/episodes" "$K/ui/e2e"
cp "$S/authoring-ui/src/views/episodes/"* "$K/ui/src/views/episodes/"
cp "$S/authoring-ui/e2e/episodes.spec.ts" "$S/authoring-ui/e2e/warmup.setup.ts" "$K/ui/e2e/"
cp "$S/authoring-ui/package-lock.json" "$S/authoring-ui/.gitignore" "$K/ui/"
```

그리고 「상표 치환 sed」를 `$K/ui/src` 와 `$K/ui/e2e` 에 적용한다. 확인: `grep -rn -i koshei "$K/ui/src" "$K/ui/e2e"` 가 비어야 한다 — 특히 `episodeApi.ts:47` 의 헤더(`X-Koshchei-Operator`, 새 제어면이 이 이름만 받는다)와 `EpisodesView.tsx:9` 의 localStorage 키(`koshchei.operator`).

`package-lock.json` 을 가져오는 까닭: `@playwright/test` 를 koshei 와 같은 1.61.1 로 풀기 위해서다. **(실행 중 바뀜, 2026-10-05)** 시험은 lock 이 고정한 내장 브라우저가 아니라 설치된 Chrome 으로 돈다 — spec 은 `test.use({ channel: "chrome" })` 를 이미 쓰고, warmup 프로젝트에도 `channel: "chrome"` 을 더했다(이 기계에는 1.61.1 의 내장 브라우저 판 1228 이 캐시돼 있지 않다. `npx playwright install` 은 금지). `ui/.gitignore` 는 `test-results/` 를 막는다(`video: "on"` 의 webm 이 커밋에 섞이지 않게). `package.json` 을 줄인 뒤 `npm install` 이 lock 을 맞춘다.

- [ ] **Step 1: 설정 파일** — `$S/authoring-ui` 의 것에서 출발한다.
  - `package.json`: `"name": "koshchei-ui"`. dependencies 는 `react`, `react-dom` 만(`@xyflow/react` · `yaml` 은 Episodes 화면이 쓰지 않는다). devDependencies 와 scripts 는 그대로.
  - `vite.config.ts`: `port: 5174`, proxy `/api` → `http://127.0.0.1:18190`.
  - `playwright.config.ts`: `baseURL: "http://localhost:5174"`, 나머지(webServer `npm run dev`, workers 1, warmup 프로젝트) 그대로.
  - `vitest.config.ts`, `tsconfig.json`: 그대로.

- [ ] **Step 2: `main.tsx` · `index.html`**

```tsx
import React from "react";
import ReactDOM from "react-dom/client";
import "./styles.css";
import { EpisodesView } from "./views/episodes/EpisodesView";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <header className="app-head"><span className="brand">Koshchei</span><span className="muted">Episodes</span></header>
    <main className="app-main"><EpisodesView /></main>
  </React.StrictMode>,
);
```

(`EpisodesView` 의 export 이름 · props 는 원본을 따른다. koshei `App.tsx` 144 행이 그것을 어떻게 렌더하는지 보고 같은 props 를 넘긴다.)

`index.html` 은 `$S/authoring-ui/index.html` 을 복사하고 `<title>` 을 `Koshchei` 로.

- [ ] **Step 3: `styles.css`** — koshei `styles.css` 에서 다음만 옮긴다: `:root` 의 토큰 전부(`--sp-*`, `--ink*`, `--mono`, `--risk-*`, `--line*`, `--fs-*` 와 이들이 참조하는 것), `body` 기본, 그리고 Episodes 화면이 쓰는 클래스 — `panel`, `panel-body`, `btn`, `primary`, `ghost`, `danger`, `chip`, `ok`, `err`, `info`, `muted`, `mono`, `empty`, `toolbar`, `banner`, `pad-sm`, `active`, `console-detail`, `console-detail-head`, `console-list`, `console-run`, `run-name`, `result`, `.episodes-*`(1511-1523), 그리고 템플릿 문자열로 붙는 수식 클래스 `.chip.await` · `.chip.run`(`episodes.ts:29, 33` — 아래 grep 으로는 안 보인다). 새 클래스 `app-head` · `brand` · `app-main` 은 짧게 쓴다.

확인: `grep -o 'className="[^"]*"' -r ui/src/views/episodes | tr ' "' '\n\n' | sort -u` 의 모든 클래스가 `styles.css` 에 있는지(없는 것은 원본에서 가져온다).

- [ ] **Step 4: e2e 를 단일 화면에 맞춘다**
  - `warmup.setup.ts`: 기대 본문 `/Compose|Console|Koshei/i` → `/Koshchei|Episodes/i`.
  - `episodes.spec.ts`: 탭 버튼 `getByRole("button",{name:/episodes/i})` 로 들어가는 곳이 **두 군데**다 — `open()` 도움 함수(83-84)와 「a person-task notice opens its episode」 시험 안(477-478). 둘 다 `page.goto("/")` 뒤 `getByTestId("operator-name")` 를 기다리는 것으로 바꾼다. 헤더 단정 `headers["x-koshchei-operator"]` 는 Step 0 의 sed 로 바뀌었다.

- [ ] **Step 5: 시험**

```bash
cd "$K/ui" && npm install && npx tsc --noEmit && npx vitest run
```
Expected: tsc 깨끗, vitest **41** 개 통과.

```bash
cd "$K/ui" && npx playwright test --project=chromium --no-deps
```
Expected: **20** 개 통과(설치된 Chrome — 위 Step 0 참고). `npx playwright test` (warmup 포함)는 21개.

- [ ] **Step 6: 눈으로 한 번** — 개발 서버를 띄우고 브라우저 창에서 Episodes 화면이 koshei 때와 같은 모양인지 본다(목록 · 상세 · 운영자 이름 칸). 다른 점이 있으면 빠진 CSS 를 옮긴다.

- [ ] **Step 7: 커밋.**

---

## Chunk 4: 문서 · 원격 저장소 · 실물 한 번

### Task 7: 문서

**Files:**
- Create: `$K/README.md`, `$K/docs/usage.md` (Gemini 초안 + 대조)
- Create: `$K/docs/design/2026-09-27-episode-outer-loop-design.md` ← `$S/docs/superpowers/specs/…`
- Create: `$K/docs/plans/*` ← `$S/docs/superpowers/plans/` 의 에피소드 계획 14개(b1 … d-lite)와 이 계획
- Modify: 설계 문서 머리 블록

- [ ] **Step 1: 설계 문서와 계획을 옮기고 sed 를 적용한다.** 설계 문서 본문의 「koshei」 중 **시스템 자체**를 가리키는 말은 sed 가 건드리지 않으므로(소문자 단독 낱말) 그대로 둔다 — 역사 기록이다. 머리 블록만 새로 쓴다(Gemini, 한국어 두세 줄): 상태(구현됨 — A·B·C1·C2·D-lite·C3), 분리 사실(2026-10-05, koshei `6e66dbe`, PR #4·#5 에서 옮김, 이력은 koshei 에), 본문의 `koshei` 는 분리 전 이름이라는 한 줄.
- [ ] **Step 2: README** — Gemini brief 의 사실: 무엇인가(예외 하나를 증상부터 해소까지, 결정적 순수 코어 + Temporal 껍데기), 무엇과 함께 도나(picasso 판 4 승인 창구 · ResultExport 판 1, narrator 계약 0.6 `narrator-tq`), 모듈 넷과 UI, 시험 수(Task 2-6 의 실제 합), 빠른 시작(`docker compose up -d`, Temporal 7233, `./gradlew :host:run` 등), 출처(S2), 라이선스. koshei README 의 에피소드 절(100-126 · 157-170 · 180-190)을 사실 원천으로 쓴다.
- [ ] **Step 3: `docs/usage.md`** — koshei `docs/usage.md` §5(279-406)와 §4 표의 에피소드 행을 원천으로, 이름 대응표대로 바꾼 사실만. 환경변수 표는 16개 전부.
- [ ] **Step 4: 남은 상표** — `grep -rn -i koshei "$K" --exclude-dir=build --exclude-dir=node_modules --exclude-dir=replay --exclude-dir=docs` 가 비어야 한다. `docs/` 안은 출처 문장과 역사 서술만.
- [ ] **Step 5: 커밋.**

### Task 8: 원격 저장소

- [ ] **Step 1: 전체 시험 한 번**

Run: `cd "$K" && ./gradlew test` 그리고 `cd ui && npx vitest run && npx playwright test --project=chromium --no-deps`
Expected: Gradle 시험 **705** 개 안팎(323 + 311 + 29 + 42), 실패 0, 건너뜀 1. UI 41 + 20. 실제 합을 README 에 적은 수와 맞춘다.

- [ ] **Step 2: GitHub 비공개 저장소를 만들고 올린다** (사용자 승인을 받은 분리의 일부다)

```bash
cd "$K" && gh repo create LivingLikeKrillin/koshchei --private --source . --push
```

### Task 9: 실물 한 번 (Mock 창구)

목적: 새 이름으로 워커 · CLI · 제어면 · UI 가 실제 Temporal 과 Postgres 에서 이어지는지.

**공용 Temporal 에서 두 시스템이 서로 밟지 않게:** 워크플로 형 `EpisodeWorkflow` 와 `ep:` id 는 두 시스템이 같은 namespace 에서 같이 쓴다. `agent-off --all` 과 signal-with-start 는 koshei 쪽에 열려 있는 에피소드에도 닿을 수 있고, Mock narrator 는 공용 `narrator-tq` 를 폴링한다. 그래서 (1) 시작 전에 열린 `EpisodeWorkflow` 가 있는지 본다(Temporal UI 또는 `temporal workflow list --query "WorkflowType='EpisodeWorkflow' AND ExecutionStatus='Running'"`). 있으면 사용자에게 묻는다. (2) `narrator-tq` 를 다른 워커(koshei 워커 · narrator 실물 워커)가 폴링하지 않는지 본다(Temporal UI 의 task queue pollers). (3) `--key` 를 이번만의 값으로 준다(`smoke-koshchei-<날짜시각>`). (4) `agent-off --all` 은 쓰지 않는다.

- [ ] **Step 1:** `cd "$K" && docker compose up -d postgres` (koshchei 의 Postgres, 15433).
- [ ] **Step 2:** 워커: `KOSHCHEI_PICASSO=mock KOSHCHEI_NARRATOR=mock ./gradlew :host:run` (공용 Temporal 7233. koshei 워커가 같은 서버에 있어도 큐가 다르다 — `koshchei-episode-tq`).
- [ ] **Step 3:** 제어면: `./gradlew :api:run` → `curl -s http://127.0.0.1:18190/api/episodes` 가 `[]`.
- [ ] **Step 4:** CLI 로 하나 연다: `./gradlew :host:cli --args="open --export '<abs>/runtime/src/test/resources/picasso/run-1' --search <그 묶음의 searchId> --key smoke-koshchei-<날짜시각>"` → 제어면 목록에 `ep:` 하나, UI(`npm run dev`, 5174)에서 그 에피소드의 카드가 보인다.
- [ ] **Step 5:** 띄운 JVM 을 명령줄로 확인하고 PID 로 끈다. `docker compose stop postgres`.

---

## Chunk 5: koshei 에서 에피소드를 지운다

### Task 10: koshei 정리 (`$S`, 브랜치 `chore/remove-episode-loop`, main 에서)

**Files (조사 2026-10-05 §8):**
- Modify: `settings.gradle.kts` (`episode`, `episode-runtime` 빼기), `.gitattributes` (1-2 행)
- Delete: `episode/`, `episode-runtime/`, `model/episode-policy/`, `scripts/episode-db-roles.sql`, `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`, 에피소드 계획 14개
- `:app`: Modify `build.gradle.kts`(의존, `kosheiEnvKeys` 의 에피소드 11개, run 의 정책 기본값, `watcher` 작업, 쓰이지 않던 `jackson-kotlin`), `Worker.kt`(8-12, 71-95), `Cli.kt`(34-36, 41, 49-50, 54-96, 510-511). Delete `Watcher.kt`, `EpisodeCli.kt`, `EpisodeDbLogin.kt`, 시험 셋, `src/test/resources/picasso/`
- `:authoring-api`: Modify `build.gradle.kts`(`:episode-runtime` 의존), `AutoCorrectGate.kt`(에피소드 모드 제거 — 결정은 이 문서 머리), `AutoCorrectBean.kt:14,21`, `AutoCorrectController.kt:14-28`, `PublishController.kt:21-26`(주석), `AutoCorrectGateTest`. Delete `EpisodeConfig`, `EpisodeController`, `EpisodeGateway`, 시험 둘, `src/test/resources/picasso/`
- `:runtime`: `DataConverterSupport.kt` 에서 `RawJson` 과 `RawAwareJsonPayloadConverter` 감싸기만 지운다. **`JacksonJsonPayloadConverter(jacksonObjectMapper())` 덮어쓰기는 남긴다** — 사가 메시지(Kotlin data class)가 그것으로 풀린다. `RawJsonConverterTest` 는 RawJson 시험 둘만 지우고 Kotlin 변환 시험은 남긴다.
- 그 밖: `model/README.md:24`(정책 줄), `scripts/run-fsm-autocorrect-dispatch-gate.sh:28`(`export KOSHEI_PICASSO=off` 와 에피소드 모드 주석), `.gitattributes`(1-4행 — app · authoring-api 자원 디렉터리에도 `picasso/` 뿐이라 파일 전체가 빈다), `Worker.kt:23` 의 쓰이지 않게 되는 `import java.nio.file.Files`.
- UI: `App.tsx`(10, 12, 21, 61-66, 144), `styles.css`(1511-1523). Delete `src/views/episodes/`, `e2e/episodes.spec.ts`
- 문서(Gemini + 대조): `README.md`(21-25 Two parts · 65 시험 수 · 77 증거 행 · 98 모듈 목록 · 100-126 에피소드 절 · 157-170 빠른 시작 · 180-190 범위 · 198 문서 목록 → koshchei 를 가리키는 한 단락), `docs/architecture.md`(§1 의 에피소드 · 모듈 그림 · §12 → 가리키는 한 줄), `docs/usage.md`(§4 표의 에피소드 행, §5), `docs/reference-architecture.md`(193-202, 576)

- [ ] **Step 1:** 브랜치를 main 에서 만든다: `git switch -c chore/remove-episode-loop origin/main`.
- [ ] **Step 2:** 코드 삭제와 수정. `grep -rn 'koshei\.episode\|PicassoMode\|KOSHEI_PICASSO\|KOSHEI_EPISODE\|KOSHEI_NARRATOR\|KOSHEI_WATCH\|EpisodeStore\|X-Koshei-Operator\|episode-policy' --include=*.kt --include=*.kts --include=*.ts --include=*.tsx --include=*.sh --include=*.md . | grep -v plans/2026-10-05` 가 Step 5 의 문서 정리 대상 말고는 비어야 한다.
- [ ] **Step 3:** `AutoCorrectGate` 의 에피소드 검사를 빼면서 `AutoCorrectGateTest` 의 에피소드 시험(6개 중 해당분)을 지운다 — 남는 시험이 「드리프트 자동 조치 켜고 끄기」만 본다.
- [ ] **Step 4: 시험**

Run: `./gradlew test`
Expected: 실패 0. 시험 수는 1,248 − 631(코어 · 런타임) − 27(`:app`) − 41(제어면 둘) − 2(`RawJsonConverterTest` 의 RawJson 시험) − (AutoCorrectGateTest 에서 지운 수) 안팎. 실제 합을 문서에 적는다.

Run: `cd authoring-ui && npx tsc --noEmit && npx vitest run && npx playwright test --project=chromium --no-deps`
Expected: tsc 깨끗, vitest 42(83 − 41), Playwright 는 episodes 를 뺀 나머지.

- [ ] **Step 5:** 문서. 여기서 미뤄 둔 OT 문서 정리(환경변수 23개 표, 「OT 연결 없음」 문장, architecture 의 근거 없는 P5 · OQ1 · ADR-11, §2 · §9 도식의 `comp_ledger` 선, `init-db.sh` 주석)는 **별도 PR** 로 한다 — 이 PR 은 지우기만.
- [ ] **Step 6:** 커밋 · push · PR(main 으로). 이 계획 파일도 이 PR 에 넣는다(지금은 브랜치 `plan/koshchei-split` 에서 커밋되지 않은 채다). 병합은 사용자 확인 뒤.

---

## 완료 조건

- koshchei: `./gradlew test` 실패 0, UI tsc · vitest · Playwright 통과, 남은 상표 grep 이 출처 · 역사 서술뿐, GitHub 비공개 저장소에 올라감, 실물 한 번(Task 9) 통과.
- koshei: 에피소드 코드 · 문서 0, `./gradlew test` 와 UI 시험 통과, README 가 koshchei 를 가리킴, PR 병합.
- 이 계획 문서는 두 저장소에 남는다(koshchei `docs/plans/`, koshei `docs/superpowers/plans/`).
