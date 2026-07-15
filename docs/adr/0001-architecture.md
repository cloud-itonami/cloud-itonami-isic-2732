# ADR-0001: Harness Advisor ⊣ Cable-Integrity Governor architecture

- Status: Accepted (2026-07-15)
- Repository: `cloud-itonami-isic-2732` (ISIC Rev.5 `2732`)

## Context

Wire/cable/harness manufacturing (crimping, harness assembly,
end-of-line continuity/insulation-resistance screening, harness-
certificate issuance) needs the same governed-actor pattern as the
rest of the cloud-itonami fleet: an untrusted advisor proposes; an
independent governor may HOLD; high-stakes actuation never
auto-commits.

This vertical is the connective upstream/materials stage feeding TWO
existing cloud-itonami manufacturing chains built earlier this
session: smartphone manufacturing (`cloud-itonami-isic-2630`) and
vehicle manufacturing (`cloud-itonami-isic-2910`/`-2920`), alongside
the shared upstream materials actors already built for both --
batteries (`cloud-itonami-isic-2720`), glass (`cloud-itonami-isic-
2310`) and plastics (`cloud-itonami-isic-2220`). See README `Scope
note` for the full cross-reference.

Per ADR-2607152000 (extending ADR-2607151600's automotive pilot), this
actor takes its real-physics-simulation dependency directly on
`kotoba-lang/physics-2d`, with no sibling design-library repo -- the
same shape `cloud-itonami-isic-2930` (auto-parts) established.

## Decision

1. Namespaces live under `harnessworks.*` with the standard
   facts / registry / store / governor / phase / advisor / operation / sim / robotics / export
   shape.
2. Entity is a **cable-run batch** (a manufactured lot of cable/
   harness assemblies of one spec), not a vehicle, part-lot or
   aircraft assembly.
3. Dual actuation on the same entity:
   - `:actuation/ship-cable-run-batch` (robot shipment/handling dispatch draft)
   - `:actuation/issue-harness-certificate` (Cable/Harness Test Certificate draft, per IPC/WHMA-A-620)
4. Double-actuation guards use dedicated booleans
   (`:batch-shipped?`, `:harness-certified?`), never a status
   lifecycle (ADR-2607071320 / 6492 lesson).
5. `cable-run-batch-resistance-out-of-range?` continues the fleet
   two-sided range check family (after testlab / conservation / water
   / steelworks / turbine / automotive / autoparts), applied here to a
   batch's own measured conductor-resistance deviation against its own
   recorded spec bounds.
6. End-of-line defect unresolved is evaluated unconditionally so
   `:end-of-line-quality/screen` itself can HARD-hold (parksafety
   ADR-2607071922 Decision 5 discipline).
7. Spec-basis catalog seeds product classes, not ISO3 jurisdictions
   (see `harnessworks.facts` docstring for why): "AUTO" (automotive
   wiring harness -- IPC/WHMA-A-620, SAE J1128, ISO 6722) and "ELEX"
   (fine-gauge electronics/flex-cable -- UL 758, IPC-A-610); missing
   product classes are uncovered, never fabricated.
8. `harnessworks.robotics/simulate-tensile-pull-test` runs a REAL
   time-stepped `physics-2d` rigid-body simulation of the conductor/
   crimp tensile-pull test (per ADR-2607152000's fleet-wide real-
   engineering-simulation pattern), using the SAME "collision-as-
   tension-limit" honest reinterpretation technique
   `cloud-itonami-isic-2930`'s `autoparts.robotics` established for a
   pull/separation-style test on a collision-only engine. The
   tolerance floor (`min-pull-force-n`, 60 N) is an explicitly
   disclosed REASONED ESTIMATE for the automotive/fine-gauge conductor
   class modeled (AWG 18-20 / 0.5-0.8 mm^2 class), anchored against
   IPC/WHMA-A-620's crimped-terminal pull-force tables and ASTM B3's
   copper-wire tensile-property baseline -- not a verbatim single
   citation.

## Consequences

(+) Wire/cable/harness manufacturing gains a forkable OSS operating
stack with auditable governor holds, and a genuinely time-stepped
physics-backed QA check instead of a symbolic pass/fail.
(+) Reuses langgraph + store dual-backend parity + the fleet's proven
physics-2d real-simulation pattern without inventing a new physics
engine.
(−) No physical plant digital-twin tick in this repo (follow-up domain
data, e.g. giemon-factory style layout, is out of scope here).
(−) Product-class coverage is a starting catalog (AUTO/ELEX only), not
exhaustive.
(−) The physics simulation is a 2D projection with no force-deflection
/spring model (same disclosed limit as every real-physics sibling in
this fleet) -- the crimp's "give" is encoded as a travel distance, not
a compliance curve.

## Related

- Superproject fleet ADR for this promotion: ADR-2607160800
  (`cloud-itonami-isic-2732-harness`)
- ADR-2607151600 (automotive pilot, real engineering-simulation
  integration) / ADR-2607152000 (fleet extension establishing the
  direct-`physics-2d`-dependency, no-design-library-sibling shape this
  actor follows)
- Sibling architecture: `cloud-itonami-isic-2910` docs/adr/0001,
  `cloud-itonami-isic-2930` docs/adr/0001
