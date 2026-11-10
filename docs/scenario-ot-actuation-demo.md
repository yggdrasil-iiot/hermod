# Hermod v0.5 — 운영자 내러티브: 라인 설비 레시피 적용 (OT Actuation Saga)

> **이 문서 = 시나리오 심층 워크스루.** 가치 요약은 **[`README.md`](../README.md)**, 시스템 아키텍처는 **[`docs/architecture.md`](architecture.md)** 를 보라.

> **목적:** 게이트(`scripts/run-scenario-gate.sh`)의 녹색 결과를 *가치 스토리*로 전환한다.
> 포트폴리오 리뷰어·운영자가 "Hermod가 실제로 무엇을 해결하는가"를 이해하기 위한 클릭-스루 내러티브.
>
> **모든 주장은 실제 게이트 어서트(A1–A6) 또는 명명된 선행 게이트에 근거한다.** 입증되지 않은 능력은 없다.
>
> **데모 GIF:** 아래 각 단계에 삽입된 GIF는 `scripts/run-e2e.sh`가 구동하는 Playwright E2E 스펙(`authoring-ui/e2e/`)이 실 브라우저에서 실행·통과하며 녹화한 영상이다. 테스트가 통과할 때만 GIF가 생성된다 — 스크린샷이 아닌 통과한 테스트의 실제 기록이다.

---

## 1. 시나리오 배경

### 왜 이 문제가 어려운가

제조 라인의 PLC(Programmable Logic Controller)에 공정 파라미터 "레시피"를 변경하는 행위는 **비가역(IRREVERSIBLE)**이다. 한 번 적용되면 되돌릴 수 없다. 그런데 그 이전 단계—라인 상태 읽기, 변경 계획 저장, 안전 인터록 통보—는 *실패할 수 있고*, 실패했을 때 이미 반쯤 기록된 상태가 그대로 남으면 다음 실행이 엉킨다.

일반적인 접근법은 두 가지 문제를 모두 개발자가 직접 처리하도록 강요한다:

- **비가역 작동 전에 모든 선행 단계가 완료됐음을 보장**하는 로직
- **선행 단계 중 실패 시 기록된 부분을 역순으로 정리**하는 보상(compensation) 로직
- **운영자가 최종 승인 전에 모든 확인을 볼 수 있도록** 하는 대기/승인 게이트

Hermod는 이 세 가지를 *블록 계약(Block Contract)* 수준에서 선언하게 하고, saga 런타임이 자동으로 집행한다.

### 두 페르소나: 한 번 선언, 영원히 안전

**블록 엔지니어**는 각 블록의 계약(멱등성, 보상 핸들러, 가역성 여부, 위험 분류)을 한 번 작성한다. 이 rigor는 블록 jar에 봉인된다. 엔지니어가 설정한 `sideEffects: irreversible`은 컴파일러가 E1 린트로 강제한다—IRREVERSIBLE 블록이 보상 가능 블록보다 *앞*에 오면 컴파일 자체가 거부된다.

**운영자**는 Compose 캔버스에서 블록을 드래그해 와이어링한다. 멱등성이나 보상 메커니즘을 알 필요 없다. 운영자가 선택 가능한 블록은 이미 E1을 통과하고 팔레트에 노출된 것뿐이므로, **잘못된 순서로 PLC 작동을 배치할 방법이 없다.** 이것이 green-zone이다.

---

## 2. 앵커 워크플로: `ot-recipe-apply`

### 5단계 선형 체인

```
sensorRead   (db.read)        현재 라인 상태/센서 판독 → source_rows
     │
     ▼
recordPlan   (db.upsert)      변경 계획 durable 기록          [보상가능 #1]  → target_rows
     │                        실패 시: INSERT 된 행 DELETE
     ▼
interlockAck (notify.email)   MES 안전 인터록 확인 통보       [보상가능 #2]
     │                        실패 시: 통보 취소 이벤트 발행
     ▼
preflight    (transform.map)  actuation 직전 사전 안전 점검   [reversible — 부작용 없음]
     │                        실패 시: 직전 두 단계 역순 보상
     ▼
applyPLC     (actuate)        PLC 레시피 적용                 [IRREVERSIBLE + 운영자 승인 WAIT 게이트]
```

### 각 노드의 도메인 의미

| 노드 id | 블록 타입 | 도메인 의미 | 속성 |
|---|---|---|---|
| `sensorRead` | `db.read` | 현재 라인 센서 상태 읽기 | 읽기 전용, 보상 불필요 |
| `recordPlan` | `db.upsert` | 변경 계획을 `target_rows`에 durable 기록 | **보상가능**: 실패 시 INSERT된 행 DELETE |
| `interlockAck` | `notify.email` | MES 안전 인터록 시스템에 확인 통보 발송 | **보상가능**: 취소 이벤트 발행 |
| `preflight` | `transform.map` | actuation 직전 최종 안전 점검 | reversible (부작용 없음) |
| `applyPLC` | `actuate` | PLC에 레시피 파라미터 실제 적용 | **IRREVERSIBLE + 운영자 승인 필수** |

### 선형 체인을 선택한 이유

앵커는 의도적으로 **선형(linear)**이다. 목적은 실패 표면(failure surface)에 집중하는 것이기 때문이다. 캔버스와 런타임은 DAG fan-out/fan-in을 완전히 지원한다—이는 `run-dag-gate.sh`(다이아몬드 5노드, 두 엔진)와 `run-concurrency-gate.sh`(Temporal 브랜치-병렬 벽시계 중첩)로 별도 검증됐다. 선형 체인이 실패 경로를 더 명확하게 보여주므로 이 데모에서는 선형을 선택했다.

---

## 3. 운영자 클릭-스루

### 단계 1: 팔레트 → 캔버스 와이어링 → 라이브 검증

**UI 동작:**
1. `:authoring-api`(포트 18090) 기동 후 브라우저에서 `authoring-ui` dev 서버 접속
2. **Compose 탭** 진입 → 좌측 팔레트에서 `db.read`, `db.upsert`, `notify.email`, `transform.map`, `actuate` 카드를 캔버스로 드래그
3. 포트 간 엣지를 연결해 선형 체인 구성: `sensorRead → recordPlan → interlockAck → preflight → applyPLC`
4. 캔버스가 디바운스 `POST /api/workflows/validate`를 자동 호출 → 라이브 검증 피드백 표시

**차별점:** 블록 엔지니어가 이미 봉인한 계약 위에서 운영자는 그냥 와이어를 연결한다. 타입 불일치나 사이클이 생기면 즉시 시각적 에러가 뜬다. E1 린트(`actuate`가 보상 가능 노드보다 앞에 오는 순서 오류)는 컴파일 레벨에서 거부되므로 운영자가 "잘못된 순서"를 저장하는 것 자체가 불가능하다.

**근거:**
- `run-compose-run-gate.sh` assert 1: `POST /api/workflows/validate`로 cycle/unwired→`valid:false`, diamond→`valid:true`
- v0.3f `run-node-states-gate.sh`: React Flow 캔버스 `graph.ts` round-trip `npm test` + `npm run build`
- `run-dag-gate.sh`(5-assert): 동일 캔버스가 DAG fan-out/fan-in도 지원함을 별도 검증
- `run-compiler-ir-gate.sh` assert 6: `bad-order.yaml` → `irreversible-ordering` CompileException

![저작 — 팔레트에서 블록을 캔버스로 드래그](demo/01-compose.gif)

---

### 단계 2: Save = Deploy, 워커 무재기동 poll-bind

**UI 동작:**
- **Save 버튼** 클릭 → `POST /api/workflows?version=1.0.0`
- 워커는 이미 실행 중이지만 **재기동 없이** 백그라운드 poll(기본 3s 주기)로 신규 워크플로를 자동 바인드
- 워커 로그: `[worker] bound workflow ot-recipe-apply@1.0.0` — 이 로그를 게이트가 직접 grep해서 확인

**차별점:** 운영자가 새 워크플로를 저장해도 long-lived 워커를 내렸다 올릴 필요가 없다. 프로덕션 라인을 멈추지 않고 새 레시피 프로세스를 배포한다.

**근거:**
- `run-compose-run-gate.sh` assert 2/3: `(name,version)` 불변 저장 + 워커 재기동 없이 poll-bind → `completed:true`
- v0.3e: `:authoring-api` 컨트롤 평면 + LIVE 워커 poll-bind

---

### 단계 3: Run → per-node 점등 → `applyPLC` 승인 게이트

**UI 동작:**
- **Run 버튼** → `POST /api/workflows/ot-recipe-apply/1.0.0/run`
- 캔버스의 각 노드가 순서대로 점등됨: `sensorRead` → `recordPlan` → `interlockAck` → `preflight` 모두 **DONE**으로 점등
- `applyPLC` 노드가 **RUNNING** 상태(황색)로 정지 — 승인 대기 게이트
- Console 탭의 run-status도 `RUNNING` 표시 (PARKED가 아닌 RUNNING — 파킹은 `interactive` 모드에서 예외가 던져질 때만 발생)
- **Approve 버튼** → `POST /api/runs/{runId}/approve` → 완료: `completed:true`, `target_rows=2`

**실제 노드 상태 (승인 게이트 대기 시점):**
```
sensorRead   : DONE
recordPlan   : DONE
interlockAck : DONE
preflight    : DONE
applyPLC     : RUNNING  ← 운영자 승인 대기
```

**차별점:** PLC 작동 직전에 실제 사람의 판단이 들어간다. 사전 단계가 하나라도 실패했다면 이 게이트에 도달하지 않는다—saga가 자동으로 역순 보상하고 PLC는 건드리지 않는다.

**근거 (A1 — Temporal happy path):**
- `run-scenario-gate.sh` A1: `applyPLC` RUNNING 감지 → approve → `completed:true`, `target_rows!=0`, `applyPLC` DONE 확인
- v0.3f `run-node-states-gate.sh`: per-node 실행 라이팅 검증

![실행 → per-node 점등 → 게이트 승인](demo/02-run.gif)

---

### 단계 4: 실패 경로 — preflight FAILED + 역순 보상 (PLC 미작동)

**UI 동작:**
- `failAtBlockId: "transform.map"` 파라미터로 재실행 (게이트에서는 `fault_inject` 테이블로 무장)
- 캔버스: `sensorRead`/`recordPlan`/`interlockAck` DONE → `preflight` **FAILED**(적색)
- `interlockAck`(notify.email)이 **COMPENSATED**(회색)로 역점등 → `recordPlan`(db.upsert)이 **COMPENSATED**로 역점등
- `applyPLC`는 **절대 DONE이 아님** — PLC에 신호가 간 적 없음
- Console 탭 → **보상 타임라인 패널**: 역순 보상 이벤트가 시간순으로 나열됨

**A3 실행 시 `/api/runs/{id}/compensation`의 실제 응답 (게이트는 `index`·`blockId`·`outcome`를 검증; `nodeId`는 가독성을 위해 표시):**
```json
[
  { "index": 0, "nodeId": "interlockAck", "blockId": "notify.email",  "outcome": "COMPENSATED" },
  { "index": 1, "nodeId": "recordPlan",   "blockId": "db.upsert",     "outcome": "COMPENSATED" }
]
```

`index: 0`이 마지막으로 완료된 노드(`interlockAck`, 위상 역순으로 첫 번째 보상 대상)이고, `index: 1`이 그 이전 노드(`recordPlan`)다. **`target_rows = 0`**—db.upsert 보상이 INSERT된 행을 삭제했음을 DB에서 직접 확인.

**핵심:** `preflight`가 실패하는 순간 saga는 자동으로 이미 완료된 보상 가능 노드를 역순으로 되감는다. 운영자가 아무것도 하지 않아도 된다. PLC는 절대 건드리지 않는다.

**근거 (A3 — Temporal forward-fail):**
- `run-scenario-gate.sh` A3: `compensation` 타임라인 JSON 검증 (index:0 = notify.email COMPENSATED, index:1 = db.upsert COMPENSATED), `target_rows=0`, `applyPLC` DONE 아님 확인
- v0.4c `run-compensation-timeline-gate.sh`: 역사적 보상 타임라인 `GET /api/runs/{id}/compensation` 엔드포인트

![preflight 실패 → 역순 보상 타임라인, PLC 미작동](demo/03-failsafe.gif)

---

### 단계 5: 개입(Intervention) — PARKED → Retry / Abort

**UI 동작:**
- `interactive: true` 옵션으로 실행 + `fault_inject` 테이블에 `transform.map` 무장
- `preflight`가 예외를 던지면 노드 상태 **PARKED** → run-status는 여전히 **RUNNING** (보상이 시작되지 않음)
- Console 탭 `RunDetail`: **Retry** / **Abort** 버튼 표시

**Retry 경로:**
1. 운영자가 근본 원인 해소 (게이트에서는 `fault_inject`에서 `transform.map` 행 삭제)
2. **Retry 버튼** → `POST /api/runs/{runId}/retry` `{ "nodeId": "preflight" }`
3. `preflight` → DONE → `applyPLC` 승인 게이트로 진행 → approve → `completed:true`

**Abort 경로 (게이트 외, 내러티브 참조):**
- **Abort 버튼** → `POST /api/runs/{runId}/abort` → 역순 보상 시작 → `completed:false`

**차별점:** 블록이 일시적 이유(네트워크 지연, 외부 시스템 일시 불능)로 실패했을 때 전체 saga를 버리지 않아도 된다. 운영자가 원인을 해소하고 해당 노드만 다시 실행하면 된다. 이 능력은 `preflight` 노드가 PARKED 상태에서도 run이 RUNNING을 유지하므로 보상이 조기에 시작되지 않는다.

**근거 (A5 — Temporal intervention):**
- `run-scenario-gate.sh` A5: fault arm → preflight PARKED, run-status RUNNING 확인 → fault 해제 → retry → preflight DONE → approve → `completed:true`
- v0.4b `run-intervention-gate.sh`: park/retry-recovers/abort-compensates/non-interactive 회귀

![park → retry → 복구 → 승인](demo/04-intervene.gif)

---

### 단계 6: Best-Effort 보상 — 실패한 보상이 `COMP_FAILED`로 표면화

**UI 동작:**
- 정상 실행으로 게이트까지 진행 (`interlockAck` DONE 확인)
- 보상 단계에서 `notify.email`에 compensate 결함 무장 (`fault_inject` phase=compensate)
- **Reject 버튼** → `POST /api/runs/{runId}/reject` → 역순 보상 시작
- `interlockAck`(notify.email) 보상 시도 → **실패** → 노드 상태 `COMP_FAILED`(주황색)
- `recordPlan`(db.upsert) 보상은 **계속 진행** → `COMPENSATED`
- Console 탭 보상 타임라인 패널에 모든 것이 기록됨

**A6 실행 시 `/api/runs/{id}/compensation`의 실제 응답 (게이트는 `index`·`blockId`·`outcome`를 검증; `nodeId`는 가독성을 위해 표시):**
```json
[
  { "index": 0, "nodeId": "interlockAck", "blockId": "notify.email", "outcome": "FAILED"      },
  { "index": 1, "nodeId": "recordPlan",   "blockId": "db.upsert",    "outcome": "COMPENSATED" }
]
```

`interlockAck`의 보상이 실패했지만 saga는 멈추지 않고 `recordPlan`의 보상을 완료했다. **잔여물(residue)을 숨기지 않는다.** 운영자는 `COMP_FAILED` 노드를 보고 수동 조치가 필요함을 알 수 있다.

**차별점:** 보상 실패를 조용히 삼키지 않는다. "최선(best-effort)"은 "완벽하게 롤백됐다는 거짓말"이 아니라 "할 수 있는 것은 다 했고 실패한 것은 명확히 표면화한다"는 의미다.

**근거 (A6 — Temporal best-effort):**
- `run-scenario-gate.sh` A6: compensate 결함 arm → reject → `interlockAck` COMP_FAILED, `recordPlan` COMPENSATED 타임라인 확인
- v0.4c `run-compensation-timeline-gate.sh`: best-effort 보상 실패 기록 + 계속 진행

---

### 단계 7: 엔진 중립 — 동일 계약이 Conductor에서도, 같은 콘솔에서

Hermod의 핵심 주장은 "하나의 계약(IR)이 두 엔진으로 컴파일·실행된다"는 것이다. 바꾼 건 실행 요청의 `engine: "conductor"` 한 줄뿐 — 워크플로 정의는 그대로다.

![엔진 중립 — 같은 ot-recipe-apply를 Conductor로: 실패하면 같은 역순 보상, PLC는 미작동, 운영자는 전체 run 재시도로 복구](demo/05-engine-neutral.gif)

위 데모는 **Conductor 엔진**으로 실행한 동일한 `ot-recipe-apply`다. v0.6a–d로 **Console이 Conductor를 Temporal과 동등하게 관측**한다 — per-node 라이팅, 보상 타임라인, 운영자 개입(retry/abort)까지. 화면처럼: preflight 실패 → 상류 `[interlockAck, recordPlan]` 역순 보상 → applyPLC(비가역) 미작동 → 운영자가 **전체 run 재시도**(Conductor는 노드별이 아닌 전체 재실행) → 승인 후 완료.

객관 증명(코드로 닫는 부분)은 게이트가 담당한다:
- **A2 (Conductor happy path):** 동일 `ot-recipe-apply` → WAIT task approve → COMPLETED, `target_rows != 0`
- **A4 (Conductor forward-fail):** 동일 `failAt=transform.map` → comp_ledger 보상 기록 + `target_rows = 0`

두 엔진이 같은 계약에서 같은 결과·같은 안전·같은 콘솔을 낸다. 운영자는 엔진을 바꿔도 워크플로를 다시 쓰지 않는다.

**근거 (A2, A4 — Conductor engine neutrality):**
- `run-scenario-gate.sh` A2: Conductor approve → COMPLETED + `target_rows != 0`
- `run-scenario-gate.sh` A4: Conductor `failAt=transform.map` → comp_ledger 보상 기록 + `target_rows = 0`
- `run-conductor-exec-gate.sh`(4-assert): 선행 검증 — Conductor 순방향 실행·인간 게이트·거절 보상·중간 실패 보상
- `run-compiler-ir-gate.sh`(6-assert): 엔진 중립 컴파일 결정성 + Conductor emit round-trip

---

## 4. 재현 방법

### 전제 조건

```bash
# Docker 스택 기동 (Postgres 15432, Temporal 7233, Conductor 18088)
docker compose up -d

# v0.1 스키마 적용
bash scripts/init-db.sh
```

### 게이트 실행 (전체 자동화)

```bash
bash scripts/run-scenario-gate.sh
# 기대 출력:
# [GATE] PASS run-scenario-gate.sh
```

게이트는 다음을 자동으로 수행한다:
1. `fault_inject` 테이블 생성 + `source_rows` 시드
2. Gradle 빌드 (`:app:build`, `:authoring-api:build`)
3. worker + API 기동 (재기동 없이 `ot-recipe-apply@1.0.0` poll-bind 확인)
4. **A1** (Temporal happy path) + **A3** (Temporal forward-fail) + **A5** (Temporal intervention) + **A6** (Temporal best-effort)
5. Temporal JVM 종료 후 Conductor worker 기동
6. **A2** (Conductor happy path) + **A4** (Conductor forward-fail)

### UI 직접 확인 (선택)

```bash
# 1. Temporal worker 기동
HERMOD_FAULT_INJECT=1 ./gradlew :app:run &

# 2. authoring-api 기동 (포트 18090)
./gradlew :authoring-api:run &

# 3. UI dev 서버 기동
cd authoring-ui && npm run dev
# → http://localhost:5173 접속, Compose 탭에서 ot-recipe-apply 워크플로 직접 구성·실행
```

---

## 5. 정직한 한계

### Console은 양 엔진 관측 — (구) "UI는 Temporal 전용"은 v0.6에서 해소됨

(이 시나리오 문서의 초판 한계였던 "UI는 Temporal 전용"은 **v0.6a–d에서 해소됐다.**) Console 탭은 이제 Temporal과 **Conductor를 동등하게** 관측한다 — 혼합 run 목록(엔진 태그), per-node 라이팅, 보상 타임라인, 운영자 개입(retry/abort)까지. Temporal은 `SagaWorkflow`의 `@QueryMethod`(`queryNodeStates`/`queryCompensationTimeline`)로, Conductor는 `:authoring-api` 엣지의 `ConductorEnginePort`(워크플로 태스크 관측 + `comp_ledger`)로 같은 `EnginePort` 심을 채운다. 단계 7의 `05-engine-neutral` 데모가 이를 시연한다.

**엔진 중립성은 UI 시연에 더해, 게이트의 forward+보상 양 엔진 어서트(A2/A4)로 객관 증명한다.**

(잔여 한계: Conductor의 노드 상태는 eventually-consistent 인덱스라 콘솔 폴링이 수렴하며 보이고, retry는 Conductor 모델상 **노드별이 아닌 전체 run 재실행**이다 — 단계 7의 캡션에 명시.)

### Best-effort live query — retention 이후 빈 응답

보상 타임라인과 노드 상태는 Temporal의 workflow execution에 라이브 쿼리한다. Temporal retention(기본 72시간) 이후 종료된 run에 대해 `queryNodeStates()`는 빈 응답을 반환할 수 있다. 이는 알려진 한계이며 status writeback 영속화는 향후 개선 대상이다.

### E2E 브라우저 테스트 — v0.5b에서 도입 (한계 해소)

v0.5b 이전의 UI 검증은 `npm run build` (tsc + vite) + `graph.ts` round-trip `npm test` + 게이트 백엔드로만 이루어졌다. 실제 브라우저에서 캔버스를 클릭하는 E2E 테스트는 없었다.

**v0.5b에서 해소:** `authoring-ui/e2e/`에 Playwright E2E 스펙 4개를 추가해 이 내러티브의 운영자 여정을 실 브라우저에서 자동 실행·검증하고 GIF로 기록한다. `scripts/run-e2e.sh`로 구동. 위에 삽입된 GIF 4개가 그 결과물이다.

**여전히 정직한 한계:** E2E는 로컬/온디맨드 실행이며 CI에 통합되지 않았다. 저작(authoring) 스펙은 블록 노드 배치(synthetic drop)만 커버하며 포트 와이어링·저장(save) 흐름은 포함하지 않는다. 노드 상태 라이팅·보상 타임라인·게이트 승인은 Temporal 경로에서만 검증된다.

### fault 훅은 test-only / env-gated

`fault_inject` 테이블과 `HERMOD_FAULT_INJECT=1` 환경 변수는 테스트 전용이다. 프로덕션 워커에서는 이 분기에 진입하지 않는다(`isFaultArmed` 체크가 `HERMOD_FAULT_INJECT=1`일 때만 활성화).

### IRREVERSIBLE 작동은 진짜로 되돌릴 수 없다

`applyPLC`(actuate)가 한 번 실행되면 PLC에 적용된 레시피는 되돌릴 수 없다. 이 데모의 가치는 그 지점 **이전**의 어떤 실패도 PLC를 건드리지 않고 깨끗하게 롤백된다는 것이다. A3 어서트가 이를 직접 증명한다: `preflight` 실패 시 `applyPLC`는 절대 DONE 상태가 아니며 DB에서 `target_rows = 0`이 확인된다.

### axis-2(운영자 안전 구성 가설)는 설득력 있게 시연하나 코드로 닫을 수 없다

Hermod 설계 §11의 axis-2는 "운영자가 실제로 안전하게 saga를 구성할 수 있는가"를 묻는다. 이 데모는 그 여정을 설득력 있게 **시연**하나, "실제 운영자가 이 UI를 사용했을 때 안전하다고 느끼는가"는 코드가 닫을 수 없는 가설이다. 최종 검증은 사용성 세션으로만 가능하다.

---

*이 문서는 `scripts/run-scenario-gate.sh`의 A1–A6 어서트와 v0.3a–v0.4c 선행 게이트에 근거한다. 게이트가 통과하지 못하는 능력은 어디에도 주장되지 않았다.*
