# model/ — Git-canonical model authority

This directory is the **single source of truth** for all site/asset models.
The `:opcua` Gradle subproject copies the entire `model/` tree into the classpath at build time
(`processResources`); there are no committed copies elsewhere in the repo.

## Runnable site models

A *runnable* site model (e.g. `ot-site.yaml`) describes the OPC-UA endpoint, setpoint nodes
(all `Double` in R1 — the only type the R1 write-path handles), and the activate command/done-node
pair. It is selected at runtime via the `KOSHEI_OPCUA_MODEL` environment variable:

```
KOSHEI_OPCUA_MODEL=/path/to/my-site.yaml   # absolute path to a runnable site model
```

If the variable is not set, the default classpath resource `/model/ot-site.yaml` is loaded.
Multiple runnable site files may coexist in this directory; select the one for a given deployment
with the env var.

The `KOSHEI_OPCUA_POLICY` env var selects the command-policy JSON in the same way
(default: `/model/command-policy.json` on the classpath).

## Activate DONE-clear (`doneClear`)

The `activate` block's optional `doneClear` token (see `ot-site.yaml`) declares how the equipment's
done-bit clears after a confirmed activate, so the *next* activate sees a fresh rising edge instead of
failing closed on a stale `done=true`:

| mode | who clears `done` | how | R1 support |
|------|-------------------|-----|-----------|
| `on-release` | equipment | clears `done` when the master de-asserts the trigger (standard rung handshake) | ✅ implemented |
| `explicit-reset` | equipment | latches `done` until a separate reset/ack node is written | declared, fail-closed |
| `master-clears` | master (koshei) | master writes `done=false` directly (where `done` is client-writable) | declared, fail-closed |
| level-triggered | n/a | `done` mirrors state, not an edge | not modeled (future) |
| latching | equipment | `done` stays true until a separate physical reset (e.g. an operator ack) | not modeled (future) |

Only `on-release` is implemented in the R1 direct apply path (`OpcUaApplyPort.call`). Absent
`doneClear` defaults to `on-release`. An unknown token fails model validation; a known-but-unimplemented
token (`explicit-reset` / `master-clears`) is accepted by the model but **fails closed at apply time**
(`ok=false`, no actuation) rather than silently defaulting.

## model/templates/ — illustrative, non-runnable exemplars

`model/templates/` contains schema/standards exemplars that demonstrate how real-world standards
map to the Koshei model format. These files are **not** runnable — they may use OPC-UA types
(e.g. `Int`, `Method`) that R1's write-path does not yet support (typed write-path is R4).

| File | Standard | Runnable? |
|---|---|---|
| `templates/packml-unit.yaml` | PackML/ISA-88 unit L0 interface (real codes, synthetic ranges) | No (R4) |

Do not set `KOSHEI_OPCUA_MODEL` to a path under `templates/`; the templates will fail model
validation at first `forward()` by design.
