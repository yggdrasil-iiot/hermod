# Using & Extending Koshei

A practical, developer-facing guide to **running Koshei locally, authoring your own block, operating workflows from the console, and configuring the stack**. Every command here is runnable against this repository.

## Who this is for, and an honest framing up front

This guide is for a developer who wants to **run** Koshei on their own machine, **author blocks** for it, **operate** workflows through the UI, and **extend** it.

Two things to know before you start:

- **License — noncommercial.** Koshei is *source-available* under the [PolyForm Noncommercial License 1.0.0](../LICENSE.md). Noncommercial use is freely permitted; **commercial use is reserved**. This is **not** an OSI-approved open-source license — "OSS" here means publicly available and contributable, not OSI Open Source.
- **Maturity — demo-grade.** What you run from this repo is a **single-process local demo**: one worker, the Docker Compose stack on one host. There are **no real OT connectors** (no PLC/OPC/MQTT), **no Kubernetes / production deployment**, and the Conductor observation overlay is **eventually consistent**. It is an engineering artifact that proves the durable-saga core, not a shippable product. See [§5](#5-limits--license).

---

## 1. Run it

Prerequisites: a JDK (the Gradle toolchain auto-provisions JDK 21 via foojay) and **Docker**. Each long-running command below wants its own terminal. These commands mirror the README Quickstart.

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

Then open **http://localhost:5173**, use the **Compose** tab to assemble (or load the bundled `ot-recipe-apply` workflow), and the **Console** tab to run it and watch per-node lighting, reverse-order compensation, and operator interventions live.

> **⚠ `./gradlew :app:run` launches the WORKER, not the CLI.** The `application { mainClass }` in `app/build.gradle.kts` is `koshei.app.WorkerKt`. The block-authoring CLI is a separate Gradle task — see [§2](#2--author-your-own-block). Don't confuse the two.

**To use the Conductor engine** with the bundled stack, export this **before** starting the worker / control plane:

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
| `KOSHEI_DB_URL` | Postgres JDBC URL for the saga/registry DB |
| `KOSHEI_DB_USER` | DB user |
| `KOSHEI_DB_PASS` | DB password |
| `KOSHEI_CONDUCTOR_URL` | Conductor API base URL (**override needed — see callout below**) |
| `KOSHEI_PLUGIN_DIR` | Where published plugin jars are stored/loaded |
| `KOSHEI_PUBLISH_DISABLED` | `true` (or `1`) closes `POST /api/publish` with 403 (no new plugin code accepted). Default: publish open |
| `KOSHEI_BIND_ADDRESS` | Address the control plane listens on. Default `127.0.0.1` (loopback only) |
| `KOSHEI_WORKFLOWS_DIR` | Where workflow definitions live |
| `KOSHEI_WF_POLL_MS` | Workflow poll interval (ms) |
| `KOSHEI_WORKER_NAME` | Worker identity/name |
| `KOSHEI_FAULT_INJECT` | **Test-only** — fault injection for gate scripts |
| `KOSHEI_TEST_BLOCKS` | **Test-only** — test block wiring |

> **Security note.** The control plane has no authentication (proof-of-concept). It therefore listens on loopback
> by default, and publish-time checks never run uploaded plugin code: the handler class is resolved without
> initialization and never instantiated until a block is executed. A published block *does* run, with that
> process's privileges, wherever it is executed (the worker) when a workflow uses it — close the surface with
> `KOSHEI_PUBLISH_DISABLED=true` on deployments that must not accept new plugin code. This closes the HTTP
> publish endpoint only; the local CLI (`:app:cli publish`) is unaffected.

The `:app:run`, `:app:cli`, and `:app:starter` tasks forward these env keys from the live shell into the forked task JVM (see the `forwardKosheiEnv` block in `app/build.gradle.kts`), so the worker and the CLI agree on the plugin store and DB. `:authoring-api:run` now forwards every `KOSHEI_*` variable from the live shell as well, so switches like `KOSHEI_PUBLISH_DISABLED` and `KOSHEI_BIND_ADDRESS` reach the control plane even on a warm Gradle daemon.

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

- **Plugins** — published jars live under `KOSHEI_PLUGIN_DIR`.
- **Workflows** — definitions live under `KOSHEI_WORKFLOWS_DIR`.

---

## 5. Limits & license

**License — PolyForm Noncommercial 1.0.0.** Source-available; **noncommercial use only — commercial use is reserved** ([`LICENSE.md`](../LICENSE.md)). Not OSI-approved open source.

**Demo-grade, honestly:**

- **Single-process local stack.** One worker, the Compose stack on one host — not a scaled or production deployment, and there is no Kubernetes/production deploy path here.
- **No real OT connectors.** There are no PLC / OPC / MQTT integrations; the `actuate` block models an irreversible actuation for the demo, it does not drive real hardware.
- **Conductor overlay is eventually consistent.** The Conductor observation/compensation overlay reflects state with a lag; Temporal is the load-bearing durable engine (crash-recovery/replay is proven on Temporal).

For the full as-built limits and non-goals see [`docs/architecture.md` §10](architecture.md#10-honest-limits-and-non-goals-current).

---

## See also

- [`docs/architecture.md`](architecture.md) — engineering deep-dive ([§3](architecture.md#3-the-keystone--block-contract-drives-derived-resilience) contract mapping, [§9](architecture.md#9-scale-out--deployment-topology) deployment topology).
- [`docs/scenario-ot-actuation-demo.md`](scenario-ot-actuation-demo.md) — the `ot-recipe-apply` walkthrough.
- [`README.md`](../README.md) — project overview and Quickstart.
