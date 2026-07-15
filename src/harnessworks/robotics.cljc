(ns harnessworks.robotics
  "Robot-executed continuity/insulation-resistance verification -- the
  concrete, actor-level realization of ADR-2607011000's robotics
  premise and ADR-2607142800's robotics-process-simulation pattern
  (established by `cloud-itonami-isic-2910`'s `automotive.robotics`,
  extended fleet-wide by ADR-2607152000 via `cloud-itonami-isic-2930`'s
  `autoparts.robotics`) for THIS actor's own `harnessworks.facts`
  requirement that a cable-run-batch-shipment proposal cite an
  IPC/WHMA-A-620 workmanship acceptance report actually on file -- not
  merely a self-reported checklist string.

  This ns builds directly on a REAL engineering simulation, per
  ADR-2607152000: a genuine time-stepped `physics-2d` rigid-body
  simulation of the CONDUCTOR/CRIMP TENSILE-PULL TEST -- a real,
  standard wire/cable/harness QA test (IPC/WHMA-A-620 specifies
  minimum pull-force requirements for crimped terminal connections by
  wire gauge; ASTM B3, Standard Specification for Soft or Annealed
  Copper Wire, is the related copper-conductor tensile-property
  baseline the crimp/termination is built onto). This vertical has no
  sibling design-library repo (unlike automotive's `kami-engine-
  vehicle-designer` pairing), so the physics module is built DIRECTLY
  in this ns, taking a real git-coordinate dependency on
  `kotoba-lang/physics-2d` alone (see deps.edn).

  HONEST REINTERPRETATION TECHNIQUE (identical in spirit to
  `cloud-itonami-isic-2930`'s `autoparts.robotics`, itself mirroring
  automotive's disclosed 'reaching end-of-tether, not literally
  crashing into a barrier' trick in `vdesign.simphysics`): `physics-
  2d`'s `world-step` ONLY natively resolves bodies that are
  APPROACHING/colliding -- it has no notion of a body SEPARATING under
  tension, so there is no direct way to simulate 'pull the crimped
  terminal off the conductor until it releases' with this engine's
  collision-only impulse resolver. This ns reframes the SAME physical
  event as an approach instead: `:pull-clamp` (the tensile-test
  crosshead jaw gripping the terminal end of the cable) starts right
  beside `:cable-anchor` (a static body anchoring the cable-run
  batch's own fixed end) and moves steadily AWAY from it at a real,
  controlled tensile-test crosshead rate -- but a THIRD, static
  `:limit-boundary` body is placed exactly `travel-to-failure-m` (the
  conductor/crimp's own real compliance/stretch distance before it
  fails) beyond the pull-clamp's start. As the pull-clamp travels, it
  is really the CRIMP/CONDUCTOR running out of give -- `physics-2d`
  only knows how to render that as the pull-clamp's leading face
  reaching the limit-boundary's near face, at which point its native
  inelastic (restitution 0) collision resolution zeroes the
  pull-clamp's velocity in a SINGLE tick -- exactly the 'terminal
  holds, then suddenly arrests/releases' event a real crimp pull test
  exhibits at peak load. The peak deceleration read off that tick,
  times the batch's own recorded effective participating mass
  (`:crimp-effective-mass-kg` -- the moving pull-clamp + the locally-
  engaged crimp/conductor material), is `:sim-peak-pull-force-n`
  (Newtons) -- REAL, derived from the actual simulated trajectory,
  never invented.

  Disclosed engineering priors (this ns's own, not measured facts --
  same discipline as `autoparts.robotics`'s own disclosed constants):

  - `test-speed-mps` models a genuine, established test category --
    the same single-tick 'boxcar' discrete-collision technique that
    `autoparts.robotics` disclosed only produces a physically
    meaningful reading at a dynamic/high-rate pull speed (peak-decel
    scales with test-speed^2 / travel-to-failure), NOT the slow
    mm/min quasi-static crosshead speed a real IPC/WHMA-A-620 crimp
    pull test typically runs at. This model is therefore honestly a
    controlled-rate DYNAMIC-EQUIVALENT reframing of the quasi-static
    standard test, chosen because this engine has no continuous
    force-vs-displacement (spring/compliance) model to run a genuine
    slow pull with -- the SAME disclosed limitation `autoparts.
    robotics` states for its own weld/fastener pull test.
  - `travel-to-failure-m` is a representative low-single-digit-
    millimeter conductor/crimp compliance/stretch distance -- a real,
    disclosed order of magnitude for crimp-barrel/conductor-strand
    slip-and-release displacement in tensile testing of small-gauge
    stranded conductors.
  - `initial-grip-slack-m` is a small, real, disclosed test-fixture
    grip-seating/alignment slack the pull-clamp travels BEFORE the
    crimp itself begins to bear load -- present only so the simulated
    trajectory captures a real pre-load approach phase, not just the
    single stopping tick (mirrors `autoparts.robotics/initial-grip-
    slack-m`).
  - `min-pull-force-n` is a REASONED ESTIMATE, explicitly disclosed as
    such (not a verbatim single-table citation): IPC/WHMA-A-620's
    published crimped-terminal pull-force tables scale with wire
    gauge, and commonly-cited figures for small-to-medium automotive-
    gauge conductors (roughly the AWG 18-20 / 0.5-0.8 mm^2 class this
    ns models, typical of automotive signal/lighting circuits and
    smartphone-adjacent fine-gauge harness runs) sit in the
    low-tens-of-newtons range. 60 N is a plausible order-of-magnitude
    floor for that gauge class, NOT a literal transcription of one
    specific IPC/WHMA-A-620 table row (ADR-2607152000 explicitly
    allows this reasoned-estimate confidence level when no existing
    on-file field fits a FORCE reading better, matching `autoparts.
    robotics/min-proof-load-n`'s own disclosed confidence level).

  Like `autoparts.robotics`'s force reading (unlike automotive's mass-
  invariant `:sim-decel-g` against an immovable barrier), the quantity
  reported HERE is a FORCE (Newtons), so `:crimp-effective-mass-kg`
  DOES directly scale `:sim-peak-pull-force-n` (force = mass x
  deceleration) -- intentional: a real load-cell force reading
  legitimately depends on the physical scale of the crimp/conductor
  under test, not an accident of chosen units. This is also this ns's
  OWN honest failure-injection lever: a cable-run batch whose recorded
  `:crimp-effective-mass-kg` is inconsistent with its spec'd gauge
  class (e.g. an under-crimped or wrong-gauge terminal that engaged far
  less conductor/crimp-barrel material than the spec calls for) drives
  a real, simulation-derived `:sim-peak-pull-force-n` below
  `min-pull-force-n` -- not a hand-set failing field.

  `pull-force-out-of-tolerance?` independently re-derives the batch's
  OWN recorded `:sim-peak-pull-force-n` against `min-pull-force-n`,
  never from the mission's self-reported result -- the SAME 'ground
  truth, not self-report' discipline `harnessworks.registry/cable-run-
  batch-resistance-out-of-range?` established for conductor-resistance
  deviation. `harnessworks.governor`'s `robotics-simulation-violations`
  calls this ns's independent recheck, never the stored :passed?
  value, before any `:actuation/ship-cable-run-batch` proposal may
  commit.

  Pure data + pure functions -- no real robot I/O, no network.
  `physics-2d/world-step` is itself a pure, fixed-timestep integrator
  (no wall-clock/IO), so this stays exactly as offline/deterministic
  as every other sibling namespace in this actor -- tests and the demo
  run without a network.

  Honest scope (ADR-2607152000, mirroring the no-design-library case
  `autoparts.robotics` already established): this DOES model a real
  time-stepped `physics-2d` rigid-body trajectory for the pull-test
  event. It does NOT model: conductor/crimp material stiffness
  (`physics-2d` has no force-deflection/spring model at all -- the
  crimp's 'give' is encoded purely as a travel DISTANCE, not a
  compliance curve), 3D geometry (2D projection only, same disclosed
  limit as every sibling), a real load-cell/DAQ connection, or a real
  robot controller -- still simulation, not control, the same 'policy,
  not control' boundary `kotoba.robotics`'s docstring already
  establishes."
  (:require [kotoba.robotics :as robotics]
            [physics-2d :as p2d]))

;; ---------------------------------------------------------------------------
;; Platform shims (mirrors physics-2d's/autoparts.robotics's own private
;; sqrt*/abs*/ceil* style, keeping this ns portable .cljc).
;; ---------------------------------------------------------------------------

(defn- abs* [x] (if (neg? x) (- x) x))

(defn- ceil* [x]
  #?(:clj  (Math/ceil (double x))
     :cljs (js/Math.ceil x)))

(def mission-actions
  "The three-step continuity/tensile-pull/insulation-resistance
  verification mission every cable-run batch walks through before
  `:actuation/ship-cable-run-batch` is proposable. All :sense/:actuate
  at :none/:low safety -- verification/QA sensing on a stationary
  batch, not the moving-shipment actuation that is `:actuation/ship-
  cable-run-batch` itself (always :safety-critical -- see
  `harnessworks.governor`)."
  [{:step :continuity-scan               :kind :sense   :safety :none}
   {:step :conductor-crimp-tensile-pull-test :kind :actuate :safety :low}
   {:step :insulation-resistance-scan     :kind :sense   :safety :none}])

;; ---------------------- real pull-test physics constants --------------------

(def ^:const test-speed-mps
  "Controlled pull-clamp crosshead rate (m/s) -- see ns docstring: a
  dynamic-equivalent reframing of IPC/WHMA-A-620's quasi-static crimp
  pull-test procedure, chosen because this single-tick 'boxcar'
  technique cannot honestly render a meaningful force reading at a
  literal quasi-static mm/min crosshead speed."
  1.0)

(def ^:const travel-to-failure-m
  "The conductor/crimp's own real compliance/stretch distance (m)
  before it releases -- see ns docstring: a representative
  low-single-digit-millimeter prior for crimp-barrel/conductor-strand
  slip-and-release displacement."
  0.001)

(def ^:const initial-grip-slack-m
  "Test-fixture grip-seating/alignment slack (m) the pull-clamp
  travels before the crimp itself begins to bear load -- present only
  so the trajectory captures a real pre-load approach phase, mirroring
  `autoparts.robotics/initial-grip-slack-m`."
  0.0003)

(def ^:const pull-clamp-half-w-m
  "Pull-clamp AABB half-width along the pull axis (m) -- a small,
  fixed test-fixture-jaw footprint, not a per-batch geometry input
  (this ns has no CAD/BREP pipeline)."
  0.01)

(def ^:const pull-clamp-half-h-m 0.05)

(def ^:const cable-anchor-half-w-m
  "Cable-run batch's fixed-end anchor AABB half-width (m) -- static
  anchor, never actually collides with anything (the pull-clamp moves
  AWAY from it), present purely as a real Body2D so the simulated
  world honestly contains both ends of the crimp/conductor being
  pulled apart."
  0.01)

(def ^:const cable-anchor-half-h-m 0.05)

(def ^:const limit-boundary-half-w-m
  "Virtual limit-boundary AABB half-width (m) -- the 'end of tether'
  wall the pull-clamp's approach is reframed against; see ns
  docstring."
  0.01)

(def ^:const limit-boundary-half-h-m 0.05)

(def ^:const settle-ticks
  "Extra ticks appended after the pull-clamp is expected to reach the
  limit-boundary, so the trajectory also captures post-contact
  settling. `physics-2d`'s positional correction removes 80% of any
  remaining overlap per tick (`resolve-contact`'s `0.8` factor), so
  residual overlap after `settle-ticks` further ticks is `0.2^settle-
  ticks` of whatever it was at first contact -- 15 ticks converges to
  ~3e-11 (same rationale/constant as `autoparts.robotics/settle-
  ticks`, a genuine physics-2d engine property, not re-derived here)."
  15)

(def ^:const min-pull-force-n
  "Real, disclosed minimum acceptable crimp/conductor pull force (N)
  for the small-to-medium automotive/fine-gauge conductor class this
  ns models -- see ns docstring. 60 N sits in the plausible low-tens-
  of-newtons range commonly cited for AWG 18-20 / 0.5-0.8 mm^2 class
  crimped terminals; a REASONED ESTIMATE, not a literal transcription
  of one specific IPC/WHMA-A-620 table row (ADR-2607152000 explicitly
  allows this when no existing on-file field fits a FORCE reading
  better, matching `autoparts.robotics/min-proof-load-n`'s own
  disclosed confidence level)."
  60.0)

;; ------------------------------ real simulation ------------------------------

(defn run-pull-test
  "Time-steps a REAL `physics-2d` world for the conductor/crimp
  tensile-pull test and returns:

    {:trajectory [{:tick :position :velocity} ...]   ; pull-clamp body only
     :sim-peak-decel-mps2 n :sim-peak-pull-force-n n
     :ticks n :dt n :test-speed-mps n :travel-to-failure-m n}

  `crimp-effective-mass-kg` is the cable-run batch's own recorded
  effective participating mass (moving pull-clamp + locally-engaged
  crimp/conductor material). opts (all optional, for tuning/testing):
  `:test-speed-mps`, `:travel-to-failure-m`, `:initial-grip-slack-m`,
  `:dt` overrides (each defaults to this ns's own constant of the same
  name).

  `:sim-peak-decel-mps2` is the PEAK magnitude of tick-to-tick velocity
  change (along the pull axis) divided by `dt` -- derived from the
  actual simulated velocity trajectory, not invented.
  `:sim-peak-pull-force-n` is `:sim-peak-decel-mps2 *
  crimp-effective-mass-kg` (Newtons) -- see ns docstring for why mass
  legitimately scales this reading."
  [crimp-effective-mass-kg & [{v-opt :test-speed-mps travel-opt :travel-to-failure-m
                                slack-opt :initial-grip-slack-m dt-opt :dt}]]
  (let [v      (double (or v-opt test-speed-mps))
        travel (double (or travel-opt travel-to-failure-m))
        slack  (double (or slack-opt initial-grip-slack-m))
        dt     (double (or dt-opt (/ travel v)))
        anchor-x 0.0
        pull-clamp-x0 (+ anchor-x cable-anchor-half-w-m pull-clamp-half-w-m)
        limit-boundary-x (+ pull-clamp-x0 slack travel pull-clamp-half-w-m limit-boundary-half-w-m)
        approach-m (+ slack travel)
        ticks (long (+ settle-ticks (long (ceil* (/ approach-m (* v dt))))))
        cable-anchor (p2d/make-body {:position [anchor-x 0.0]
                                      :velocity [0.0 0.0]
                                      :mass 0.0
                                      :restitution 0.0
                                      :friction 0.0
                                      :collider (p2d/make-aabb-collider cable-anchor-half-w-m cable-anchor-half-h-m)
                                      :user-data :cable-anchor})
        pull-clamp (p2d/make-body {:position [pull-clamp-x0 0.0]
                                    :velocity [v 0.0]
                                    :mass (double crimp-effective-mass-kg)
                                    :restitution 0.0
                                    :friction 0.0
                                    :collider (p2d/make-aabb-collider pull-clamp-half-w-m pull-clamp-half-h-m)
                                    :user-data :pull-clamp})
        limit-boundary (p2d/make-body {:position [limit-boundary-x 0.0]
                                        :velocity [0.0 0.0]
                                        :mass 0.0
                                        :restitution 0.0
                                        :friction 0.0
                                        :collider (p2d/make-aabb-collider limit-boundary-half-w-m limit-boundary-half-h-m)
                                        :user-data :limit-boundary})
        w0 (p2d/world-new [0.0 0.0])
        [w1 _anchor-id] (p2d/world-add w0 cable-anchor)
        [w2 pull-clamp-id] (p2d/world-add w1 pull-clamp)
        [w3 _limit-id] (p2d/world-add w2 limit-boundary)
        worlds (reductions (fn [w _] (p2d/world-step w dt)) w3 (range ticks))
        trajectory (mapv (fn [tick world]
                            (let [b (nth (:bodies world) pull-clamp-id)]
                              {:tick tick :position (:position b) :velocity (:velocity b)}))
                          (range (count worlds)) worlds)
        vxs (mapv (comp first :velocity) trajectory)
        peak-decel-mps2 (->> (map (fn [va vb] (abs* (/ (- vb va) dt))) vxs (rest vxs))
                              (reduce max 0.0))]
    {:trajectory trajectory
     :sim-peak-decel-mps2 peak-decel-mps2
     :sim-peak-pull-force-n (* peak-decel-mps2 (double crimp-effective-mass-kg))
     :ticks (count trajectory)
     :dt dt
     :test-speed-mps v
     :travel-to-failure-m travel}))

(defn pull-test-telemetry-for
  "Runs the REAL `run-pull-test` `physics-2d` simulation for
  `batch`'s own recorded `:crimp-effective-mass-kg` and returns the
  actual simulated trajectory telemetry: `{:sim-peak-pull-force-n n
  :sim-peak-decel-mps2 n :ticks n :dt n :test-speed-mps n
  :travel-to-failure-m n}`. Pure, deterministic -- the same
  `:crimp-effective-mass-kg` always reproduces the same telemetry."
  [batch]
  (select-keys (run-pull-test (:crimp-effective-mass-kg batch))
               [:sim-peak-pull-force-n :sim-peak-decel-mps2 :ticks :dt
                :test-speed-mps :travel-to-failure-m]))

(defn pull-force-out-of-tolerance?
  "Ground-truth check: does `batch`'s own recorded
  `:sim-peak-pull-force-n` (the REAL `run-pull-test` trajectory
  telemetry already on file for this batch -- see
  `pull-test-telemetry-for`) fall below `min-pull-force-n`? Needs no
  mission run -- its inputs are permanent fields already on the
  batch, the same shape `harnessworks.registry/cable-run-batch-
  resistance-out-of-range?` uses for conductor-resistance deviation."
  [{:keys [sim-peak-pull-force-n]}]
  (and (number? sim-peak-pull-force-n)
       (< sim-peak-pull-force-n min-pull-force-n)))

(defn simulate-tensile-pull-test
  "Run the robot continuity/tensile-pull/insulation-resistance
  verification mission for `batch-id` (`batch` is the full
  cable-run-batch record, incl. `:crimp-effective-mass-kg`). Actually
  runs the REAL engine: `pull-test-telemetry-for` -- the actual
  `physics-2d`-stepped conductor/crimp tensile-pull-test trajectory
  (`:sim-peak-pull-force-n`/`:sim-peak-decel-mps2`).

  Returns {:mission .. :actions [{:action .. :proof ..} ..] :passed?
  bool :sim-peak-pull-force-n n :sim-peak-decel-mps2 n}.
  Deterministic: :passed? is derived from the batch's OWN recorded
  `:crimp-effective-mass-kg` via the REAL simulated trajectory
  (`pull-force-out-of-tolerance?`), never invented or randomized --
  `kotoba.robotics` mandates no network/IO, and a repeatable
  simulation is what makes the governor's independent recheck
  meaningful."
  [batch-id batch]
  (let [telemetry (pull-test-telemetry-for batch)
        out-of-range? (pull-force-out-of-tolerance? (merge batch telemetry))
        reading (if out-of-range? :out-of-tolerance :nominal)
        mission (robotics/mission (str "mission-" batch-id "-tensile-pull-verify")
                                   :robot/tensile-pull-test-cell-1
                                   :conductor-crimp-tensile-pull-verification
                                   :boundaries {:station "end-of-line-tensile-pull-cell"}
                                   :max-steps (count mission-actions))
        actions (mapv (fn [{:keys [step kind safety]}]
                        (let [a (robotics/action (str (:mission/id mission) "-" (name step))
                                                  (:mission/id mission) kind safety
                                                  :params {:step step :batch-id batch-id})]
                          {:action a
                           :proof (robotics/telemetry-proof (:mission/id mission) step reading
                                                             :provenance :simulated)}))
                      mission-actions)]
    {:mission mission
     :actions actions
     :passed? (not out-of-range?)
     :sim-peak-pull-force-n (:sim-peak-pull-force-n telemetry)
     :sim-peak-decel-mps2 (:sim-peak-decel-mps2 telemetry)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does `batch`'s
  OWN current, on-file real `physics-2d`-simulated pull-force
  telemetry (`:sim-peak-pull-force-n`) fall out of tolerance right
  now? Ignores whatever :passed? verdict a prior mission run stored --
  identical in spirit to `harnessworks.registry/cable-run-batch-
  resistance-out-of-range?`'s refusal to trust a proposal's
  self-report."
  [batch]
  (pull-force-out-of-tolerance? batch))
