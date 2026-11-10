# Using & Extending Hermod

A practical, developer-facing guide to **running Hermod locally, authoring your own block, operating workflows from the console, and configuring the stack**. Every command here is runnable against this repository.

## Who this is for, and an honest framing up front

This guide is for a developer who wants to **run** Hermod on their own machine, **author blocks** for it, **operate** workflows through the UI, and **extend** it.

Two things to know before you start:

- **License — Apache-2.0.** Hermod is licensed under the [Apache License, Version 2.0](../LICENSE), an OSI-approved open-source license. Commercial and noncommercial use are both permitted under Apache-2.0's terms.
- **Maturity — demo-grade.** What you run from this repo is a **single-process local demo**: one worker, the Docker Compose stack on one host. There is no PLC connector, and the OPC UA client and the Sparkplug B / MQTT adapter **target only simulators** (the Sparkplug gates add a broker) in this repository's tests and gate scripts, and none has run against field equipment. There is **no Kubernetes / production deployment**, and the Conductor observation overlay is **eventually consistent**. It is an engineering artifact that proves the durable-saga core, not a shippable product. See [§5](#5-limits--license).

---

## 1. Run it

Prerequisites: a JDK (the Gradle toolchain auto-provisions JDK 21 via foojay) and **Docker**. Each long-running command below wants its own terminal. These commands mirror the README Quickstart.

Every command in the guide is written for bash. In PowerShell, `./gradlew ...` and `docker compose ...` work as written, including `--args="..."`. Set an environment variable with `$env:NAME = "value"` instead of `export NAME=value`. Windows PowerShell 5.1 has no `&&`; put each command on its own line or separate with `;`.

```bash
# 1. Bring up infra (Postgres, Temporal, Conductor + their UIs) and apply the schema.
docker compose up -d
bash scripts/init-db.sh

# 2. Start the Temporal worker (the yggdrasil.hermod.app.Worker entrypoint).
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
Get-Content app/src/main/resources/schema.sql -Raw | docker compose exec -T postgres psql -U hermod -d hermod
```

Then open **http://localhost:5173**, use the **Compose** tab to assemble (or load the bundled `ot-recipe-apply` workflow), and the **Console** tab to run it and watch per-node lighting, reverse-order compensation, and operator interventions live.

> **⚠ `./gradlew :app:run` launches the WORKER, not the CLI.** The `application { mainClass }` in `app/build.gradle.kts` is `yggdrasil.hermod.app.WorkerKt`. The block-authoring CLI is a separate Gradle task — see [§2](#2--author-your-own-block). Don't confuse the two.

**To use the Conductor engine** with the bundled stack, set `HERMOD_CONDUCTOR_URL` before starting the control plane, the only process that reads it. To run a workflow on the Conductor engine you need a separate Conductor task-worker process, started with `./gradlew :conductor-runtime:run`:

```bash
# In the control plane's terminal, before ./gradlew :authoring-api:run:
export HERMOD_CONDUCTOR_URL=http://localhost:18088/api

# In its own terminal: the Conductor task worker.
./gradlew :conductor-runtime:run
```

This override matters: the code default is `http://localhost:8088/api`, but `docker-compose.yml` publishes Conductor on host port **18088**. Without the override, Conductor runs won't reach the bundled container. The Conductor worker reads a different variable, `CONDUCTOR_SERVER_URL` (default `http://localhost:18088/api`, which already matches the bundled Compose stack, so no override is needed for it)—two different variables with two different defaults. See [§4](#4-configure).

The Temporal worker (`./gradlew :app:run`) does not serve Conductor. Without the Conductor worker, Conductor runs have no one polling their tasks. The Conductor task worker (`./gradlew :conductor-runtime:run`, `ConductorWorkerMain.kt`) polls one task type per block id the registry knows: the builtins and every published plugin block, plus the compensation task. It re-reads the registry every `HERMOD_WF_POLL_MS` ms (default 3000), and a block published while it runs gets a poller without a restart. If the registry DB is down at boot, it serves the builtins and adds plugins on a later refresh. A plugin's handler is loaded from its stored jar path, which is absolute, in an isolated classloader on first execution, as the Temporal worker does.

---

## 2. ★ Author your own block

This is the centerpiece. A **block** is the unit you compose workflows from, and you can add one **without editing Hermod's source**.

### The model: a contract + a handler

A block is two things:

1. A **YAML contract manifest** that *declares*, once, the block's resilience policy — its idempotency strategy, its compensation (reversibility + kind), its retry budget, its human-gating, and its side-effects. The runtime **derives** all durable behaviour (idempotent convergence, reverse-order saga compensation, retry, approval gating) generically from this contract — there is no per-workflow orchestration code. The full contract-field-to-behaviour mapping is in [`docs/architecture.md` §3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience).
2. A **`Block` handler** (a Kotlin class) that implements the actual `forward` work and, optionally, `compensate`.

### The `Block` interface

From `sdk/src/main/kotlin/hermod/sdk/Block.kt`:

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

**Step 1 — scaffold a plugin project.** The CLI is a custom Gradle `JavaExec` task named `cli` (mainClass `yggdrasil.hermod.app.CliKt`) defined in `app/build.gradle.kts`. Run it as `./gradlew :app:cli --args="..."`:

```bash
./gradlew :app:cli --args="scaffold block io.example.greet"
```

This generates a standalone plugin project (default dir `io-example-greet/`): `build.gradle.kts`, `settings.gradle.kts`, `src/main/kotlin/.../GreetBlock.kt`, and `src/main/resources/manifests/io.example.greet.yaml`.

> The CLI's printed next-steps say `hermod-cli publish ...`. That `hermod-cli` shorthand is **not** runnable — translate every such line to `./gradlew :app:cli --args="publish ..."`.

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

**Step 4 — build and publish the jar.** Inside the plugin project, publish the SDK to mavenLocal first if needed, then build the jar. The plugin depends on the SDK as `compileOnly("yggdrasil.hermod:sdk:0.2.0")` (see `examples/greet-plugin/build.gradle.kts`) — the host provides the SDK at runtime, so the jar carries only *your* classes:

```bash
# from the host repo root, if yggdrasil.hermod:sdk isn't in mavenLocal yet:
./gradlew :sdk:publishToMavenLocal

# inside the plugin project:
./gradlew publishToMavenLocal   # publishes your plugin to mavenLocal
./gradlew jar                   # builds build/libs/<your>.jar
```

Then publish the jar to the Hermod registry via the CLI. **Pass an absolute path:** the `:app:cli` task runs with `workingDir = app/`, so a *relative* jar path would resolve under `app/`, not your plugin dir.

```bash
./gradlew :app:cli --args="publish /ABSOLUTE/path/to/build/libs/io-example-greet.jar"
```

Useful companion commands:

```bash
./gradlew :app:cli --args="list"            # list all registered blocks (builtins + plugins)
./gradlew :app:cli --args="compile ot-recipe-apply"   # compile a workflow to IR
```

**Step 5 — it appears in the palette; compose and run.** After publishing, the freshly published block **appears in the authoring-api palette without restarting the API**, because the palette is a live read of the block registry (`registry.list()` is a DB query). Open the Compose tab, drag your block in, wire it, save (deploys a versioned definition), then run it from the Console tab.

> **Scope of the "no code edit" claim.** Do not over-claim worker hot-reload. The block is published, isolation-loaded, and run **without editing Hermod's source**, and it appears in the palette **without restarting the authoring-api**. It does **not** hot-reload into an already-running worker.

### Proof this actually works

`scripts/run-add-block-gate.sh` is an objective gate that proves an external jar is **published, classloader-isolation-loaded, and run inside a durable saga, with no code edit**. It publishes the jar via `./gradlew :app:cli --args="publish $PLUGIN_JAR_ABS"` (absolute path, as above) and asserts the log shows `[PluginLoader] loaded … via URLClassLoader`, `[plugin] forward io.example.greet`, and `completed=true`. Note this gate is **publish-then-start** — it loads the plugin when the worker starts, so it does **not** prove worker hot-reload.

---

## 3. Compose & operate (the UI)

Open **http://localhost:5173**. The SPA proxies `/api` to the `:authoring-api` control plane on 18090 (`authoring-ui/vite.config.ts`).

**Compose tab.** The palette is projected from the block registry (your published blocks plus the 9 builtins: `db.read`, `transform.map`, `db.upsert`, `notify.email`, `actuate`, `merge`, `opcua.write`, `opcua.call`, `delegate.score`). Drag blocks onto the React Flow canvas, wire them, and **save** — saving deploys a **versioned workflow definition**. When you run, you pick the **engine** (Temporal or Conductor).

![Compose a workflow on the canvas](demo/01-compose.gif)

**Console tab.** Shows **run history**, **per-node lighting** (live node states), the **compensation timeline** (reverse-topological unwinding when a run fails before an irreversible step), and **operator interventions** — approve, reject, **retry**, **abort** — at human gates. The same console observes runs on **both engines**.

![Run it — each node lights up as it completes; approve the irreversible gate](demo/02-run.gif)

The canonical scenario to try is `ot-recipe-apply` (`sensorRead → recordPlan → interlockAck / preflight → applyPLC`, where `applyPLC` is `IRREVERSIBLE` and human-gated). On the failure path, completed reversible steps compensate in reverse order and the PLC never fires. Full walkthrough: [`docs/scenario-ot-actuation-demo.md`](scenario-ot-actuation-demo.md).

---

## 4. Configure

### Environment keys

| Key | Purpose |
|---|---|
| `HERMOD_DB_URL` | Postgres JDBC URL for the saga/registry DB. Default `jdbc:postgresql://localhost:15432/hermod` (the bundled Compose Postgres). |
| `HERMOD_DB_USER` | DB user. Default `hermod`. |
| `HERMOD_DB_PASS` | DB password. Default `hermod`. |
| `HERMOD_CONDUCTOR_URL` | Conductor API base URL. Code default `http://localhost:8088/api`; the bundled Compose stack publishes Conductor on host port 18088, so use `http://localhost:18088/api` with it. Read only by the control plane. (**override needed, see the callout below**) |
| `HERMOD_PLUGIN_DIR` | The directory where published plugin jars are stored and loaded from (stored as `<id>/<version>/<sha256>.jar`). Default `registry-store`, relative to the process's working directory. |
| `HERMOD_PUBLISH_DISABLED` | Plugin publish switch. Setting `true` (or `1`) closes `POST /api/publish` with 403, so no new plugin code is accepted. Default: publish open. Read by the control plane. |
| `HERMOD_BIND_ADDRESS` | The address the control plane listens on. Default `127.0.0.1` (loopback only). Read by the control plane. |
| `HERMOD_WORKFLOWS_DIR` | Optional directory of workflow YAML files loaded at start, next to the bundled `workflows/*.yaml`; a file with the same name replaces the bundled one. Workflows saved from the Compose tab are stored in Postgres (table `workflow_def`), not here. Read by the worker. |
| `HERMOD_WF_POLL_MS` | How often, in ms, the worker polls Postgres for newly deployed workflow definitions (Temporal) or re-reads the registry for published plugin blocks (Conductor). Default 3000. Read by the Temporal and Conductor workers. |
| `HERMOD_WORKER_NAME` | The name the worker prints in its log lines. Default `worker-1` (`conductor-worker-1` for the Conductor worker). Read by the worker and the Conductor worker. |
| `CONDUCTOR_SERVER_URL` | The Conductor server URL. Default `http://localhost:18088/api`, which already matches the bundled Compose stack, so no override is needed for it. Read by the Conductor worker. |
| `HERMOD_FAULT_INJECT` | **Test-only**. When set to any value, it arms the fault-injection toggle the gate scripts use. |
| `HERMOD_TEST_BLOCKS` | **Test-only**. Setting `1` adds a test block for the gate scripts. |

> **Security note.** The control plane has no authentication (proof-of-concept). It therefore listens on loopback
> by default, and publish-time checks never run uploaded plugin code: the handler class is resolved without
> initialization and never instantiated until a block is executed. A published block *does* run, with that
> process's privileges, wherever it is executed (the worker) when a workflow uses it — close the surface with
> `HERMOD_PUBLISH_DISABLED=true` on deployments that must not accept new plugin code. This closes the HTTP
> publish endpoint only; the local CLI (`:app:cli publish`) is unaffected.

#### Adapter, governance and other keys

The keys below configure the OT adapters, governance features, delegation, and LLM assist. Each row names the process that reads it: the worker (`./gradlew :app:run`), the Conductor worker (`./gradlew :conductor-runtime:run`), the CLI (`./gradlew :app:cli`), or the control plane (`./gradlew :authoring-api:run`).

**OPC UA and the apply path**

| Key | Purpose |
|---|---|
| `HERMOD_APPLY_MODE` | Apply path for `opcua.write` / `opcua.call` (case-insensitive, surrounding whitespace ignored). Unset, blank, or `ncmd` (default) → Sparkplug B NCMD over MQTT through Heimdall (R2). `direct` → direct OPC UA (R1), which bypasses Heimdall's policy check; used only when set explicitly, and the worker prints a warning at start. Read by the worker and the Conductor worker when they start; any other value stops them with an error before polling, with no fallback to `direct`. |
| `HERMOD_OPCUA_URL` | OPC UA endpoint. When it is unset, the endpoint is the site model's `endpoint`: `opc.tcp://localhost:48400` in the bundled `ot-site.yaml`, or the endpoint in the `HERMOD_OPCUA_MODEL` file when that is set. That same default applies in every process that connects: the worker and the Conductor worker (direct apply mode), the CLI (`fsm drift-check`, `fsm hold`, `fsm auto-correct-sweep`), and the control plane's drift auto-correct. The processes only load the same site model, and so only share the same default endpoint, when `HERMOD_OPCUA_MODEL` is unset or an absolute path. |
| `HERMOD_OPCUA_MODEL` | Site model file (endpoint, setpoint nodes, activate command). Unset → the bundled `/model/ot-site.yaml` on the classpath. A relative path resolves against the process's working directory; a missing file or invalid YAML throws. Read by the worker and the Conductor worker (when the builtin blocks load), by the CLI and the control plane for FSM state reads, by the control plane at boot when `HERMOD_RECIPE_SETPOINTS` is loaded, and by `:opcua:runSim`. The Gradle tasks start their JVMs in different working directories (`:app:run` and `:app:cli` in `app/`, `:conductor-runtime:run` in `conductor-runtime/`, `:authoring-api:run` in `authoring-api/`), so the processes only load the same site model when `HERMOD_OPCUA_MODEL` is unset or an absolute path. |
| `HERMOD_OPCUA_POLICY` | Command-authorization policy JSON. Unset → the bundled `/model/command-policy.json`. A missing file throws; a `default` other than `deny` / `allow` fails at load. Read by the worker and the Conductor worker (when the builtin blocks load) and by the control plane at boot when `HERMOD_RECIPE_SETPOINTS` is loaded. |
| `HERMOD_OPCUA_SECURITY` | Client security policy. Default `none`, the only value implemented (case-insensitive); any other value fails before connecting. Read wherever an OPC UA client connects. |
| `HERMOD_OPCUA_USER` | OPC UA username. A non-blank user with `HERMOD_OPCUA_PASS` set → username authentication; a user without a password → a warning and anonymous; a password without a user → anonymous. Default: anonymous. Ignition denies anonymous writes. Read wherever an OPC UA client connects. |
| `HERMOD_OPCUA_PASS` | OPC UA password, used only together with `HERMOD_OPCUA_USER`. Set-but-empty counts as a password. |

**Sparkplug B / MQTT (NCMD apply path)**

| Key | Purpose |
|---|---|
| `HERMOD_MQTT_URL` | Broker for the R2 NCMD apply path. Default `tcp://localhost:1883`. Read by the worker and the Conductor worker only on the NCMD path (`HERMOD_APPLY_MODE` unset, blank or `ncmd`). |
| `HERMOD_SPB_GROUP` | Sparkplug group id of the NCMD, QUERY and RESULT topics. Default `Bifrost:Line1`, Heimdall's own default. Same readers as `HERMOD_MQTT_URL`. |
| `HERMOD_SPB_EDGE` | Sparkplug edge node id the NCMDs are sent to (Heimdall's command edge). Default `recipe-edge-cmd`, Heimdall's own default. It must equal the edge Heimdall runs as (its `SPB_EDGE`), or Heimdall never receives the NCMDs and they time out; and Heimdall's policy rules must target that edge, or Heimdall denies every command (deny-by-default). The policy fixture shipped in bifrost (`heimdall/registry/policy.json`) targets `recipe-edge`, so the NCMD gate uses `recipe-edge`. Same readers as `HERMOD_MQTT_URL`. |

**Governance emit, reconciliation and drift auto-correct** (all read by the control plane)

| Key | Purpose |
|---|---|
| `HERMOD_EMIT_MODE` | Turns on the outbound governance-event emit (hermod's own Sparkplug edge node: NBIRTH / NDATA, answers a Rebirth NCMD). Presence check: any value, even empty or `0`, turns it on. Unset → off. |
| `HERMOD_EMIT_MQTT_URL` | Broker for the governance emit. No default; required when `HERMOD_EMIT_MODE` is set, otherwise the control plane fails to boot. |
| `HERMOD_EMIT_GROUP` | Sparkplug group of the emit session. Default `Hermod`. |
| `HERMOD_EMIT_EDGE` | Edge node id of the emit session. Default `Governance`. The MQTT client id is `hermod-<group>-<edge>`. |
| `HERMOD_RECIPE_SETPOINTS` | Path to the published canonical `recipe-setpoints.yaml`. No default. Unset or not a file → the control plane boots with a warning and reconciliations return 409; present but invalid → boot fails. |
| `HERMOD_MODEL_DIR` | Model directory (must contain `fsm/`) for the drift auto-correct FSM specs. No default; without it the scheduled sweep does nothing and `POST /api/autocorrect/sweep` returns 503. The CLI ignores it and uses `<repo root>/model`. |
| `HERMOD_AUTOCORRECT_DISPATCH` | `1` lets the scheduled auto-correct sweep dispatch the corrective `ot-safe-hold` run, which parks at its approval gate. Otherwise the sweep only alarms. Read once at start. |
| `HERMOD_AUTOCORRECT_DISABLED` | `1` turns the scheduled auto-correct sweep off; it wins over `HERMOD_AUTOCORRECT_DISPATCH`. `POST /api/autocorrect/sweep` is not affected and always dispatches. Read once at start. |
| `HERMOD_AUTOCORRECT_STALE_MS` | How long, in ms, a pending correction whose run status cannot be read stays pending before it is marked `FAILED`. Default 3600000 (1 h); an unparsable value silently falls back to the default. |

**Delegation** (read by the worker and the Conductor worker)

| Key | Purpose |
|---|---|
| `HERMOD_DELEGATION_POLICY` | Delegation endpoint allow/deny policy JSON. Unset → the bundled `/model/delegation-policy.json`. A missing file throws; a `default` other than `deny` / `allow` fails at load. Read when the builtin blocks load. |
| `HERMOD_DELEGATION_URL` | Scorer URL for every delegation endpoint. Unset → each endpoint's `url` from the policy. Read when `delegate.score` first runs. |

**LLM assist**

| Key | Purpose |
|---|---|
| `HERMOD_LLM_ASSIST` | LLM port behind `POST /api/fsm/assist`. Exactly `anthropic` → the Anthropic API client, which needs `ANTHROPIC_API_KEY`. Unset or any other value → a deterministic fake. Read by the control plane. |

**Test / gate only**

| Key | Purpose |
|---|---|
| `HERMOD_DELEGATION_SIM_PORT` | Listen port of the standalone scoring simulator (`./gradlew :delegation:runSim`). Default 9099; an unparsable value falls back to the default. |
| `HERMOD_RECONCILER_DISABLED` | `1` turns off the control plane's scheduled run-reconcile sweep; a run is still reconciled when its status is read. Checked on every sweep. Production code, but only gate scripts set it. |
| `HERMOD_SOAK_DISABLED` | `1` turns off the control plane's periodic FSM soak sweep. Read once at start. Production code, but only gate scripts set it. |

The Gradle daemon keeps the environment it started with, so a variable exported after the daemon started would not reach a forked task JVM on its own. `:app:run`, `:app:cli` and `:app:starter` copy every `HERMOD_*` variable from the live shell into the forked JVM (`forwardHermodEnv` in `app/build.gradle.kts`) so the worker and the CLI agree on the plugin store and the DB. `:conductor-runtime:run` and `:conductor-runtime:ctl` copy every `HERMOD_*` variable plus `CONDUCTOR_SERVER_URL` from the live shell into the forked JVM. `:authoring-api:run` also forwards every `HERMOD_*` variable from the live shell, so switches like `HERMOD_PUBLISH_DISABLED` and `HERMOD_BIND_ADDRESS` reach the control plane. To confirm the publish switch took effect, check the control plane's startup log line `[publish] POST /api/publish is DISABLED (hermod.publish.disabled)` (or `... is open`).

### Ports (bundled Docker Compose stack)

| Service | Host port |
|---|---|
| Postgres | 15432 |
| Temporal | 7233 |
| Temporal UI | 18080 |
| Conductor | 18088 |
| authoring-api | 18090 |
| Vite (UI dev) | 5173 |

> **⚠ Conductor URL override (read this).** `HERMOD_CONDUCTOR_URL` defaults to `http://localhost:8088/api` **in code**, but `docker-compose.yml` publishes Conductor on host **18088**. To use Conductor with the bundled stack you **must** set:
> ```bash
> export HERMOD_CONDUCTOR_URL=http://localhost:18088/api
> ```

### Per-run engine

Each run chooses its engine via `RunRequest.engine`, one of `"temporal"` or `"conductor"` (default `temporal`). The Console's run dialog sets this for you.

### Where things live

- **Plugins**: published jars, under `HERMOD_PLUGIN_DIR`.
- **Workflows**: saved (deployed) definitions are rows in Postgres (`workflow_def`); `HERMOD_WORKFLOWS_DIR` only adds YAML files for the worker to load at start.

---

## 5. Limits & license

**License — Apache License, Version 2.0.** OSI-approved open-source license; commercial and noncommercial use are both permitted under Apache-2.0's terms ([`LICENSE`](../LICENSE)).

**Demo-grade, honestly:**

- **Single-process local stack.** One worker, the Compose stack on one host — not a scaled or production deployment, and there is no Kubernetes/production deploy path here.
- **Limited OT connectors.** There is no PLC connector, and the demo `actuate` block drives no hardware. The real OPC UA client is limited to security `None`, optional username authentication, `Double`-only writes, and `opcua.call` as a Boolean trigger plus rising-edge poll (not a Method call); it is tested in this repository only against the embedded Milo simulator. The Ignition gate (`run-ignition-interop-gate.sh`) has no recorded result in this repository, its NodeIds having been browsed from a live Ignition server (2026-07-02); the Sparkplug B / MQTT gate scripts exist but are cross-repo (`sparkplug-governance-lab`, `resequence-twin-lab`) and their far end is again the Milo simulator; nothing has run against field equipment.
- **Conductor overlay is eventually consistent.** The Conductor observation/compensation overlay reflects state with a lag; Temporal is the load-bearing durable engine (crash-recovery/replay is proven on Temporal).

For the full as-built limits and non-goals see [`docs/architecture.md` §10](architecture.md#10-honest-limits-and-non-goals-current).

---

## See also

- [`docs/architecture.md`](architecture.md) — engineering deep-dive ([§3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience) contract mapping, [§9](architecture.md#9-scale-out--deployment-topology) deployment topology).
- [`docs/scenario-ot-actuation-demo.md`](scenario-ot-actuation-demo.md) — the `ot-recipe-apply` walkthrough.
- [`README.md`](../README.md) — project overview and Quickstart.
- [incident-recovery](https://github.com/picasso-robotics/incident-recovery) (formerly koshchei) — the episode outer loop (how to run it, its configuration), which moved there on 2026-10-05.
