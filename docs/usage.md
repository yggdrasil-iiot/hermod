# Using & Extending Koshei

A practical, developer-facing guide to **running Koshei locally, authoring your own block, operating workflows from the console, and configuring the stack**. Every command here is runnable against this repository.

## Who this is for, and an honest framing up front

This guide is for a developer who wants to **run** Koshei on their own machine, **author blocks** for it, **operate** workflows through the UI, and **extend** it.

Two things to know before you start:

- **License — noncommercial.** Koshei is *source-available* under the [PolyForm Noncommercial License 1.0.0](../LICENSE.md). Noncommercial use is freely permitted; **commercial use is reserved**. This is **not** an OSI-approved open-source license — "OSS" here means publicly available and contributable, not OSI Open Source.
- **Maturity — demo-grade.** What you run from this repo is a **single-process local demo**: one worker, the Docker Compose stack on one host. There are **no real OT connectors** (no PLC/OPC/MQTT), **no Kubernetes / production deployment**, and the Conductor observation overlay is **eventually consistent**. It is an engineering artifact that proves the durable-saga core, not a shippable product. See [§6](#6-limits--license).

---

## 1. Run it

Prerequisites: a JDK (the Gradle toolchain auto-provisions JDK 21 via foojay) and **Docker**. Each long-running command below wants its own terminal. These commands mirror the README Quickstart.

Every command in the guide is written for bash. In PowerShell, `./gradlew ...` and `docker compose ...` work as written, including `--args="..."`. Set an environment variable with `$env:NAME = "value"` instead of `export NAME=value`. Windows PowerShell 5.1 has no `&&`; put each command on its own line or separate with `;`.

```bash
# 1. Bring up infra (Postgres, Temporal, Conductor + their UIs) and apply the schema.
docker compose up -d
bash scripts/init-db.sh

# 2. Start the Temporal worker (the koshei.app.Worker entrypoint).
./gradlew :app:run

# 3. Start the control plane (Spring Boot edge on port 18090).
./gradlew :authoring-api:run

# 4. Start the UI dev server (Vite on port 5173, proxies /api -> 18090).
cd authoring-ui
npm install      # first time only
npm run dev
```

To apply the schema without bash, pipe `Get-Content` into `docker compose exec -T postgres ...` to do what `scripts/init-db.sh` does:

```powershell
Get-Content app/src/main/resources/schema.sql -Raw | docker compose exec -T postgres psql -U koshei -d koshei
```

Then open **http://localhost:5173**, use the **Compose** tab to assemble (or load the bundled `ot-recipe-apply` workflow), and the **Console** tab to run it and watch per-node lighting, reverse-order compensation, and operator interventions live.

> **⚠ `./gradlew :app:run` launches the WORKER, not the CLI.** The `application { mainClass }` in `app/build.gradle.kts` is `koshei.app.WorkerKt`. The block-authoring CLI is a separate Gradle task — see [§2](#2--author-your-own-block). Don't confuse the two.

**To use the Conductor engine** with the bundled stack, set `KOSHEI_CONDUCTOR_URL` before starting the control plane, the only process that reads it:

```bash
export KOSHEI_CONDUCTOR_URL=http://localhost:18088/api
```

This override matters: the code default is `http://localhost:8088/api`, but `docker-compose.yml` publishes Conductor on host port **18088**. Without the override, Conductor runs won't reach the bundled container. See [§4](#4-configure).

---

## 2. ★ Author your own block

This is the centerpiece. A **block** is the unit you compose workflows from, and you can add one **without editing Koshei's source**.

### The model: a contract + a handler

A block is two things:

1. A **YAML contract manifest** that *declares*, once, the block's resilience policy — its idempotency strategy, its compensation (reversibility + kind), its retry budget, its human-gating, and its side-effects. The runtime **derives** all durable behaviour (idempotent convergence, reverse-order saga compensation, retry, approval gating) generically from this contract — there is no per-workflow orchestration code. The full contract-field-to-behaviour mapping is in [`docs/architecture.md` §3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience).
2. A **`Block` handler** (a Kotlin class) that implements the actual `forward` work and, optionally, `compensate`.

### The `Block` interface

From `sdk/src/main/kotlin/koshei/sdk/Block.kt`:

```kotlin
typealias Record = Map<String, String?>

data class BlockInput(
    val rows: List<Record> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val namedInputs: Map<String, List<Record>> = emptyMap(),  // multi-input nodes only
    // ...test-only fields omitted...
)
data class BlockOutput(
    val rows: List<Record> = emptyList(),
    val boundState: Map<String, String> = emptyMap(),         // stateBinding outputs, as JSON strings
)
data class CompensationContext(val alreadyAppliedHint: Boolean = true)
data class CompensationAction(val kind: String, val detail: String) // kind: a String — see below

interface Block {
    val id: String
    fun forward(input: BlockInput): BlockOutput
    fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction =
        CompensationAction("NOOP", "no compensation declared")
}

class PermanentBlockFailure(message: String) : RuntimeException(message)
```

Notes:

- **`CompensationAction.kind` is a plain `String`, not a typed enum.** By convention it's one of `RESTORE` / `CORRECT` / `NOOP`, but the type is `String` — you return whichever convention fits.
- Throw **`PermanentBlockFailure`** from `forward` to signal a *permanent* failure. That's what trips the saga: completed reversible steps then compensate in reverse-topological order rather than the step being retried.
- `compensate` has a default `NOOP` body, so a pure transform can override only `forward`.

### End-to-end walk: scaffold → implement → publish → compose → run

**Step 1 — scaffold a plugin project.** The CLI is a custom Gradle `JavaExec` task named `cli` (mainClass `koshei.app.CliKt`) defined in `app/build.gradle.kts`. Run it as `./gradlew :app:cli --args="..."`:

```bash
./gradlew :app:cli --args="scaffold block io.example.greet"
```

This generates a standalone plugin project (default dir `io-example-greet/`): `build.gradle.kts`, `settings.gradle.kts`, `src/main/kotlin/.../GreetBlock.kt`, and `src/main/resources/manifests/io.example.greet.yaml`.

> The CLI's printed next-steps say `koshei-cli publish ...`. That `koshei-cli` shorthand is **not** runnable — translate every such line to `./gradlew :app:cli --args="publish ..."`.

**Step 2 — implement `forward` and `compensate`.** The scaffold `Block.kt` stub gives you both halves to fill in:

```kotlin
class GreetBlock : Block {
    override val id: String = "io.example.greet"

    override fun forward(input: BlockInput): BlockOutput {
        // pass rows through; emit boundState keys your compensation needs
        // (must match the manifest's stateBinding)
        return BlockOutput(rows = input.rows)
    }

    override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction {
        return CompensationAction("NOOP", "no compensation declared")
    }
}
```

For a complete, real handler see the worked example `examples/greet-plugin/src/main/kotlin/io/example/GreetBlock.kt` — a pure transform that stamps `greeted=true` onto each row and overrides **only** `forward` (relying on the default `NOOP` compensate).

**Step 3 — fill the manifest contract.** Edit the scaffolded `manifests/io.example.greet.yaml`. The complete reference manifest (`examples/greet-plugin/src/main/resources/manifests/io.example.greet.yaml`) shows every field:

```yaml
id: io.example.greet
version: "1.0.0"
displayName: "Greet"
category: transform
forward: { handler: "io.example.GreetBlock" }
idempotency: { strategy: NONE }
compensation: { reversibility: REVERSIBLE, kind: NONE }
retry: { maxAttempts: 1, backoff: { initialMs: 100, maxMs: 100 } }
timeoutMs: 30000
sideEffects: [NONE]
human: { requireApprovalBefore: false }
```

Each field's runtime effect is documented in [`docs/architecture.md` §3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience).

**Step 4 — build and publish the jar.** Inside the plugin project, publish the SDK to mavenLocal first if needed, then build the jar. The plugin depends on the SDK as `compileOnly("io.koshei:sdk:0.2.0")` (see `examples/greet-plugin/build.gradle.kts`) — the host provides the SDK at runtime, so the jar carries only *your* classes:

```bash
# from the host repo root, if io.koshei:sdk isn't in mavenLocal yet:
./gradlew :sdk:publishToMavenLocal

# inside the plugin project:
./gradlew publishToMavenLocal   # publishes your plugin to mavenLocal
./gradlew jar                   # builds build/libs/<your>.jar
```

Then publish the jar to the Koshei registry via the CLI. **Pass an absolute path:** the `:app:cli` task runs with `workingDir = app/`, so a *relative* jar path would resolve under `app/`, not your plugin dir.

```bash
./gradlew :app:cli --args="publish /ABSOLUTE/path/to/build/libs/io-example-greet.jar"
```

Useful companion commands:

```bash
./gradlew :app:cli --args="list"            # list all registered blocks (builtins + plugins)
./gradlew :app:cli --args="compile ot-recipe-apply"   # compile a workflow to IR
```

**Step 5 — it appears in the palette; compose and run.** After publishing, the freshly published block **appears in the authoring-api palette without restarting the API**, because the palette is a live read of the block registry (`registry.list()` is a DB query). Open the Compose tab, drag your block in, wire it, save (deploys a versioned definition), then run it from the Console tab.

> **Scope of the "no code edit" claim.** Do not over-claim worker hot-reload. The block is published, isolation-loaded, and run **without editing Koshei's source**, and it appears in the palette **without restarting the authoring-api**. It does **not** hot-reload into an already-running worker.

### Proof this actually works

`scripts/run-add-block-gate.sh` is an objective gate that proves an external jar is **published, classloader-isolation-loaded, and run inside a durable saga, with no code edit**. It publishes the jar via `./gradlew :app:cli --args="publish $PLUGIN_JAR_ABS"` (absolute path, as above) and asserts the log shows `[PluginLoader] loaded … via URLClassLoader`, `[plugin] forward io.example.greet`, and `completed=true`. Note this gate is **publish-then-start** — it loads the plugin when the worker starts, so it does **not** prove worker hot-reload.

---

## 3. Compose & operate (the UI)

Open **http://localhost:5173**. The SPA proxies `/api` to the `:authoring-api` control plane on 18090 (`authoring-ui/vite.config.ts`).

**Compose tab.** The palette is projected from the block registry (your published blocks plus the 6 builtins: `db.read`, `transform.map`, `db.upsert`, `notify.email`, `actuate`, `merge`). Drag blocks onto the React Flow canvas, wire them, and **save** — saving deploys a **versioned workflow definition**. When you run, you pick the **engine** (Temporal or Conductor).

![Compose a workflow on the canvas](demo/01-compose.gif)

**Console tab.** Shows **run history**, **per-node lighting** (live node states), the **compensation timeline** (reverse-topological unwinding when a run fails before an irreversible step), and **operator interventions** — approve, reject, **retry**, **abort** — at human gates. The same console observes runs on **both engines**.

![Run it — each node lights up as it completes; approve the irreversible gate](demo/02-run.gif)

The canonical scenario to try is `ot-recipe-apply` (`sensorRead → recordPlan → interlockAck / preflight → applyPLC`, where `applyPLC` is `IRREVERSIBLE` and human-gated). On the failure path, completed reversible steps compensate in reverse order and the PLC never fires. Full walkthrough: [`docs/scenario-ot-actuation-demo.md`](scenario-ot-actuation-demo.md).

---

## 4. Configure

### Environment keys

| Key | Purpose |
|---|---|
| `KOSHEI_DB_URL` | Postgres JDBC URL for the saga/registry DB. Default `jdbc:postgresql://localhost:15432/koshei` (the bundled Compose Postgres). |
| `KOSHEI_DB_USER` | DB user. Default `koshei`. |
| `KOSHEI_DB_PASS` | DB password. Default `koshei`. |
| `KOSHEI_EPISODE_DB_USER` | DB user for the episode tables only (`EpisodeStore`, `WatchStore`). Unset or blank: the `KOSHEI_DB_*` login. The URL is always `KOSHEI_DB_URL`'s. In production: the runtime role `koshei_episode_rt` (how to make it: [§5](#the-episode-tables-and-the-runtime-role)). Read by the worker and the watcher. |
| `KOSHEI_EPISODE_DB_PASS` | Password for `KOSHEI_EPISODE_DB_USER`. Set both or neither: one without the other is refused at start. Read by the worker and the watcher. |
| `KOSHEI_PICASSO` | The approval window: `off` (default), `mock` (the test Mock window) or `picasso` (the real approval window); any other value stops the process at start. Read by the worker, the watcher and the control plane. Details in [§5](#5-run-the-episode-loop). |
| `KOSHEI_PICASSO_URL` | The window's origin, required when `KOSHEI_PICASSO=picasso`. Use `http://127.0.0.1:<port>` or `http://localhost:<port>` only, with no path (the client adds `/approvals`). `localhost` must resolve only to loopback addresses with an IPv4 loopback address first, otherwise the start is refused; prefer `http://127.0.0.1:<port>`. Read only by the worker. |
| `KOSHEI_PICASSO_AGENT_ID` | The approver id that a POLICY approval sends as AGENT. Required when `KOSHEI_PICASSO=picasso`. Read only by the worker. |
| `KOSHEI_PICASSO_TIMEOUT_MS` | The limit for one request to the window, in ms. Must be a positive number; default 8000. Keep it at most `(dispatchMs − 3000) / 3`, where `dispatchMs` is the episode policy's `dispatchMs` (30000 in policy v1, so at most 9000). Read only by the worker with `KOSHEI_PICASSO=picasso`. |
| `KOSHEI_NARRATOR` | Narrator mode: `mock` (default) or `remote`. Any other value stops the worker at start. With `mock` and episode mode on, the worker registers the Mock narrator on `narrator-tq`; with `remote`, narrator's Python worker serves that queue. Run only one poller on the queue. Read by the worker. |
| `KOSHEI_EPISODE_POLICY` | The episode policy file. When unset, `:app:run` and `:app:watcher` pass the absolute path of `model/episode-policy/active.yaml`. A missing file makes every episode `POLICY_MISSING`; the worker warns at start. Read by the worker and the watcher. |
| `KOSHEI_WATCH_EXPORTS` | picasso export directories, separated by the platform path separator (`;` on Windows, `:` elsewhere). Required, with no empty entries. Read only by the watcher. |
| `KOSHEI_WATCH_CARRY` | The directory holding picasso's `job-responses.jsonl` (ResultExport schema 1). Optional; when unset, no result reports are carried. Read only by the watcher. |
| `KOSHEI_WATCH_INTERVAL_MS` | Poll interval in ms. Allowed values are 100 to 60000; default 2000. Read only by the watcher. |
| `KOSHEI_CONDUCTOR_URL` | Conductor API base URL. Code default `http://localhost:8088/api`; the bundled Compose stack publishes Conductor on host port 18088, so use `http://localhost:18088/api` with it. Read only by the control plane. (**override needed, see the callout below**) |
| `KOSHEI_PLUGIN_DIR` | The directory where published plugin jars are stored and loaded from (stored as `<id>/<version>/<sha256>.jar`). Default `registry-store`, relative to the process's working directory. |
| `KOSHEI_PUBLISH_DISABLED` | Plugin publish switch. Setting `true` (or `1`) closes `POST /api/publish` with 403, so no new plugin code is accepted. Default: publish open. Read by the control plane. |
| `KOSHEI_BIND_ADDRESS` | The address the control plane listens on. Default `127.0.0.1` (loopback only). Read by the control plane. |
| `KOSHEI_WORKFLOWS_DIR` | Optional directory of workflow YAML files loaded at start, next to the bundled `workflows/*.yaml`; a file with the same name replaces the bundled one. Workflows saved from the Compose tab are stored in Postgres (table `workflow_def`), not here. Read by the worker. |
| `KOSHEI_WF_POLL_MS` | How often, in ms, the worker polls Postgres for newly deployed workflow definitions. Default 3000. Read by the worker. |
| `KOSHEI_WORKER_NAME` | The name the worker prints in its log lines. Default `worker-1`. Read by the worker. |
| `KOSHEI_FAULT_INJECT` | **Test-only**. When set to any value, it arms the fault-injection toggle the gate scripts use. |
| `KOSHEI_TEST_BLOCKS` | **Test-only**. Setting `1` adds a test block for the gate scripts. |

> **Security note.** The control plane has no authentication (proof-of-concept). It therefore listens on loopback
> by default, and publish-time checks never run uploaded plugin code: the handler class is resolved without
> initialization and never instantiated until a block is executed. A published block *does* run, with that
> process's privileges, wherever it is executed (the worker) when a workflow uses it — close the surface with
> `KOSHEI_PUBLISH_DISABLED=true` on deployments that must not accept new plugin code. This closes the HTTP
> publish endpoint only; the local CLI (`:app:cli publish`) is unaffected.

The Gradle daemon keeps the environment it started with, so a variable exported after the daemon started would not reach a forked task JVM on its own. So `:app:run`, `:app:watcher`, `:app:cli` and `:app:starter` copy a fixed list of `KOSHEI_*` keys from the live shell into the forked JVM (`kosheiEnvKeys` and `forwardKosheiEnv` in `app/build.gradle.kts`). This list covers every key in the table that the worker, the watcher or the CLI reads, except the test-only `KOSHEI_TEST_BLOCKS`, so the worker and the CLI agree on the plugin store and the DB. Separately, `:authoring-api:run` forwards every `KOSHEI_*` variable from the live shell, so switches like `KOSHEI_PUBLISH_DISABLED` and `KOSHEI_BIND_ADDRESS` reach the control plane. To confirm the publish switch took effect, check the control plane's startup log line `[publish] POST /api/publish is DISABLED (koshei.publish.disabled)` (or `... is open`).

### Ports (bundled Docker Compose stack)

| Service | Host port |
|---|---|
| Postgres | 15432 |
| Temporal | 7233 |
| Temporal UI | 18080 |
| Conductor | 18088 |
| authoring-api | 18090 |
| Vite (UI dev) | 5173 |

> **⚠ Conductor URL override (read this).** `KOSHEI_CONDUCTOR_URL` defaults to `http://localhost:8088/api` **in code**, but `docker-compose.yml` publishes Conductor on host **18088**. To use Conductor with the bundled stack you **must** set:
> ```bash
> export KOSHEI_CONDUCTOR_URL=http://localhost:18088/api
> ```

### Per-run engine

Each run chooses its engine via `RunRequest.engine`, one of `"temporal"` or `"conductor"` (default `temporal`). The Console's run dialog sets this for you.

### Where things live

- **Plugins**: published jars, under `KOSHEI_PLUGIN_DIR`.
- **Workflows**: saved (deployed) definitions are rows in Postgres (`workflow_def`); `KOSHEI_WORKFLOWS_DIR` only adds YAML files for the worker to load at start.

---

## 5. Run the episode loop

The episode outer loop handles one fault from symptom to resolution: picasso's exports come in as symptoms, narrator diagnoses, a person approves, koshei dispatches to picasso's approval window, and picasso's result reports close the episode. See [`docs/architecture.md` §12](architecture.md#12-episode-outer-loop-as-built) for how it works and [`docs/superpowers/specs/2026-09-27-episode-outer-loop-design.md`](superpowers/specs/2026-09-27-episode-outer-loop-design.md) for the design (in Korean). Every variable in this section is in the [§4](#4-configure) table.

Follow the steps in order:

1. [Worker modes](#worker-modes): pick the modes.
2. [The policy file](#the-policy-file): check the policy file.
3. [Start it](#start-it): start the processes.
4. [Feed it](#feed-it): feed it symptoms, with the watcher or the dev CLI.
5. [Decide](#decide-the-control-plane-and-the-ui): decide in the UI or through the control plane.
6. [The episode tables and the runtime role](#the-episode-tables-and-the-runtime-role): for production, run the worker and the watcher as the runtime database role.

### Worker modes

`KOSHEI_PICASSO` picks the approval window, and with it whether the loop runs at all:

| Value | What runs |
|---|---|
| `off` (default) | No episode worker and no episode tables. The saga worker and the gate scripts behave as before |
| `mock` | The episode worker on `koshei-episode-tq` with the test Mock window (`MockPicasso`, in the worker's memory). Remedies reach no robot; the worker prints a warning at start |
| `picasso` | The episode worker with the real window, `HttpApprovalWindow`: `POST /approvals` on `KOSHEI_PICASSO_URL` (loopback only), approvals sent as PERSON, or as AGENT under `KOSHEI_PICASSO_AGENT_ID` |

`KOSHEI_NARRATOR` picks who diagnoses: `mock` (default) answers in-process with a stand-in that recommends the first offered candidate with a clean, cited answer; `remote` leaves the Temporal queue `narrator-tq` to narrator's Python worker (diagnosis contract 0.6).

With `KOSHEI_PICASSO=picasso`, picasso looks up a declaration for every approver id, PERSON or AGENT; an undeclared id is refused `NOT_DECLARED`.

Each process reads only its own environment. The worker, the watcher and `:authoring-api` must see the same `KOSHEI_PICASSO`: a control plane started from another shell does not know it is in episode mode and could still dispatch drift auto-corrections. Set the same value in every shell that starts one of the three; the Gradle tasks copy `KOSHEI_*` variables from the shell that runs them (see [§4](#4-configure)). A value the code does not know stops the process at start.

### The policy file

`model/episode-policy/active.yaml` is policy table v1 (version `2026-10-03.2`), used by `:app:run` and `:app:watcher` unless `KOSHEI_EPISODE_POLICY` names another file. The runtime reads it afresh at every decision, so an edit takes effect without a restart; an unreadable or rejected file keeps the last valid table and suspends auto-approval. What v1 sets:

- `agentLayerEnabled: true`. Set it to `false` and every episode hands over to a person; `episode agent-off --all` makes the open ones act on it at once.
- `autoApprove.APPROVE_REMEDY.allowed: false`: a person approves every remedy.
- `deadlines`: every wait has one, e.g. `approvalMs` 5 min, `evidenceMs` 10 min, `episodeMs` 1 h.
- `diagnosis`: two attempts of 540 s within 1,200 s, heartbeat 30 s (narrator contract 0.6).
- `correlation`: one rule, a robot's search line and incident line on one order are one episode.
- `actionCatalog: []`: koshei's own remedies are out of scope.

Editing the file is the only policy management for now. Git + DB pointer + `policy validate` / `activate` is out of scope (design §19).

### Start it

Bring up the infra and the schema as in [§1](#1-run-it). Set `KOSHEI_PICASSO=mock` in each shell, then start the worker, the control plane and the UI dev server, each in its own terminal:

```bash
docker compose up -d && bash scripts/init-db.sh     # as in §1
export KOSHEI_PICASSO=mock                          # in each shell below
./gradlew :app:run                                  # worker: sagas + episodes (+ Mock narrator)
./gradlew :authoring-api:run                        # control plane: /api/episodes…
cd authoring-ui && npm run dev                      # UI: tab "07 Episodes"
```

The same steps in PowerShell:

```powershell
docker compose up -d                                # then apply the schema as in §1
$env:KOSHEI_PICASSO = "mock"                        # in each shell below
./gradlew :app:run                                  # worker: sagas + episodes (+ Mock narrator)
./gradlew :authoring-api:run                        # control plane: /api/episodes…
cd authoring-ui; npm run dev                        # UI: tab "07 Episodes"
```

At start in episode mode, the worker prints a line naming the episode queue `koshei-episode-tq`, the narrator mode and the policy path. With `mock`, it prints a warning that remedies reach no robot; if the policy file does not exist, it prints a warning that every episode will escalate `POLICY_MISSING`. Then open the UI tab **07 Episodes**.

### Feed it

Nothing opens an episode until symptoms arrive. Either run the watcher, or open one episode by hand with the dev CLI.

**The watcher** is a separate process started with `./gradlew :app:watcher`. It polls picasso export directories and, when `KOSHEI_WATCH_CARRY` is set, picasso's `job-responses.jsonl`. It reads a bundle only after its `manifest.json` appears, keeps its cursor in the table `episode_watch_cursor`, and writes what it could not carry to `episode_watch_log` (there is no screen for that log; read the table). It refuses to start with `KOSHEI_PICASSO=off`. Its working directory is `app/`, so give it absolute paths.

```bash
export KOSHEI_WATCH_EXPORTS=/abs/path/to/picasso/export
export KOSHEI_WATCH_CARRY=/abs/path/to/picasso/carry     # optional
./gradlew :app:watcher
```

The same in PowerShell; on Windows, separate several export directories with `;`:

```powershell
$env:KOSHEI_WATCH_EXPORTS = "C:\abs\path\to\picasso\export"
$env:KOSHEI_WATCH_CARRY = "C:\abs\path\to\picasso\carry"     # optional
./gradlew :app:watcher
```

**The dev CLI** opens one episode from one export line, read the same way the watcher reads it, and switches the agent off:

```bash
# open: signalWithStart ep:<key> with one line; the default key is <runId>:<id>
./gradlew :app:cli --args="episode open --export /abs/path/to/export --search search-1"
./gradlew :app:cli --args="episode open --export /abs/path/to/export --incident incident-1 --key my-key"

# agent-off: one episode, or every open one; the episode goes to a person (AGENT_LAYER_OFF)
./gradlew :app:cli --args="episode agent-off ep:my-key"
./gradlew :app:cli --args="episode agent-off --all"
```

These commands run unchanged in PowerShell (give a Windows absolute path there).

The repo carries a sample export at `episode-runtime/src/test/resources/picasso/run-1` (four searches, nine incidents). Pass it as an absolute path: `:app:cli` runs in `app/`.

### Decide: the control plane and the UI

`:authoring-api` serves the episodes on port 18090 (loopback by default). It reads the episode tables and relays decisions to the workflow as Workflow Updates. The core decides, and a refusal comes back as a value.

| Route | What |
|---|---|
| `GET /api/episodes?limit=` | episode instances |
| `GET /api/episodes/notices?after=&limit=` | the notice feed, in id order |
| `GET /api/episodes/{workflowId}/{run}` | records, notices, and while the run is live its view and operator card |
| `POST /api/episodes/{workflowId}/{run}/decide` | approve or reject a proposal |
| `POST /api/episodes/{workflowId}/{run}/confirm` | confirm a precondition, an outcome or an unknown |
| `POST /api/episodes/{workflowId}/{run}/takeover` | a person takes the episode over |
| `POST /api/episodes/{workflowId}/{run}/close` | close an escalated episode |

The `POST` routes need the operator's id in the `X-Koshei-Operator` header and refuse unknown or repeated body fields. The control plane has no authentication (see the security note in §4). The UI tab **07 Episodes** shows the list, the detail (card, the four decisions, history, candidates, records) and the notice feed.

With `KOSHEI_PICASSO=picasso`, revalidation is always UNKNOWN, so every dispatch waits for a person to confirm the precondition the card shows (`confirm` with `PRECONDITION`).

### The episode tables and the runtime role

The worker (or the watcher), started with the owner login, creates five tables from `episode-runtime/src/main/resources/episode-schema.sql`: `episode_event`, `episode_notice`, `episode_dispatch`, `episode_watch_cursor`, `episode_watch_log`. The control plane still reads the tables with the owner login.

In production, the worker and the watcher should use the runtime role `koshei_episode_rt` from `scripts/episode-db-roles.sql`, which grants `SELECT` and `INSERT` only on the five episode tables and refuses a role that may `CREATE` in the schema. With `KOSHEI_EPISODE_DB_USER` set, the worker and the watcher only check that the tables exist, so the tables must exist first: apply `episode-schema.sql` as the owner, or start the worker once without the variable. Then the owner account runs the script once with `psql -v ON_ERROR_STOP=1 -v rt_password='…' -U koshei -d koshei -f scripts/episode-db-roles.sql` and sets the real password with `\password koshei_episode_rt`. Pass a throwaway `rt_password` there, because a `-v` value shows in shell history and process lists. Against the bundled Compose Postgres from the host, add `-h localhost -p 15432` to that `psql` command (the stack publishes Postgres on host port 15432; the owner is `koshei`). Then set `KOSHEI_EPISODE_DB_USER` and `KOSHEI_EPISODE_DB_PASS` in the shells of the worker and the watcher.

---

## 6. Limits & license

**License — PolyForm Noncommercial 1.0.0.** Source-available; **noncommercial use only — commercial use is reserved** ([`LICENSE.md`](../LICENSE.md)). Not OSI-approved open source.

**Demo-grade, honestly:**

- **Single-process local stack.** One worker, the Compose stack on one host — not a scaled or production deployment, and there is no Kubernetes/production deploy path here.
- **No real OT connectors.** There are no PLC / OPC / MQTT integrations; the `actuate` block models an irreversible actuation for the demo, it does not drive real hardware.
- **Conductor overlay is eventually consistent.** The Conductor observation/compensation overlay reflects state with a lag; Temporal is the load-bearing durable engine (crash-recovery/replay is proven on Temporal).
- **The episode loop is a one-machine PoC.** There is no real picasso host yet (a separate deployment repo is decided; picasso's reference host is the test peer), revalidation with the real window is always UNKNOWN, and what DONE means is an open decision. See [`docs/architecture.md` §12.6](architecture.md#126-limits).

For the full as-built limits and non-goals see [`docs/architecture.md` §10](architecture.md#10-honest-limits-and-non-goals-current).

---

## See also

- [`docs/architecture.md`](architecture.md) — engineering deep-dive ([§3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience) contract mapping, [§9](architecture.md#9-scale-out--deployment-topology) deployment topology).
- [`docs/scenario-ot-actuation-demo.md`](scenario-ot-actuation-demo.md) — the `ot-recipe-apply` walkthrough.
- [`README.md`](../README.md) — project overview and Quickstart.
