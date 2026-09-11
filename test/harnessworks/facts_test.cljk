(ns harnessworks.facts-test
  (:require [clojure.test :refer [deftest is]]
            [harnessworks.facts :as facts]))

(deftest auto-has-a-spec-basis
  (is (some? (facts/spec-basis "AUTO")))
  (is (string? (:provenance (facts/spec-basis "AUTO")))))

(deftest elex-has-a-spec-basis
  (is (some? (facts/spec-basis "ELEX")))
  (is (string? (:provenance (facts/spec-basis "ELEX")))))

(deftest unknown-product-class-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "UNK"))))

(deftest coverage-never-reports-a-missing-product-class-as-covered
  (let [report (facts/coverage ["AUTO" "UNK" "ELEX"])]
    (is (= 2 (:covered report)))
    (is (= ["UNK"] (:missing-jurisdictions report)))
    (is (= ["AUTO" "ELEX"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "AUTO")]
    (is (facts/required-evidence-satisfied? "AUTO" all))
    (is (not (facts/required-evidence-satisfied? "AUTO" (rest all))))
    (is (not (facts/required-evidence-satisfied? "UNK" all)) "no spec-basis -> never satisfied")))
