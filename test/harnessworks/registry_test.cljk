(ns harnessworks.registry-test
  (:require [clojure.test :refer [deftest is]]
            [harnessworks.registry :as r]))

;; ----------------------------- cable-run-batch-resistance-out-of-range? -----------------------------

(deftest not-out-of-range-when-within-bounds
  (is (not (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual 0.05 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10})))
  (is (not (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual -0.10 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10})))
  (is (not (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual 0.10 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10}))))

(deftest out-of-range-when-below-minimum-or-above-maximum
  (is (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual -0.35 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10}))
  (is (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual 0.35 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10})))

(deftest out-of-range-is-false-on-missing-fields
  (is (not (r/cable-run-batch-resistance-out-of-range? {})))
  (is (not (r/cable-run-batch-resistance-out-of-range? {:conductor-resistance-deviation-actual 0.35}))))

;; ----------------------------- register-cable-run-batch-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-cable-run-batch-shipment "batch-1" "AUTO" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-cable-run-batch-shipment "batch-1" "AUTO" 7)]
    (is (= (get result "shipment_number") "AUTO-SHP-000007"))
    (is (= (get-in result ["record" "batch_id"]) "batch-1"))
    (is (= (get-in result ["record" "kind"]) "cable-run-batch-shipment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? Exception (r/register-cable-run-batch-shipment "" "AUTO" 0)))
  (is (thrown? Exception (r/register-cable-run-batch-shipment "batch-1" "" 0)))
  (is (thrown? Exception (r/register-cable-run-batch-shipment "batch-1" "AUTO" -1))))

;; ----------------------------- register-harness-certificate -----------------------------

(deftest certificate-is-a-draft-not-real-certification
  (let [result (r/register-harness-certificate "batch-1" "AUTO" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest certificate-assigns-certificate-number
  (let [result (r/register-harness-certificate "batch-1" "AUTO" 3)]
    (is (= (get result "certificate_number") "AUTO-HCERT-000003"))
    (is (= (get-in result ["record" "batch_id"]) "batch-1"))
    (is (= (get-in result ["record" "kind"]) "harness-certificate-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest certificate-validation-rules
  (is (thrown? Exception (r/register-harness-certificate "" "AUTO" 0)))
  (is (thrown? Exception (r/register-harness-certificate "batch-1" "" 0)))
  (is (thrown? Exception (r/register-harness-certificate "batch-1" "AUTO" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-cable-run-batch-shipment "batch-1" "AUTO" 0)
        hist (r/append [] c1)
        c2 (r/register-cable-run-batch-shipment "batch-2" "AUTO" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "AUTO-SHP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "AUTO-SHP-000001" (get-in hist2 [1 "record_id"])))))
