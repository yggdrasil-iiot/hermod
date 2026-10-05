# Ignition OPC-UA Interop Runbook

One-time manual setup so `scripts/run-ignition-interop-gate.sh` can prove koshei governs a **real
Ignition OPC-UA server** (not the embedded Milo sim). Ignition-side steps are GUI work; the gate is
automated. This is **not** CI-hermetic (Ignition is licensed) — it is a re-runnable, evidence-backed
interop proof.

## 0. What to install / download
For **this** track you download essentially one thing — Ignition. Everything else is bundled or already running.

- **Ignition** (the only download) — **standard trial**, NOT Maker. Get the Windows installer from
  <https://inductiveautomation.com/downloads>, install, open `http://localhost:8088`, create the admin
  account; the **2-hour trial auto-starts** (reset anytime, unlimited — harmless for a demo). The
  **OPC-UA Server module ships bundled** — no separate download.
  - If you already installed Ignition 8.3.x for the MQTT/Sparkplug interop asset, **just launch it —
    nothing to download**; reset the trial timer if it expired.
- **Cirrus Link MQTT modules (Transmission/Engine) — NOT needed.** Those were for the Sparkplug/MQTT
  interop; this track is pure OPC-UA.
- **koshei stack** (Postgres `15432` + Temporal) — already running via Docker; no new install.
- **NodeId browsing** (step 4) — the bundled Ignition **Designer** has an OPC Browser; no extra tool is
  required (UaExpert is an optional alternative).

## 0.1 Before you run the gate
- Ignition running (`http://localhost:8088`), trial active.
- koshei stack up (the same Postgres/Temporal the other gates use).
- `KOSHEI_OPCUA_SECURITY` unset (defaults to `none`) — the Ignition OPC-UA server is configured for
  `None` + anonymous below (dev). Cert-based security is a documented follow-up.

## 1. Configure the OPC-UA server (None + anonymous + **Expose Tag Providers**)
Gateway → **Config → OPC UA → Server Settings**:
- Ensure the server is running (default endpoint `opc.tcp://localhost:62541`). Port 62541 is
  Ignition's default OPC UA port and the gate's default `KOSHEI_OPCUA_URL`, not koshei's code
  default; with `KOSHEI_OPCUA_URL` unset, the endpoint is the site model's `endpoint`:
  `opc.tcp://localhost:48400` in the bundled `ot-site.yaml`, or the endpoint in the
  `KOSHEI_OPCUA_MODEL` file when that is set, and the worker, the CLI and the drift auto-correct all
  use it.
- Under security, **allow the `None` policy and anonymous access** (dev only).
- **CRITICAL — enable "Expose Tag Providers".** By default Ignition's OPC-UA server exposes only
  *device* connections, NOT tag-provider tags; with this OFF an external UA client (koshei) cannot see
  the memory tags created below and the gate's **T1 will time out**. Enabling it publishes the tag
  providers in a dedicated namespace for external UA clients (all-or-nothing; no per-tag filtering —
  fine for this dev interop proof).

Confirm the endpoint URL/port and put it in `KOSHEI_OPCUA_URL`.

**Write authentication (important).** Ignition grants anonymous clients READ but **denies WRITE**
(`Bad_UserAccessDenied`). The gate therefore authenticates as Ignition's **built-in OPC-UA user
`opcuauser` / `password`** (write-permitted; it lives in the OPC-UA server's own user source, which is
why there's no user to add under the main Gateway users). The gate script
(`run-ignition-interop-gate.sh`) defaults to those values when `KOSHEI_OPCUA_USER` and
`KOSHEI_OPCUA_PASS` are unset; koshei's own code default is anonymous. No action needed if that default exists;
override with `KOSHEI_OPCUA_USER` / `KOSHEI_OPCUA_PASS` env vars to use a different write-permitted user.
Transport stays `SecurityPolicy.None` (dev); cert transport is the deferred follow-up.

## 2. Create four writable memory tags
Designer → a tag provider (e.g. `default`), folder `koshei/Line1`, create **Memory tags**:
| Tag | Data type | Notes |
|---|---|---|
| `Rpm` | Float8 / Double | client-writable setpoint |
| `Temp` | Float8 / Double | client-writable setpoint |
| `ApplyRecipe` | Boolean | client-writable trigger (koshei writes true, then de-asserts false) |
| `ApplyDone` | Boolean | rises on ApplyRecipe rising edge, clears on release (rung script below) |

All four must be **exposed via the OPC-UA server** and **client-writable**.

## 3. The rung handshake — a Gateway Tag Change Script
Gateway Events → **Tag Change**, tag path = `ApplyRecipe`. Script (Jython):
```python
# Rung: ApplyDone follows ApplyRecipe (rising -> true, release -> false), so koshei's call()
# sees a false->true rising edge and, after de-asserting the trigger, ApplyDone rearms.
if not initialChange:
    system.tag.writeBlocking(["[default]koshei/Line1/ApplyDone"], [currentValue.value])
```
(Adjust the tag path to your provider/folder.)

## 4. Record the real NodeIds
`model/ot-site-ignition.yaml` already holds NodeIds resolved from a live Ignition server on
2026-07-02 (`ns=2;s=[default]/koshei/Line1/<tag>`); namespace indices are server-specific, so
re-browse with the Designer OPC Browser (or any OPC UA client) and update them along with
`endpoint:` if yours differ. `model/recipe-setpoints-ignition.yaml` carries the same NodeIds and must
match, because the control plane cross-validates it against the site model at startup and fails
closed on a mismatch. Commit that edit.

## 5. Run the gate
```bash
KOSHEI_OPCUA_URL="opc.tcp://localhost:62541" bash scripts/run-ignition-interop-gate.sh
```
The gate itself exports `KOSHEI_OPCUA_MODEL` and `KOSHEI_RECIPE_SETPOINTS` as absolute paths to
`model/ot-site-ignition.yaml` and `model/recipe-setpoints-ignition.yaml`, overriding any value in
your shell, and defaults `KOSHEI_OPCUA_URL` to `opc.tcp://localhost:62541`.

Expect `[GATE] PASS run-ignition-interop-gate.sh` — T1 (staged+activated, confirm-by-read on Ignition,
`command_audit` keyed to the run), T2b (reject → `opcua.write.compensate` RESTORE, activate never
fired), T3 (rpm=9999 → EURANGE_REJECT), T4 (unlisted node → DENIED).

## 6. Capture evidence
Save the gate log and an Ignition **Tag Browser** screenshot showing `Rpm`/`Temp` at the staged values
during T1 and restored after T2b.

## Notes (design invariants)
- **Single writer.** During a governed run koshei is the single writer of these tags — do NOT have
  another Ignition script/transaction-group write `Rpm`/`Temp`/`ApplyRecipe` concurrently, or the
  confirm-by-read/RESTORE assertions become non-deterministic.
- **Koshei-down ≠ line-down.** Killing the koshei worker mid-run leaves the Ignition tags at their last
  value (koshei is D4-above-OT); recovery is the explicit, operator-approved compensation step (T2b),
  never a silent auto-rollback.
