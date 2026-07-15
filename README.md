# cloud-itonami-isic-2732

Open Business Blueprint for **ISIC Rev.5 2732**: manufacture of other
electronic and electric wires and cables -- cable-run-batch intake,
per-product-class harness-standard evidence verification, end-of-line
continuity/insulation-resistance quality screening, robot conductor/
crimp tensile-pull-test verification and Cable/Harness Test Certificate
issuance for a community wire/cable/harness plant.

This repository publishes a wire/cable/harness-manufacturing actor --
batch intake, harness-standard evidence checklist verification,
end-of-line-defect screening, robot tensile-pull-test simulation and
harness-certificate finalization -- as an OSS business that any
qualified wire/cable/harness plant can fork, deploy, run, improve and
sell, so a plant keeps its own construction and certification history
instead of renting a closed MES / quality SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **Harness Advisor ⊣
Cable-Integrity Governor**.

## Scope note: the connective upstream stage for two chains

This repository promotes the industry registry's dead placeholder
`gftdcojp/cloud-itonami-C2732` from `:maturity :spec` to
`:maturity :implemented`. Wire/cable/harness manufacturing is the
MOST directly connective materials stage this session has built:
automotive wiring harnesses and smartphone internal flex-cables/fine-
gauge wiring are both fundamentally wire-and-cable manufacturing
outputs. This actor is the missing upstream/connective stage feeding
BOTH chains this session has been building out:

- **automotive wiring harnesses** -> `cloud-itonami-isic-2910`
  (manufacture of motor vehicles) / `cloud-itonami-isic-2920`
  (manufacture of bodies for motor vehicles, trailers and semi-
  trailers) -- connecting the body, powertrain and battery.
- **smartphone internal flex-cables/fine-gauge wiring** ->
  `cloud-itonami-isic-2630` (manufacture of communication equipment)
  -- connecting the battery, display and logic board.
- **both product classes terminate at `cloud-itonami-isic-2720`**
  (manufacture of batteries and accumulators) -- every wiring harness/
  cable this actor ships ultimately connects to a battery connection
  point.

This is the same downstream/adjacent-consumer cross-referencing
pattern `cloud-itonami-isic-2720` (battery)/`cloud-itonami-isic-2310`
(glass)/`cloud-itonami-isic-2220` (plastics) established earlier this
session as shared upstream materials stages for these same chains.

Distinct from:
- `cloud-itonami-isic-2910`/`-2920` -- vehicle/body **assembly**
  (consumes wiring harnesses, does not manufacture them)
- `cloud-itonami-isic-2630` -- communication-equipment **assembly**
  (consumes flex-cables, does not manufacture them)
- `cloud-itonami-isic-2720` -- battery **manufacturing** (a downstream
  termination point for this actor's cable/harness output, not a wire/
  cable manufacturer itself)

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (crimping, harness
assembly, end-of-line continuity/insulation-resistance scan) operate
under an actor that proposes actions and an independent **Cable-
Integrity Governor** that gates them. The governor never issues a
harness certificate itself; `:high`/`:safety-critical` actions
(`:actuation/ship-cable-run-batch`, `:actuation/issue-harness-
certificate`) require human sign-off.

**Robot process simulation is a REAL time-stepped physics simulation,
not just a flag** (ADR-2607152000, extending ADR-2607151600):
`harnessworks.robotics` walks every cable-run batch through a robot-
executed continuity/tensile-pull/insulation-resistance verification
mission (`kotoba.robotics` mission/action/telemetry-proof contracts)
built directly on a real, tested `kotoba-lang/physics-2d` time-stepped
rigid-body simulation of the **conductor/crimp tensile-pull test** --
a real, standard wire/cable/harness QA test (IPC/WHMA-A-620 specifies
minimum pull-force requirements for crimped terminal connections by
wire gauge; ASTM B3 is the related copper-conductor tensile-property
baseline) -- before `:actuation/ship-cable-run-batch` is proposable.
The Cable-Integrity Governor independently re-derives the batch's own
REAL simulated peak pull force against a disclosed tolerance floor
from ground-truth fields, never trusting the mission's self-reported
verdict alone.

`physics-2d`'s `world-step` only natively resolves bodies that are
APPROACHING/colliding -- it has no notion of a body SEPARATING under
tension. This actor uses the SAME honest "collision-as-tension-limit"
reinterpretation technique `cloud-itonami-isic-2930` (auto-parts)
established for its own weld-joint/fastener proof-load pull test: a
moving `:pull-clamp` body travels at a controlled tensile-test
crosshead rate across the cable's compliant stretch distance, then
collides with a static `:limit-boundary` body representing the point
of conductor/crimp failure. The peak deceleration read off that
collision, times the batch's own recorded effective participating
mass, is `:sim-peak-pull-force-n` -- REAL, derived from the actual
simulated trajectory, never invented. See `harnessworks.robotics` ns
docstring for the full disclosed engineering-prior confidence levels
(the 60 N minimum-pull-force floor is an explicitly-disclosed REASONED
ESTIMATE for the automotive/fine-gauge conductor class modeled, not a
verbatim single-table citation).

## Core contract

```text
cable-run-batch intake + harness-standard-rules verify + end-of-line quality screen
  -> Harness Advisor proposal
  -> Cable-Integrity Governor (HARD holds un-overridable)
  -> phase gate (actuation always escalates)
  -> human approval for high stakes
  -> append-only ledger + draft records
```

## Actuation honesty

Shipping a cable-run batch onward and issuing a Cable/Harness Test
Certificate produce **unsigned draft records and ledger facts only**.
This actor does not talk to real plant control systems or OEM/
inspector portals. Signature and hardware dispatch are the wire/cable/
harness plant's own acts.

## Ops

| Op | Effect |
|---|---|
| `:cable-run-batch/intake` | normalize batch directory patch (phase 3 may auto-commit when clean) |
| `:harness-standard-rules/verify` | per-product-class Cable/Harness Test Certificate evidence checklist (always human) |
| `:end-of-line-quality/screen` | end-of-line continuity/insulation-resistance defect screen (HARD hold if unresolved) |
| `:robotics/simulate-tensile-pull-test` | real `physics-2d` conductor/crimp tensile-pull-test simulation (always human; required on file before shipment) |
| `:actuation/ship-cable-run-batch` | draft cable-run-batch-shipment record (always human; HARD hold if robotics-sim missing or independently out-of-tolerance) |
| `:actuation/issue-harness-certificate` | draft Cable/Harness Test Certificate record (always human) |

## Social / regulatory hand-off

```clojure
(require '[harnessworks.store :as store]
         '[harnessworks.export :as export])

(def db (store/seed-db))
(export/audit-package db)           ;; EDN maps for harness-standard/OEM hand-off
(export/package->csv-bundle db)     ;; CSV bundle (batches/ledger/shipments/harness-certificates)
```

Operator console (static sample): `docs/samples/operator-console.html`.

## Develop

```bash
clojure -M:dev:test
clojure -M:lint
clojure -M:dev:run
```

## License

AGPL-3.0-or-later — see `LICENSE`.

## Export audit package (CLI)

```bash
clojure -M:dev:export
# or: clojure -M:dev:export /tmp/audit-2732
```

Writes CSV files under `out/audit-package/` (or the given directory).
