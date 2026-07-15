(ns harnessworks.export-test
  "Audit-package export contract -- social/regulatory hand-off shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [harnessworks.export :as export]
            [harnessworks.operation :as op]
            [harnessworks.store :as store]))

(def operator {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- seed-with-one-shipment []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "v" {:op :harness-standard-rules/verify :subject "batch-1"})
    (approve! actor "v")
    (exec! actor "r" {:op :robotics/simulate-tensile-pull-test :subject "batch-1"})
    (approve! actor "r")
    (exec! actor "d" {:op :actuation/ship-cable-run-batch :subject "batch-1"})
    (approve! actor "d")
    db))

(deftest audit-package-shape
  (let [db (seed-with-one-shipment)
        pkg (export/audit-package db)]
    (is (= "2732" (:isic pkg)))
    (is (= "cloud-itonami-isic-2732" (:business-id pkg)))
    (is (= :edn-maps (:format pkg)))
    (is (pos? (get-in pkg [:counts :ledger])))
    (is (= 1 (get-in pkg [:counts :shipments])))
    (is (some #(= "batch-1" (:id %)) (:batches pkg)))
    (is (true? (:batch-shipped?
                (first (filter #(= "batch-1" (:id %)) (:batches pkg))))))))

(deftest csv-bundle-has-headers-and-rows
  (let [db (seed-with-one-shipment)
        bundle (export/package->csv-bundle db)]
    (is (every? bundle ["batches.csv" "ledger.csv" "shipments.csv" "harness-certificates.csv"]))
    (is (str/starts-with? (get bundle "batches.csv") "id,batch-name,"))
    (is (re-find #"batch-1" (get bundle "batches.csv")))
    (is (re-find #"AUTO-SHP-000000" (get bundle "shipments.csv")))
    (is (re-find #":actuation/ship-cable-run-batch" (get bundle "ledger.csv")))))

(deftest empty-store-export-is-usable
  (let [db (store/seed-db)
        pkg (export/audit-package db)
        bundle (export/package->csv-bundle db)]
    (is (= 0 (get-in pkg [:counts :shipments])))
    (is (= 5 (get-in pkg [:counts :batches])))
    (is (str/includes? (get bundle "ledger.csv") "seq,t,op"))))
