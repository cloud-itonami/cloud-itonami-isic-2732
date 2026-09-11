(ns harnessworks.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean cable-run batch
  through intake -> harness-standard-rules evidence verification ->
  end-of-line-defect screening -> robot continuity/tensile-pull/
  insulation-resistance mission -> cable-run-batch-shipment proposal
  (always escalates) -> human approval -> commit, then through
  harness-certificate proposal (always escalates) -> human approval ->
  commit, then shows every HARD hold this actor defends against (a
  product class with no spec-basis, an out-of-spec conductor-
  resistance deviation, an actuation attempted before the robot
  tensile-pull-test mission ever ran, a robotics mission on file whose
  independent recheck of the REAL simulated pull force disagrees, an
  unresolved end-of-line defect screened directly via `:end-of-line-
  quality/screen` [never via an actuation op against an unscreened
  batch -- see this actor's own governor ns docstring / the lesson
  `parksafety`'s ADR-2607071922 Decision 5, and every prior sibling's
  ADR-0001 already recorded, most recently `automotive`'s and
  `autoparts`'s], and a double cable-run-batch-shipment/certificate-
  issuance of an already-processed batch) that never reach a human at
  all, and prints the audit ledger + the draft cable-run-batch-
  shipment and harness-certificate records."
  (:require [langgraph.graph :as g]
            [harnessworks.export :as export]
            [harnessworks.store :as store]
            [harnessworks.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== cable-run-batch/intake batch-1 (AUTO, clean; resistance within spec, no EOL defect) ==")
    (println (exec! actor "t1" {:op :cable-run-batch/intake :subject "batch-1"
                                :patch {:id "batch-1" :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}} operator))

    (println "== actuation/ship-cable-run-batch batch-1 before any harness-standard-rules verification -> HARD hold (evidence-incomplete) ==")
    (println (exec! actor "t1b" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator))

    (println "== harness-standard-rules/verify batch-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :harness-standard-rules/verify :subject "batch-1"} operator))
    (println (approve! actor "t2"))

    (println "== end-of-line-quality/screen batch-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :end-of-line-quality/screen :subject "batch-1"} operator))
    (println (approve! actor "t3"))

    (println "== robotics/simulate-tensile-pull-test batch-1 (real physics-2d conductor/crimp pull-test mission; escalates -- human approves) ==")
    (println (exec! actor "t3b" {:op :robotics/simulate-tensile-pull-test :subject "batch-1"} operator))
    (println (approve! actor "t3b"))

    (println "== actuation/ship-cable-run-batch batch-1 (always escalates -- actuation/ship-cable-run-batch) ==")
    (let [r (exec! actor "t4" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)]
      (println r)
      (println "-- human quality engineer approves --")
      (println (approve! actor "t4")))

    (println "== actuation/issue-harness-certificate batch-1 (always escalates -- actuation/issue-harness-certificate) ==")
    (let [r (exec! actor "t5" {:op :actuation/issue-harness-certificate :subject "batch-1"} operator)]
      (println r)
      (println "-- human quality engineer approves --")
      (println (approve! actor "t5")))

    (println "== harness-standard-rules/verify batch-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :harness-standard-rules/verify :subject "batch-2" :no-spec? true} operator))

    (println "== harness-standard-rules/verify batch-3 (escalates -- human approves; sets up the out-of-spec test) ==")
    (println (exec! actor "t7" {:op :harness-standard-rules/verify :subject "batch-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/ship-cable-run-batch batch-3 before robotics simulation -> HARD hold (robotics-simulation-missing) ==")
    (println (exec! actor "t7b" {:op :actuation/ship-cable-run-batch :subject "batch-3"} operator))

    (println "== robotics/simulate-tensile-pull-test batch-3 (real physics-2d pull-test simulation clears the floor; escalates -- human approves) ==")
    (println (exec! actor "t7c" {:op :robotics/simulate-tensile-pull-test :subject "batch-3"} operator))
    (println (approve! actor "t7c"))

    (println "== actuation/ship-cable-run-batch batch-3 (0.35 outside [-0.10,0.10] conductor-resistance-deviation tolerance -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/ship-cable-run-batch :subject "batch-3"} operator))

    (println "== actuation/ship-cable-run-batch batch-5 (robotics-sim on file, but real physics-2d-simulated peak pull force falls below the minimum required floor on independent recheck -- under-crimped/wrong-gauge terminal -> HARD hold) ==")
    (println (exec! actor "t8b" {:op :harness-standard-rules/verify :subject "batch-5"} operator))
    (println (approve! actor "t8b"))
    (println (exec! actor "t8c" {:op :actuation/ship-cable-run-batch :subject "batch-5"} operator))

    (println "== end-of-line-quality/screen batch-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :end-of-line-quality/screen :subject "batch-4"} operator))

    (println "== actuation/ship-cable-run-batch batch-1 AGAIN (double-shipment -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator))

    (println "== actuation/issue-harness-certificate batch-1 AGAIN (double-issuance -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/issue-harness-certificate :subject "batch-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft cable-run-batch-shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))

    (println "== draft harness-certificate records ==")
    (doseq [r (store/certificate-history db)] (println r))

    (println "== social hand-off: audit package counts ==")
    (println (:counts (export/audit-package db)))
    (println "== social hand-off: CSV bundle keys ==")
    (println (keys (export/package->csv-bundle db)))))
