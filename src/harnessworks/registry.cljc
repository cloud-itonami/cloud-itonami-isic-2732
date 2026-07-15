(ns harnessworks.registry
  "Pure-function cable-run-batch-shipment + harness-certificate record
  construction -- an append-only wire/cable/harness-manufacturer
  book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a cable-run-batch-shipment or
  Cable/Harness Test Certificate reference number -- every
  manufacturer/OEM assigns its own reference format. This namespace
  does NOT invent one; it builds a product-class-scoped sequence
  number (see `harnessworks.facts` docstring for why the scope key is
  a product class, not an ISO3 country code) and validates the
  record's required fields, the same honest, non-fabricating
  discipline `harnessworks.facts` uses.

  `cable-run-batch-resistance-out-of-range?` continues this fleet's
  two-sided range check family (`testlab.registry/within-tolerance?`
  established the first, `conservation.registry/body-condition-out-
  of-range?` the second, `water.registry/contaminant-level-out-of-
  range?` the third, `steelworks.registry/heat-chemistry-out-of-
  range?`/`turbine.registry/unit-tolerance-out-of-range?`/`automotive.
  registry/vehicle-emissions-out-of-range?`/`autoparts.registry/part-
  lot-dppm-out-of-range?` further siblings), applying the SAME lo/hi
  bounds-comparison shape to a cable-run-batch's own measured
  conductor-resistance deviation (from its own recorded per-unit-
  length spec resistance) against the batch's own recorded spec
  bounds.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant/MES control system. It builds the RECORD a
  manufacturer would keep, not the act of shipping the cable-run-batch
  robot action or issuing the harness certificate itself (that is
  `harnessworks.operation`'s `:actuation/ship-cable-run-batch`/
  `:actuation/issue-harness-certificate`, always human-gated -- see
  README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  manufacturer's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn cable-run-batch-resistance-out-of-range?
  "Does `batch`'s own `:conductor-resistance-deviation-actual` fall
  outside its own `[:conductor-resistance-deviation-min
  :conductor-resistance-deviation-max]` recorded spec bounds? A pure
  ground-truth check against the batch's own permanent fields -- no
  upstream comparison needed. A further sibling in this fleet's
  two-sided range check family (see ns docstring)."
  [{:keys [conductor-resistance-deviation-actual
           conductor-resistance-deviation-min
           conductor-resistance-deviation-max]}]
  (and (number? conductor-resistance-deviation-actual)
       (number? conductor-resistance-deviation-min)
       (number? conductor-resistance-deviation-max)
       (or (< conductor-resistance-deviation-actual conductor-resistance-deviation-min)
           (> conductor-resistance-deviation-actual conductor-resistance-deviation-max))))

(defn register-cable-run-batch-shipment
  "Validate + construct the CABLE-RUN-BATCH-SHIPMENT registration
  DRAFT -- the manufacturer's own act of dispatching a real robot
  shipment/handling action to release a finished cable-run batch
  onward (automotive wiring harnesses to `cloud-itonami-isic-2910`/
  `-2920`, smartphone flex-cables to `cloud-itonami-isic-2630`, both
  connecting to `cloud-itonami-isic-2720`'s battery terminals). Pure
  function -- does not touch any real plant/MES control system; it
  builds the RECORD a manufacturer would keep. `harnessworks.governor`
  independently re-verifies the batch's own conductor-resistance
  sufficiency against its own spec bounds, and a double-shipment for
  the same batch, before this is ever allowed to commit."
  [batch-id jurisdiction sequence]
  (when-not (and batch-id (not= batch-id ""))
    (throw (ex-info "cable-run-batch-shipment: batch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "cable-run-batch-shipment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "cable-run-batch-shipment: sequence must be >= 0" {})))
  (let [shipment-number (str (str/upper-case jurisdiction) "-SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "cable-run-batch-shipment-draft"
                "batch_id" batch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "CableRunBatchShipment" shipment-number shipment-number)}))

(defn register-harness-certificate
  "Validate + construct the HARNESS-CERTIFICATE registration DRAFT --
  the manufacturer's own act of issuing a real Cable/Harness Test
  Certificate (per IPC/WHMA-A-620) certifying a cable-run batch as
  shippable. Pure function -- does not touch any real plant/MES
  control system; it builds the RECORD a manufacturer would keep.
  `harnessworks.governor` independently re-verifies the batch's own
  end-of-line defect resolution status, and a double-issuance for the
  same batch, before this is ever allowed to commit."
  [batch-id jurisdiction sequence]
  (when-not (and batch-id (not= batch-id ""))
    (throw (ex-info "harness-certificate: batch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "harness-certificate: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "harness-certificate: sequence must be >= 0" {})))
  (let [certificate-number (str (str/upper-case jurisdiction) "-HCERT-" (zero-pad sequence 6))
        record {"record_id" certificate-number
                "kind" "harness-certificate-draft"
                "batch_id" batch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "certificate_number" certificate-number
     "certificate" (unsigned-certificate "HarnessCertificate" certificate-number certificate-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
