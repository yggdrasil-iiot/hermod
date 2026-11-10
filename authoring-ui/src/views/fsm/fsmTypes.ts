// FSM spec data model — mirrors Kotlin yggdrasil.hermod.opcua.FsmSpec (opcua/.../FsmSpec.kt).
// driver is typed `string` (not a union) so a malformed parsed value is representable
// and can be flagged by validateFsm — exactly as Kotlin stores `driver: String`.

export interface FsmState {
  id: string;
  code: number;
}

export interface FsmAction {
  workflow: string;
}

export interface FsmTransition {
  id: string;
  from: string;
  to: string;
  command: string | null; // null = reactive/PLC-driven transition; must survive round-trip
  driver: string; // "hermod" | "field" (validated, not type-enforced)
  action?: FsmAction; // present iff driver === "hermod"
}

export interface FsmSpec {
  name: string;
  unit: string;
  version: string; // "" = omitted/legacy (base packml-line1.yaml)
  stateNode: string;
  states: FsmState[];
  transitions: FsmTransition[];
}
