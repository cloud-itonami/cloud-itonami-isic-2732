(ns harnessworks.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [harnessworks.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Meridian Automotive Wiring Harness Lot WH-2044" (:batch-name (store/batch s "batch-1"))))
      (is (= "AUTO" (:jurisdiction (store/batch s "batch-1"))))
      (is (= 0.05 (:conductor-resistance-deviation-actual (store/batch s "batch-1"))))
      (is (= -0.10 (:conductor-resistance-deviation-min (store/batch s "batch-1"))))
      (is (= 0.10 (:conductor-resistance-deviation-max (store/batch s "batch-1"))))
      (is (false? (:eol-defect-unresolved? (store/batch s "batch-1"))))
      (is (= 0.35 (:conductor-resistance-deviation-actual (store/batch s "batch-3"))))
      (is (true? (:eol-defect-unresolved? (store/batch s "batch-4"))))
      (is (false? (:robotics-sim-verified? (store/batch s "batch-1"))) "no robotics mission has run yet")
      (is (true? (:robotics-sim-verified? (store/batch s "batch-5"))) "seeded as already-on-file")
      (is (= 0.03 (:crimp-effective-mass-kg (store/batch s "batch-5"))))
      (is (number? (:sim-peak-pull-force-n (store/batch s "batch-5"))) "REAL physics-2d telemetry precomputed on seed")
      (is (false? (:batch-shipped? (store/batch s "batch-1"))))
      (is (false? (:harness-certified? (store/batch s "batch-1"))))
      (is (= ["batch-1" "batch-2" "batch-3" "batch-4" "batch-5"]
             (mapv :id (store/all-batches s))))
      (is (nil? (store/eol-screen-of s "batch-1")))
      (is (nil? (store/requirements-verification-of s "batch-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/shipment-history s)))
      (is (= [] (store/certificate-history s)))
      (is (zero? (store/next-shipment-sequence s "AUTO")))
      (is (zero? (store/next-certificate-sequence s "AUTO")))
      (is (false? (store/batch-already-shipped? s "batch-1")))
      (is (false? (store/batch-already-certified? s "batch-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :batch/upsert
                                 :value {:id "batch-1" :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}})
        (is (= "Meridian Automotive Wiring Harness Lot WH-2044" (:batch-name (store/batch s "batch-1"))))
        (is (= 0.05 (:conductor-resistance-deviation-actual (store/batch s "batch-1"))) "unrelated field preserved"))
      (testing "robotics-sim result commits via :batch/upsert and reads back"
        (store/commit-record! s {:effect :batch/upsert
                                 :value {:id "batch-1" :robotics-sim-verified? true
                                        :sim-peak-pull-force-n 100.0
                                        :robotics-sim-record {:mission-id "m-1" :passed? true}}})
        (is (true? (:robotics-sim-verified? (store/batch s "batch-1"))))
        (is (= {:mission-id "m-1" :passed? true} (:robotics-sim-record (store/batch s "batch-1"))))
        (is (= 0.05 (:conductor-resistance-deviation-actual (store/batch s "batch-1"))) "unrelated field still preserved"))
      (testing "verification / EOL-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["batch-1"]
                                 :payload {:jurisdiction "AUTO" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "AUTO" :checklist ["a" "b"]} (store/requirements-verification-of s "batch-1")))
        (store/commit-record! s {:effect :eol-screen/set :path ["batch-1"]
                                 :payload {:batch-id "batch-1" :verdict :resolved}})
        (is (= {:batch-id "batch-1" :verdict :resolved} (store/eol-screen-of s "batch-1"))))
      (testing "cable-run-batch shipment drafts a record and advances the sequence"
        (store/commit-record! s {:effect :batch/mark-shipped :path ["batch-1"]})
        (is (= "AUTO-SHP-000000" (get (first (store/shipment-history s)) "record_id")))
        (is (= "cable-run-batch-shipment-draft" (get (first (store/shipment-history s)) "kind")))
        (is (true? (:batch-shipped? (store/batch s "batch-1"))))
        (is (= 1 (count (store/shipment-history s))))
        (is (= 1 (store/next-shipment-sequence s "AUTO")))
        (is (true? (store/batch-already-shipped? s "batch-1")))
        (is (false? (store/batch-already-shipped? s "batch-2"))))
      (testing "harness certificate drafts a record and advances the sequence"
        (store/commit-record! s {:effect :batch/mark-certified :path ["batch-1"]})
        (is (= "AUTO-HCERT-000000" (get (first (store/certificate-history s)) "record_id")))
        (is (= "harness-certificate-draft" (get (first (store/certificate-history s)) "kind")))
        (is (true? (:harness-certified? (store/batch s "batch-1"))))
        (is (= 1 (count (store/certificate-history s))))
        (is (= 1 (store/next-certificate-sequence s "AUTO")))
        (is (true? (store/batch-already-certified? s "batch-1")))
        (is (false? (store/batch-already-certified? s "batch-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/batch s "nope")))
    (is (= [] (store/all-batches s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/shipment-history s)))
    (is (= [] (store/certificate-history s)))
    (is (zero? (store/next-shipment-sequence s "AUTO")))
    (is (zero? (store/next-certificate-sequence s "AUTO")))
    (store/with-batches s {"x" {:id "x" :batch-name "n" :conductor-resistance-deviation-actual 0.05
                                :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
                                :eol-defect-unresolved? false
                                :batch-shipped? false :harness-certified? false
                                :jurisdiction "AUTO" :status :intake}})
    (is (= "n" (:batch-name (store/batch s "x"))))))
