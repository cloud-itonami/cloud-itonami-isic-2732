(ns harnessworks.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/ship-cable-run-batch`/`:actuation/issue-
  harness-certificate` must NEVER be a member of any phase's `:auto`
  set."
  (:require [clojure.test :refer [deftest is testing]]
            [harnessworks.phase :as phase]))

(deftest ship-cable-run-batch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real robot batch shipment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/ship-cable-run-batch))
          (str "phase " n " must not auto-commit :actuation/ship-cable-run-batch")))))

(deftest issue-harness-certificate-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real harness certificate"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/issue-harness-certificate))
          (str "phase " n " must not auto-commit :actuation/issue-harness-certificate")))))

(deftest end-of-line-quality-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :end-of-line-quality/screen))
          (str "phase " n " must not auto-commit :end-of-line-quality/screen")))))

(deftest robotics-simulate-tensile-pull-test-never-auto-at-any-phase
  (testing "the robot continuity/tensile-pull/insulation-resistance verification mission carries no direct capital risk, but is still never auto-eligible, matching every sibling verification op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :robotics/simulate-tensile-pull-test))
          (str "phase " n " must not auto-commit :robotics/simulate-tensile-pull-test")))))

(deftest robotics-simulate-tensile-pull-test-enabled-from-phase-2
  (is (contains? (:writes (get phase/phases 2)) :robotics/simulate-tensile-pull-test))
  (is (contains? (:writes (get phase/phases 3)) :robotics/simulate-tensile-pull-test))
  (is (not (contains? (:writes (get phase/phases 1)) :robotics/simulate-tensile-pull-test))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":cable-run-batch/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:cable-run-batch/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :cable-run-batch/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/ship-cable-run-batch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/issue-harness-certificate} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :cable-run-batch/intake} :commit)))))
