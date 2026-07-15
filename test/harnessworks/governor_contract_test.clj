(ns harnessworks.governor-contract-test
  "The governor contract as executable tests -- the wire/cable/
  harness-manufacturer analog of `cloud-itonami-isic-6512`'s `casualty.
  governor-contract-test`. The single invariant under test:

    Harness Advisor never ships a cable-run batch or issues a harness
    certificate the Cable-Integrity Governor would reject,
    `:actuation/ship-cable-run-batch`/`:actuation/issue-harness-
    certificate` NEVER auto-commit at any phase, `:cable-run-batch/
    intake` (no direct capital risk) MAY auto-commit when clean, and
    every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [harnessworks.store :as store]
            [harnessworks.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a requirements
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :harness-standard-rules/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through end-of-line-defect screening -> approve,
  leaving a screening on file. Only safe to call for a batch whose
  defect status has already resolved -- an unresolved defect
  HARD-holds the screen itself (see
  `end-of-line-defect-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :end-of-line-quality/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(defn- simulate-robotics!
  "Walks `subject` through the robot continuity/tensile-pull/
  insulation-resistance verification mission -> approve, leaving
  `:robotics-sim-verified?` on file. Only meaningful to call for a
  batch whose REAL simulated pull force is actually within tolerance
  -- an out-of-tolerance batch still gets `:robotics-sim-verified?`
  recorded (per whatever the mission itself found), but
  `harnessworks.governor`'s independent recheck HARD-holds regardless
  (see `robotics-simulation-out-of-tolerance-is-held`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-robotics") {:op :robotics/simulate-tensile-pull-test :subject subject} operator)
  (approve! actor (str tid-prefix "-robotics")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :cable-run-batch/intake :subject "batch-1"
                   :patch {:id "batch-1" :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Meridian Automotive Wiring Harness Lot WH-2044" (:batch-name (store/batch db "batch-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest requirements-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :harness-standard-rules/verify :subject "batch-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/requirements-verification-of db "batch-1")))))))

(deftest fabricated-product-class-is-held
  (testing "a harness-standard-rules/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :harness-standard-rules/verify :subject "batch-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/requirements-verification-of db "batch-1")) "no verification written"))))

(deftest ship-cable-run-batch-without-verification-is-held
  (testing "actuation/ship-cable-run-batch before any requirements verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest cable-run-batch-resistance-out-of-range-is-held
  (testing "a batch whose own conductor-resistance deviation falls outside its own spec bounds -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "batch-3")
          _ (simulate-robotics! actor "t5pre2" "batch-3")
          res (exec-op actor "t5" {:op :actuation/ship-cable-run-batch :subject "batch-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:cable-run-batch-resistance-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest end-of-line-defect-is-held-and-unoverridable
  (testing "an unresolved end-of-line defect on a batch -> HOLD, and never reaches request-approval -- exercised via :end-of-line-quality/screen DIRECTLY, not via the actuation op against an unscreened batch (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / automotive's and autoparts's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :end-of-line-quality/screen :subject "batch-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:end-of-line-defect-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/eol-screen-of db "batch-4")) "no clearance written"))))

(deftest ship-cable-run-batch-always-escalates-then-human-decides
  (testing "a clean, fully-verified, in-spec batch still ALWAYS interrupts for human approval -- actuation/ship-cable-run-batch is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "batch-1")
          _ (simulate-robotics! actor "t7pre2" "batch-1")
          r1 (exec-op actor "t7" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, shipment record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:batch-shipped? (store/batch db "batch-1"))))
          (is (= 1 (count (store/shipment-history db))) "one draft shipment record"))))))

(deftest issue-harness-certificate-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-defect batch still ALWAYS interrupts for human approval -- actuation/issue-harness-certificate is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "batch-1")
          _ (screen! actor "t8pre2" "batch-1")
          r1 (exec-op actor "t8" {:op :actuation/issue-harness-certificate :subject "batch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, certificate record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:harness-certified? (store/batch db "batch-1"))))
          (is (= 1 (count (store/certificate-history db))) "one draft certificate record"))))))

(deftest ship-cable-run-batch-double-shipment-is-held
  (testing "shipping the same batch's action twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "batch-1")
          _ (simulate-robotics! actor "t9pre2" "batch-1")
          _ (exec-op actor "t9a" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-shipped} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/shipment-history db))) "still only the one earlier shipment"))))

(deftest issue-harness-certificate-double-issuance-is-held
  (testing "issuing the same batch's harness certificate twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "batch-1")
          _ (screen! actor "t10pre2" "batch-1")
          _ (exec-op actor "t10a" {:op :actuation/issue-harness-certificate :subject "batch-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/issue-harness-certificate :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-certified} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/certificate-history db))) "still only the one earlier certificate issuance"))))

(deftest robotics-simulation-always-needs-approval
  (testing "robotics/simulate-tensile-pull-test is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :robotics/simulate-tensile-pull-test :subject "batch-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:robotics-sim-verified? (store/batch db "batch-1"))))
        (is (number? (:sim-peak-pull-force-n (store/batch db "batch-1"))) "REAL physics-2d telemetry recorded, not invented")))))

(deftest ship-cable-run-batch-without-robotics-simulation-is-held
  (testing "actuation/ship-cable-run-batch before the robot tensile-pull-test mission ever ran -> HOLD (robotics-simulation-missing)"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "batch-1")
          res (exec-op actor "t12" {:op :actuation/ship-cable-run-batch :subject "batch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest robotics-simulation-out-of-tolerance-is-held
  (testing "batch-5 has a robotics-sim already on file, but its own REAL physics-2d-simulated peak pull force (an under-crimped/wrong-gauge terminal with an inconsistently light :crimp-effective-mass-kg) falls below the minimum required floor on INDEPENDENT recheck -> HOLD, never trusts the on-file verdict alone"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "batch-5")]
      (is (< (:sim-peak-pull-force-n (store/batch db "batch-5")) 60.0)
          "genuinely-failing real-physics-derived fixture: simulated peak pull force is actually below the 60 N floor")
      (let [res (exec-op actor "t13" {:op :actuation/ship-cable-run-batch :subject "batch-5"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:robotics-simulation-out-of-tolerance} (-> (store/ledger db) last :basis)))
        (is (empty? (store/shipment-history db)))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :cable-run-batch/intake :subject "batch-1"
                          :patch {:id "batch-1" :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}} operator)
      (exec-op actor "b" {:op :harness-standard-rules/verify :subject "batch-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
