# Koshei — Architecture (as-built)

## 1. Purpose and the hard problem

The repository contains two parts. The first is the **saga platform**, which composes and runs
contract-driven durable sagas on Temporal or Conductor for transactional OT/IT integration. In
these workflows, the final step is often an irreversible side-effect, such as writing a recipe to a
PLC, and the people composing the canvas are operators who do not write code. The hard problem is
ensuring that every step dragged onto the canvas guarantees idempotent re-execution after a crash,
ordered compensation of committed work when a later step fails, bounded retry, and a human approval
gate before anything irreversible, all without the author writing orchestration code.

The second part is the **episode outer loop**, which manages a single fault from the first symptom
to resolution as a deterministic state machine on Temporal only. Its hard problem is safe operational
remediation without direct robot actuation: the loop never commands a robot itself. It sends a
reference to an already computed proposal to picasso's approval window (`POST /approvals`), and
picasso is the only command source. A person approves remedies while policy v1 keeps auto-approval
off. The LLM diagnosis (the narrator service, a separate Python worker) is a side-effect-free
activity called from one state, no remedy dispatches without a recorded dispatch intent, and nothing
executes while a precondition or outcome is UNKNOWN.

The episode loop is disabled by default (`KOSHEI_PICASSO=off`) and runs as a single-machine proof of
concept. When enabled, it shares the koshei worker process, the `:authoring-api` control plane, and
Postgres with the saga platform, though an episode does not start a saga today: saga remedies and
child sagas are out of scope (§12.6). The saga platform is described in §2–§11 with limits in §10;
the episode outer loop is described in §12 with limits in §12.6.

## 2. System overview

The authoring UI (a React app) projects the block registry into a palette and lets an operator
compose a workflow on a canvas. That definition is sent to the `:authoring-api` control plane, which
**compiles** it to an engine-neutral IR and lowers it to a concrete engine: Temporal (via
`TemporalBackend` in `:runtime`) or Conductor (via `ConductorBackend` in `:compiler`). Workers
execute the block handlers; durable state lives in Postgres. The F5 operator console observes runs
on both engines through one `EnginePort` seam. The diagram below covers the saga platform; §12.2
lists the episode loop's processes and queues.

```mermaid
flowchart LR
  subgraph UI["authoring-ui (React)"]
    canvas["Compose canvas + palette"]
    console["F5 console (RunList / RunDetail)"]
  end
  api[":authoring-api control plane"]
  subgraph compile["compile pipeline"]
    ir["WorkflowIR (:compiler)"]
    tb["TemporalBackend (:runtime)"]
    cb["ConductorBackend (:compiler)"]
  end
  subgraph engines["durable engines"]
    temporal["Temporal"]
    conductor["Conductor"]
  end
  pg[("Postgres<br/>comp_ledger")]

  canvas --> api
  api --> ir
  ir --> tb --> temporal
  ir --> cb --> conductor
  temporal --> pg
  conductor --> pg
  console -->|EnginePort| api
  api -->|TemporalEnginePort| temporal
  api -->|ConductorEnginePort| conductor
```

## 3. The keystone — block contract drives derived resilience

Every block ships a declarative `BlockContract` (`core/src/main/kotlin/koshei/core/Contract.kt`)
loaded from a YAML manifest. The runtime derives all resilience behaviour generically from these
fields, with no per-workflow orchestration code. The mapping is:

| Contract field | Enum / shape | Derived behaviour | Where consumed |
| --- | --- | --- | --- |
| `idempotency` | `IdempotencyStrategy{NONE,KEY_DEDUP,UPSERT,NATURAL}` + `keyExpression` | dedup of input rows before the forward call | `SagaWorkflowImpl.run` (KEY_DEDUP branch) |
| `compensation` | `Reversibility{REVERSIBLE,MITIGATABLE,IRREVERSIBLE}` + `CompensationKind{STATIC,CONTEXTUAL,NONE}` | which completed nodes are eligible for compensation | `SagaWorkflowImpl` / `CompensationOrder` |
| `retry` | `RetrySpec(maxAttempts, initialMs, maxMs)` | Temporal `RetryOptions`; Conductor `retryCount` | `SagaWorkflowImpl`, `ConductorBackend.emitTaskDefs` |
| `human` | `HumanSpec(requireApprovalBefore)` | park before the step for operator approve/reject | `SagaWorkflowImpl` gate; Conductor `WAIT` task |
| `compensation.reversibility == IRREVERSIBLE` | — | also forces a human gate (cannot undo, so confirm first) | `SagaWorkflowImpl`; `ConductorBackend.needsWait` |

The `actuate` manifest (`blocks/src/main/resources/manifests/actuate.yaml`) is the canonical example:
`compensation.reversibility: IRREVERSIBLE`, `kind: NONE`, `human.requireApprovalBefore: true`,
`retry.maxAttempts: 1`, `sideEffects: [ACTUATION]`. The author's workflow never asks for approval;
the contract does, and both engines honour it: Temporal via the
`Workflow.await { approved || rejected || aborted }` gate, and Conductor by compiling that node into
a `WAIT` task (`ConductorBackend.needsWait` → `taskFor`, type `WAIT`, 24h taskdef timeout).

## 4. Engine neutrality — compile once, run on two engines (the headline)

The control plane compiles an operator's workflow **once** into an engine-neutral `WorkflowIR`
(`compiler/src/main/kotlin/koshei/compiler/Ir.kt`, produced by `WorkflowCompiler.compile`), then a
backend lowers that single IR to a concrete engine. The contract is the carrier: each `IrNode`
holds the resolved, version-pinned `BlockContract`, so both backends read the same resilience facts.

```mermaid
flowchart TB
  def["WorkflowDef (operator authored)"]
  comp["WorkflowCompiler.compile (:compiler)"]
  ir["WorkflowIR — engine-neutral, topo-ordered (:compiler)"]
  def --> comp --> ir

  ir --> cb["ConductorBackend.emit (:compiler)<br/>JSON workflow def + taskdefs"]
  ir --> tb["TemporalBackend.lower (:runtime)<br/>ordered List<BlockContract>"]

  cb --> cport["ConductorEnginePort (:authoring-api)"]
  tb --> tport["TemporalEnginePort (:runtime)"]

  cport --> port["EnginePort interface (:runtime)"]
  tport --> port
  port --> console["F5 console observes BOTH"]
```

The module placement across compiler and runtime boundaries is deliberately **asymmetric**:

- **Compiler and IR**: The IR and `WorkflowCompiler` live in `:compiler`.
- **Conductor backend**: `ConductorBackend` lives in `:compiler` as pure data (Jackson only, no
  Conductor runtime), emitting a Conductor workflow-definition JSON. It self-validates by
  round-tripping the output (`ConductorBackend.validate`), so the server is not required to compile.
- **Temporal backend**: `TemporalBackend` lives in `:runtime` (not `:compiler`), because lowering to
  Temporal is trivial: the saga is contract-driven, so it projects the IR's per-node contracts in
  order (`TemporalBackend.lower` = `ir.nodes.map { it.contract }`).
- **Engine ports**: The `EnginePort` interface lives in `:runtime`. `TemporalEnginePort` is in
  `:runtime`; `ConductorEnginePort` is in the `:authoring-api` edge, so that `:conductor-runtime`
  itself never depends on Temporal or on `koshei.runtime`.

**Boundary invariants** (verified, see §8): Measured on 2026-10-04, `:conductor-runtime/src/main`
contains 0 `io.temporal` imports and 0 `koshei.runtime` imports. Spring Boot is a dependency of
`:authoring-api` only; `:app` is a plain Kotlin application with no Spring dependency. Sources in
`:core / :registry / :compiler / :dispatch` contain 0 `org.springframework` imports, and assert 7 of
`run-authoring-gate.sh` checks that the `:core`, `:registry`, `:compiler` and `:dispatch` jars carry
no `org.springframework`.

**Engineering note (R-1).** Conductor abort does not rely on the engine's failureWorkflow dispatch.
On conductor-standalone 3.15.0, `terminateWorkflowWithFailure(id, reason,
triggerFailureWorkflow=true)` terminates the run but does not dispatch the registered
failureWorkflow, leaving `comp_ledger` uncompensated. `ConductorStarter.abortWithCompensation`
therefore self-dispatches the `<name>-compensation` workflow with the `{workflowId: <id>}` input
Conductor would inject on failure; its compensate tasks then read the forward run's ledger entries
and unwind them. This behaviour is documented in code and proven by
`run-conductor-retry-abort-gate.sh`.

## 5. Durable saga execution

`SagaWorkflowImpl` (`runtime/.../SagaWorkflowImpl.kt`) implements the contract-driven Temporal saga:

- **Promise-graph concurrency.** It builds a `Promise<BlockOutput>` per node over the topo-ordered
  IR; each node awaits its upstream promises, so independent branches run concurrently.
- **Settle-all, never fail-fast.** On failure it settles every branch first (`Promise.handle` +
  `Promise.allOf`, the sdk-java #902 non-determinism mitigation) before compensating, so partial
  parallel failure is handled deterministically.
- **Reverse-topological compensation.** As nodes complete, `SagaWorkflowImpl` records the compensable
  ones (the eligibility predicate `reversibility != IRREVERSIBLE && kind != NONE`); on failure,
  `CompensationOrder.reverseTopological` walks the topo list in reverse over that
  completed-and-compensable set to produce the unwind order. Compensation is **best-effort**: a
  failing compensate step records `COMP_FAILED` and continues unwinding the rest.
- **Human gate before irreversible.** When a node contract has `requireApprovalBefore` or is
  IRREVERSIBLE and the run is not auto-approved, the node is set to `AWAITING_APPROVAL` while the
  workflow waits for approve, reject or abort. The gate is evaluated once, outside the operator
  retry ("park") loop (where a failed step on an interactive run enters `PARKED` to await retry or
  abort), so one approval covers every forward retry. Reject or abort at the gate marks the node
  `FAILED` and is terminal: it propagates into settle-all, then compensation.
- **Crash recovery.** `scripts/run-crash-recovery.sh` kills the worker mid-flight; Temporal replays
  the in-flight activity on restart and the run completes.
- **Conductor saga state.** Conductor's failureWorkflow cannot carry in-memory saga state, so
  `CompLedger` (`conductor-runtime/.../CompLedger.kt`) externalizes it in Postgres, keyed by
  **PK `(workflow_id, node_id)`** — exactly one row per compensable node per run, so parallel
  branches sharing a `blockId` never collide. The compensation timeline is read back from this
  ledger ordered by reverse-topo `idx`.

The canonical scenario `ot-recipe-apply` (`scripts/fixtures/compose/ot-recipe-apply.json`) exercises
this end to end: `sensorRead`(db.read) → `recordPlan`(db.upsert) → `interlockAck`(notify.email) →
`preflight`(transform.map) → `applyPLC`(actuate, IRREVERSIBLE + gate).

The **run lifecycle** traces execution from the operator's compose action through compile, deploy,
start, step failure, reverse-topological compensation, and observation in the F5 console:

```mermaid
sequenceDiagram
  actor Op as Operator
  participant UI as Compose canvas + F5 console<br/>(authoring-ui)
  participant API as :authoring-api<br/>(control plane)
  participant Eng as Durable engine<br/>(Temporal / Conductor)
  participant Wk as Workers<br/>(shared task queue)

  Op->>UI: compose workflow on canvas
  UI->>API: deploy (WorkflowDef)
  API->>API: compile to engine-neutral IR<br/>(WorkflowCompiler.compile)
  API->>Eng: register def + start run
  Eng->>Wk: dispatch ready steps
  Wk-->>Eng: step DONE (records compensable nodes)
  Note over Eng,Wk: a later step FAILS
  Wk-->>Eng: step FAILED
  Eng->>Eng: settle-all, then reverse-topo compensation
  Eng->>Wk: compensate completed nodes (reverse order)
  Wk-->>Eng: COMPENSATED (or COMP_FAILED, best-effort)
  Op->>UI: open run
  UI->>API: poll run state
  API->>Eng: query node states + comp ledger
  Eng-->>API: per-node states + compensation timeline
  API-->>UI: render lighting + timeline
```

![On failure: reverse-order compensation; the irreversible PLC never fires](demo/03-failsafe.gif)

*When a step fails, completed reversible work unwinds in reverse-topological order and the IRREVERSIBLE PLC actuation never fires, as enforced by contract.*

## 6. Operator surfaces

- **Authoring UI palette** projects the block registry so operators see the registered,
  version-pinned blocks; manifest `label`/`help`/`widget` fields drive the field UI.
- **Compose canvas** is built on React Flow; the library supplies graph mechanics, and the visual
  design comes from the project's own design system.
- **F5 console** (`authoring-ui/src/views/console/{ConsoleView,RunList,RunDetail}.tsx`) shows run
  history, per-node lighting, the compensation timeline, and interventions ("Approve", "Reject",
  "Abort"). It is engine-tagged for both engines: `RunList` displays an engine chip, and `RunDetail`
  branches the retry affordance. Temporal offers a per-node "Retry" on a parked node; Conductor has
  no parked-node primitive, so it offers "Retry (whole run)". Conductor retry and
  abort are implemented in `ConductorEnginePort.signalRetry/signalAbort`: `signalRetry` ignores the
  node id, clears the run's compensation ledger, and re-runs the workflow from the start, while
  `signalAbort` calls `abortWithCompensation`.

Console lighting is driven by a fixed **per-node state set**: the eight-value `NodeState` union in
`authoring-ui/src/types.ts` (`PENDING | RUNNING | DONE | FAILED | COMPENSATED | PARKED |
COMP_FAILED | AWAITING_APPROVAL`), rendered as `ns-*` CSS classes in
`authoring-ui/src/views/compose/BlockNode.tsx`. Node states are derived engine-side by
`SagaWorkflowImpl` for Temporal and by `ConductorNodeStates.kt` for Conductor. The node lifecycle is:

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> AWAITING_APPROVAL: human gate<br/>(both engines)
  AWAITING_APPROVAL --> RUNNING: operator approve<br/>(Temporal)
  AWAITING_APPROVAL --> DONE: operator approve<br/>(Conductor WAIT completes)
  AWAITING_APPROVAL --> FAILED: operator reject / abort
  PENDING --> RUNNING: dispatched to a worker
  RUNNING --> DONE: forward step succeeds
  RUNNING --> FAILED: forward step fails
  RUNNING --> PARKED: forward step fails<br/>(Temporal interactive run)
  PARKED --> RUNNING: operator retry
  PARKED --> FAILED: operator abort<br/>(upstream unwinds)
  DONE --> COMPENSATED: later failure → reverse-topo unwind
  DONE --> COMP_FAILED: compensate step itself fails
  FAILED --> [*]
  DONE --> [*]
  COMPENSATED --> [*]
  COMP_FAILED --> [*]
```

`AWAITING_APPROVAL` indicates a gated node awaiting operator decision: Temporal sets this state
when a node contract has `requireApprovalBefore` or is IRREVERSIBLE (unless auto-approved), while
Conductor maps an in-progress `WAIT` task to `AWAITING_APPROVAL` in `ConductorNodeStates.kt`.
Approval continues execution (completing the `WAIT` task to `DONE` on Conductor), while a reject
or abort marks the node `FAILED` and triggers compensation. `PARKED` exists only on Temporal
interactive runs: when a forward step fails, the node enters `PARKED` to await an operator
decision, where retry runs the step again (`RUNNING`) and abort marks it `FAILED` to unwind
completed compensable nodes. On non-interactive runs a failing step transitions straight to
`FAILED`, and Conductor never emits `PARKED` because it has no parked-node primitive.
`COMPENSATED` vs `COMP_FAILED` records whether a node's compensate step succeeded — compensation is
best-effort, so a failed compensate is recorded as `COMP_FAILED` and the rest of the unwind
continues (§5).

## 7. Module map and dependency graph

Fourteen Gradle modules (from `settings.gradle.kts`; `authoring-ui` is the React app, not a Gradle
module). Edges point in the direction of dependency (A → B means A depends on B). The diagram shows
the architecture-carrying edges, not every edge. Edges omitted from the diagram: `runtime` →
`registry`, `blocks`; `conductor-runtime` → `registry`, `blocks`, `sdk`; `authoring-api` → `core`,
`blocks`; and `app` → `core`, `registry`, `blocks`, `compiler`, `opcua`. Each module's
`build.gradle.kts` (including `app/build.gradle.kts`) provides the full list.

```mermaid
flowchart TD
  core["core<br/>(BlockContract, enums)"]
  sdk["sdk<br/>(BlockInput/Output)"]
  registry["registry<br/>(manifest resolve)"]
  blocks["blocks<br/>(6 handlers + manifests)"]
  dispatch["dispatch"]
  compiler["compiler<br/>(IR, WorkflowCompiler, ConductorBackend)"]
  runtime["runtime<br/>(SagaWorkflowImpl, EnginePort, TemporalBackend/Port)"]
  conductorrt["conductor-runtime<br/>(ConductorStarter, CompLedger)"]
  api["authoring-api<br/>(ConductorEnginePort, Spring edge)"]
  app["app<br/>(worker, watcher, CLI entry points)"]
  opcua["opcua<br/>(Milo, OPC-UA blocks, command-policy, audit)"]
  delegation["delegation<br/>(DelegationPolicy, scoring sim)"]
  episode["episode<br/>(pure state machine: transition, candidates, policy)"]
  episodert["episode-runtime<br/>(EpisodeWorkflow, activities, watcher logic, stores)"]

  sdk --> core
  registry --> core
  blocks --> sdk
  dispatch --> core
  dispatch --> registry
  dispatch --> blocks
  dispatch --> sdk
  dispatch --> opcua
  opcua --> sdk
  compiler --> core
  compiler --> registry
  runtime --> compiler
  runtime --> core
  runtime --> sdk
  runtime --> dispatch
  conductorrt --> compiler
  conductorrt --> core
  conductorrt --> dispatch
  api --> compiler
  api --> runtime
  api --> conductorrt
  api --> registry
  api --> dispatch
  api --> opcua
  app --> runtime
  delegation --> sdk
  dispatch --> delegation
  app --> delegation
  episodert --> episode
  episodert --> runtime
  app --> episodert
  api --> episodert

  classDef edge fill:#eee,stroke:#888;
  class api,app edge;
```

**Boundary rules baked into this graph:** `:conductor-runtime` depends on `:compiler`/`:core` but
not on `:runtime` or `io.temporal`. Spring Boot is a dependency of `:authoring-api` only; `:app` is
a plain Kotlin application (the Temporal worker, the watcher and the CLIs) with no Spring
dependency, so `:core / :registry / :compiler / :dispatch` stay framework-free.
`ConductorEnginePort` is hoisted into `:authoring-api` to keep `:conductor-runtime` clean of the
`EnginePort` (Temporal) seam. The same rule holds for the episode loop: `:episode` has no project
dependency at all (Jackson's JSON tree only, `episode/build.gradle.kts`), so it knows nothing of
Temporal, Spring or JDBC; `:episode-runtime`'s main code imports only `RawJson` from `:runtime`.

## 8. Verification strategy

The test suite reports **1,248 tests, 0 failures, 1 skipped** across the 14 Gradle modules via
`./gradlew test` at `a6b5314` (episode 323 · episode-runtime 308 · authoring-api 151 · opcua 115 ·
registry 102 · runtime 55 · compiler 47 · app 42 · conductor-runtime 39 · delegation 20 · blocks 17 ·
core 17 · dispatch 7 · sdk 5). The skipped test is the R3 replay-history generator, which runs only
with `-Dkoshei.writeReplayHistories=true` (§12). Outside Gradle, `authoring-ui` type-checks clean
(`tsc --noEmit`), runs 83 vitest tests, and runs 20 Playwright tests in `e2e/episodes.spec.ts`
against a mocked `/api/episodes…`. The disposable de-risking spike is excluded from these figures.
Engine-touching tests use Testcontainers (real Postgres, Temporal, and Conductor), development is
TDD, and there are 34 gate scripts (`scripts/run-*-gate.sh`) plus a Playwright E2E-to-GIF harness
(`scripts/run-e2e.sh`, specs in `authoring-ui/e2e/`) and a crash-recovery harness
(`scripts/run-crash-recovery.sh`).

The table maps each headline capability to the artifact that proves it; it lists only some of the
gate scripts:

| Capability | Proving artifact |
| --- | --- |
| Compile to engine-neutral IR | `run-compiler-ir-gate.sh` |
| DAG wiring + topo order | `run-dag-gate.sh` |
| Temporal concurrency (promise graph) | `run-concurrency-gate.sh` |
| Conductor concurrency (FORK_JOIN/JOIN) | `run-conductor-concurrency-gate.sh` |
| Per-node states / canvas lighting | `run-node-states-gate.sh`, `run-conductor-node-states-gate.sh` |
| Compensation timeline (both engines) | `run-compensation-timeline-gate.sh`, `run-conductor-comp-timeline-gate.sh` |
| Human gate + interventions | `run-intervention-gate.sh` |
| Conductor retry/abort + R-1 self-dispatch | `run-conductor-retry-abort-gate.sh` |
| Conductor end-to-end execution | `run-conductor-exec-gate.sh` |
| Plugin block on Conductor (published while the worker runs) | `run-conductor-plugin-gate.sh` |
| Console (both engines) | `run-console-gate.sh`, `run-conductor-console-gate.sh` |
| Add-a-block / authoring / compose-run | `run-add-block-gate.sh`, `run-authoring-gate.sh`, `run-compose-run-gate.sh` |
| Full OT scenario | `run-scenario-gate.sh` |
| Crash recovery (Temporal) | `run-crash-recovery.sh` |
| Episode outer loop | the tests listed in §12 (no gate script yet) |

## 9. Scale-out & deployment topology

This section covers whether the backend can scale out under load while the frontend stays a single
portal. It does so **by design**, subject to the as-built caveat at the end of the section.

- **Backend scales horizontally.** Workers poll a shared task queue, so throughput increases by
  adding worker replicas without code changes. On Temporal, `app/.../Worker.kt` starts a
  `WorkerFactory` worker that polls the shared `koshei-v0_1-tq` task queue (`factory.start()`). On
  Conductor, `conductor-runtime/.../ConductorWorkerMain.kt` boots one `ForwardWorker` per block id
  the registry knows (the builtins and every published plugin block, task type = blockId) plus a
  `CompensateWorker`, all polling Conductor via `TaskRunnerConfigurer`. Additional replicas add
  pollers competing for the same queue.
  The `:authoring-api` control plane is a thin, mostly stateless edge: durable state lives in
  Postgres (`workflow_def`, `run_index`, `comp_ledger`) and in the engine, so the control
  plane can run multi-replica behind a load balancer (its only per-process state is an idempotent
  deploy-dedupe cache, which is safe to lose). The durable engines themselves (Temporal and
  Conductor) are clustered, horizontally scalable systems.
- **Frontend stays a single portal.** The React `authoring-ui` is a static SPA, one
  operator console; load does not scale the frontend. Operators control and observe
  workflows through one window regardless of how many workers run, so worker scale remains
  transparent to the operator. This is a stated design principle.
- **Evidence.** The de-risking spike proved shared-task-queue scale-out and failover as proof P5:
  `run-p5-scaleout.sh` killed worker 1, and a fresh workflow completed on the surviving worker 2
  (failover), with activity distribution observed across both workers (`w1=18 w2=27` forward
  markers). The spike's engine verdict (OQ1) records: "Shared task queue gives scale-out + failover
  (P5)."

```mermaid
flowchart TB
  spa["authoring-ui SPA<br/>single operator console"]
  lb["load balancer"]
  subgraph cp["control plane (stateless, N replicas)"]
    api1[":authoring-api #1"]
    api2[":authoring-api #N"]
  end
  tq[["shared task queue<br/>(Temporal / Conductor)"]]
  subgraph wk["worker pool (M replicas)"]
    w1["worker #1"]
    w2["worker #M"]
  end
  pg[("Postgres<br/>workflow_def · run_index · comp_ledger")]
  eng["durable engine<br/>(clustered)"]

  spa --> lb --> api1
  lb --> api2
  api1 --> eng
  api2 --> eng
  api1 --> pg
  api2 --> pg
  eng --> tq
  tq --> w1
  tq --> w2
  w1 --> pg
  w2 --> pg
```

**As-built limit.** The repository implements a single-process local demo: one worker,
one `:authoring-api`, and a docker-compose stack. Horizontal scale-out is designed for and not
deployed. The architectural seams make it possible (poll-based workers on a shared queue, a
stateless externalized-state control plane, and clustered engines) and engine task-queue scaling is
spike-proven (P5 above), but a multi-replica Kubernetes / HPA / Helm deployment is not built. That
deployment is on the roadmap (design-doc ADR-11), and the architecture is deliberately kept
non-breaking toward it. See the broader inventory of limits in §10.

## 10. Honest limits and non-goals (current)

- **Crash-recovery is demonstrated on Temporal only** (`run-crash-recovery.sh`); the Conductor path
  is not exercised by an equivalent kill-and-replay harness.
- **Conductor node-state overlay is eventually consistent.** Because conductor-standalone indexes
  asynchronously, the COMPENSATED overlay surfaces after a short grace-poll window
  (`RunDetail.tsx` TERMINAL_GRACE).
- **No real OT connectors.** The `actuate` block in `ot-recipe-apply` models an IRREVERSIBLE
  side-effect and drives no hardware. The `:opcua` module's `opcua.write` and `opcua.call` blocks
  are exercised by `run-opcua-gate.sh` only against an embedded Milo OPC-UA simulator, on Temporal
  only. No connector to real field equipment is part of the build.
- **Single-version Conductor definitions.** A Conductor definition is keyed by bare `name`; a
  Temporal one by `name@version`; multi-version Conductor is future work
  (`ConductorEnginePort` limitation note).
- **E2E/GIFs run locally and on demand**, not in continuous CI.
- **Product and demand axis is unvalidated.** Koshei is an engineering artifact, not a
  market-proven product.
- **Episode outer loop.** The episode loop is a PoC on one machine (§12) with no real picasso host
  yet; with the real approval window a person confirms every precondition, and the meaning of DONE
  remains open (design §19 F). Detailed limits are listed in §12.6.

## 11. Design rationale and decision record

- `docs/scenario-ot-actuation-demo.md` — the deep walkthrough of the `ot-recipe-apply` scenario.
- `docs/usage.md` — the **Using & Extending Koshei** developer guide: run it, author your own block (the SDK + `:app:cli` scaffold/publish flow), operate, and configure.
- `docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md` — the episode outer loop design (in Korean), with its decision record (§18) and open decisions (§19). §12 below summarizes what was built.

## 12. Episode outer loop (as-built)

The saga (§5) runs a workflow an operator composed. The episode outer loop manages one fault, an
episode, from the first symptom to its resolution. It is a deterministic state machine; the LLM
diagnosis is one side-effect-free activity called from one of its states (design §1). It is
**Temporal-only**: nobody draws it, so it does not go through the engine-neutral IR or Conductor
(design §4.1). It is off by default.

### 12.1 Modules and boundaries

| Where | What | Knows about |
| --- | --- | --- |
| `:episode` | The pure core: `transition(state, event, policy, now)` returns the next state and the commands to run; candidate projection, `candidateId` / `candidatesVersion`, the policy table and its validation, `takeEvidence` (result-report verdict, `Outcome.kt`), `correlate` (merge key, `Correlation.kt`) | Jackson's JSON tree only. No Temporal, Spring or JDBC (`episode/build.gradle.kts`) |
| `:episode-runtime` | The Temporal shell: `EpisodeWorkflow(Impl)` drives `transition` and decides nothing itself; the activities (`diagnose` remote on `narrator-tq`, `readPolicy`, `revalidate`, `record`, `dispatch`, `notify`); the approval windows (`MockPicasso`, `HttpApprovalWindow`); the watcher's logic (`EpisodeWatcher`, `Bundles`, `ExportSymptoms`, `ResultExportFile`, `WatchStore`); `EpisodeStore`; `AgentOffBroadcast` | `:episode`, `RawJson` from `:runtime`, Temporal SDK, Postgres |
| `:app` | `Worker.kt` registers the episode worker on `koshei-episode-tq` next to the saga worker (`koshei-v0_1-tq`) when `KOSHEI_PICASSO` is not `off`; `Watcher.kt` is the watcher process (`./gradlew :app:watcher`); `EpisodeCli.kt` is the dev CLI | `:episode-runtime` |
| `:authoring-api` | `EpisodeController`, `/api/episodes…` on 127.0.0.1:18090: list, detail with the operator card, the notice feed, and the decisions `decide` / `confirm` / `takeover` / `close` under the `X-Koshei-Operator` header. It reads and relays; the core decides | `:episode-runtime` |
| `authoring-ui` | Tab "07 Episodes" (`src/views/episodes/`): list, detail (card, decisions, history, candidates, records), notice feed | `/api/episodes…` |

Three boundaries carry the design:

- **Pure core vs. Temporal shell.** The workflow feeds events to `transition` and runs the returned
  commands. Every rule (deadlines, "UNKNOWN blocks every execution command", "no dispatch without a
  recorded intent") is a property of the transition table and is tested in the pure module
  (design §5.2, §6, §17).
- **Decisions as Workflow Updates.** A person's decision arrives as a Workflow Update. The validator
  only rejects what cannot be decoded; a stale or refused decision is returned by the handler as a
  value, so it stays in the history (design §3 D2, §3.1 spike).
- **The watcher is a separate process.** It carries and does not judge. It reads picasso's export
  bundles only after `manifest.json` appears, keeps its cursor in Postgres, delivers at least once
  (the core de-duplicates by `eventId` and by (`instanceId`, `jobResponseId`)), and sends a result
  report to the episode instance whose recorded dispatch intent names that order (design §12).

### 12.2 Processes and queues

| Process | Queue / surface |
| --- | --- |
| koshei worker (`./gradlew :app:run`) | `koshei-episode-tq` (episodes), `koshei-v0_1-tq` (sagas); with `KOSHEI_NARRATOR=mock` (default) also the Mock narrator on `narrator-tq` |
| narrator worker (Python, sibling repo) | `narrator-tq`, activity `diagnose`, diagnosis contract 0.6, when `KOSHEI_NARRATOR=remote` |
| watcher (`./gradlew :app:watcher`) | reads picasso export directories (`KOSHEI_WATCH_EXPORTS`) and `job-responses.jsonl` (`KOSHEI_WATCH_CARRY`, picasso ResultExport schema 1); signals Temporal |
| picasso host (outside koshei, not built yet) | the approval window `POST /approvals` (loopback, unauthenticated) and the result reports |
| control plane (`:authoring-api`) | 127.0.0.1:18090, `/api/episodes…` |

### 12.3 The episode, in short

Phases in code (`Phase` in `:episode`, design §5.1) are `CORRELATING`, `DIAGNOSING`, `AWAITING_APPROVAL`,
`REVALIDATING`, `UNKNOWN_PRECONDITION`, `DISPATCH_PENDING`, `DISPATCHED`, `AWAITING_EVIDENCE`,
`UNKNOWN_OUTCOME`, `ESCALATED`, `RESOLVED`, `SUPERSEDED`, and `CLOSED`. The design's PROPOSED is
momentary: the policy decision happens inside the same transition step, so it is never a resting
phase, though its passage is recorded. Terminal phases are `RESOLVED`, `SUPERSEDED`, and `CLOSED`.
`ESCALATED` is not terminal: the agent stops acting and a person takes over; it ends in `CLOSED` by a
person's `close` or when the retention deadline passes (design §5.1). Every wait has a deadline from
the policy table.

- **Diagnosis.** koshei computes the candidates; narrator may only point at one or choose ESCALATE.
  A recommendation inside the candidates, on the same candidates version, with at least one verified
  citation becomes a proposal. Everything else (NO_GROUNDS, UNCITED, ESCALATE, out of candidates, a
  contract violation, a timeout) escalates to a person (design §5.2).
- **Approval.** Policy v1 (`model/episode-policy/active.yaml`, version `2026-10-03.2`) keeps
  auto-approval of `APPROVE_REMEDY` off, so a person approves (design §18).
- **Revalidation and dispatch.** The precondition is re-read before dispatch. The Mock answers TRUE
  while the proposal stands; with the real window it is always UNKNOWN, because there is no live
  ledger read, so a person confirms the proposition shown on the card (design §8.3, §16 "C3 끝"). The
  dispatch intent is recorded first, and a dispatch is sent only after that record. The `dispatch`
  activity has up to three attempts inside the policy's `dispatchMs`. If the answer is still lost
  after that (attempts used up or `dispatchMs` expired), or picasso answers `NO_PROPOSAL`,
  `REMEDY_NOT_APPLIED` or `CONSUMED`, the episode goes to UNKNOWN(OUTCOME), and koshei does not
  dispatch again to find out (design §11). An answer koshei cannot read escalates as
  `DISPATCH_ANSWER_UNKNOWN` (design §5.2).
- **Resolution.** A result report resolves the episode only when it links to this attempt by
  (`instanceId`, `executionId`), every approved unit is in `completedUnits`, the report says no unit
  is in doubt or unverified and no operator is required, and the connection is
  `CONNECTION_STATE_ONLINE`. A doubtful report makes the outcome UNKNOWN; a person can confirm it
  (design §5.2, §12, §19 F).
- **Off switches.** `agentLayerEnabled: false` in the policy file, or `agentOff` (one episode, or
  every open one with `--all`), hands episodes to a person (design §10.4).
- **Saga behaviour is unchanged.** With `KOSHEI_PICASSO=off` no episode worker starts and no episode
  table is created (design §4.2). With the loop on, the control plane refuses drift auto-correct
  dispatch, including the one-shot `POST /api/autocorrect/sweep` (409 `EPISODE_MODE`), so no remedy
  leaves outside an episode (design §14, §18).

### 12.4 Tables

`episode-runtime/src/main/resources/episode-schema.sql` creates five append-only tables, idempotently
and under an advisory lock (design §13):

| Table | Holds |
| --- | --- |
| `episode_event` | the audit record of every episode, keyed `(episode_instance_id, seq)`; the audit source of truth, since Temporal history expires |
| `episode_notice` | the notice feed for people |
| `episode_dispatch` | what a dispatch came back with, by idempotency key (picasso's answer verbatim) |
| `episode_watch_cursor` | the watcher's cursor per (source, run), one row per step forward |
| `episode_watch_log` | what the watcher could not carry or carried unmerged (`NOT_READY`, `BROKEN`, `STUCK`, `AMBIGUOUS`, `UNROUTED`, `ROUTE_ENDED`, `SIGNAL_FAILED`, `POLICY_UNUSABLE`) |

`scripts/episode-db-roles.sql` grants the runtime role `koshei_episode_rt` `SELECT` and `INSERT` on the
five tables (plus use of their two id sequences) and nothing else, and refuses to run if that role may
`CREATE` in the schema. The worker and the watcher use it through `KOSHEI_EPISODE_DB_USER` /
`KOSHEI_EPISODE_DB_PASS`; the control plane still reads with the owner login
(design §13 "구현(D-lite)").

### 12.5 Evidence

Tests (counts in §8):

- **`:episode`.** Tests cover the transition table, policy validation and projection.
  `EpisodePropertiesTest` runs 1,000 seeds × up to 60 random events, checking no dispatch while
  UNKNOWN, at most one dispatch per key, no dispatch without a record, and that every run ends. A
  directed test checks that a report resolves only when linked, complete, online and clean.
- **`:episode-runtime`.** Tests include `EpisodeEndToEndTest` (real activities, test Postgres, Mock
  narrator and Mock picasso, policy v1), `WatcherEndToEndTest` (watcher, signals, `MockCarrier` result
  reports: merge rule, early and duplicate reports, resolution only while ONLINE),
  `PicassoWindowEndToEndTest` (the real client over HTTP), `EpisodeDbRolesTest` (`UPDATE`, `DELETE`,
  `TRUNCATE` refused as the runtime role), and `CommittedReplayTest`, which replays the nine committed
  histories in `src/test/resources/replay/2026-10-04/` against today's code (R3, design §7.5).
- **`:authoring-api`.** `EpisodeControllerTest` and `EpisodeGatewayTest`.
- **`authoring-ui`.** `e2e/episodes.spec.ts` runs against a mocked `/api/episodes…` only.

Live runs:

- **Through narrator, 2026-10-03 (UTC).** Running with `KOSHEI_NARRATOR=remote` and
  `KOSHEI_PICASSO=mock`, an episode was opened with the dev CLI `episode open` from the export line
  `search-1`. It called narrator's Python worker on `narrator-tq`, which called khala. One diagnosis
  attempt took 255.5 s; the answer returned with 13 of 13 citations verified and recommended ESCALATE,
  so the episode went to a person (ESCALATED) (design §16, "실물 진단 끝까지 한 번").
- **Against picasso's reference host, 2026-10-04** (design §16 "C3 끝"). With
  `KOSHEI_PICASSO=picasso` and the Mock narrator, a person approved a remedy for hum-02, confirmed the
  precondition, and picasso answered APPROVED. The linked JobResponse completed the approved unit
  while ONLINE but carried `operatorRequired`, so the episode went to UNKNOWN_OUTCOME. picasso's
  reading is that the next unit of the order blocked, not the remedy; this leaves what DONE means open
  (design §19 F).

### 12.6 Limits

- **No real picasso host.** It is decided as a separate deployment repo (design §19 C); until then
  picasso's reference host is the test peer. The approval window is loopback-only, so koshei must run
  on the same machine.
- **Revalidation.** Revalidation with the real window is always UNKNOWN, so a person confirms each
  precondition.
- **DONE.** Today, DONE means "the remedy's units completed and the order shows no doubt and no need
  for a person". Whether it should mean the remedy or the order is open (design §19 F).
- **Out of scope** (design §16 "D-lite 끝", §19): policy management (git + DB pointer + CLI);
  `SAGA_ACTION` remedies and child sagas; the blocks' "unknown" report and the safe-hold defect D5;
  the full record fields and quality-history links; a watcher-log screen and cross-references for
  ambiguous merges in the episode record; a read-only role for the control plane; column checks in
  `ensureSchema`; and replay histories stopped in DISPATCH_PENDING or REVALIDATING.
- **Drift.** Drift is not yet a watcher input (design §16, C1 remaining).
- **Deployment scale.** The system runs on one machine at PoC scale.
