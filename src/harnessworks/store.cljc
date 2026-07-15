(ns harnessworks.store
  "SSoT for the wire/cable/harness-manufacturing actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/harnessworks/store_contract_test.clj), which is the whole
  point: the actor, the Cable-Integrity Governor and the audit ledger
  never know which SSoT they run on.

  Like `automotive.store`'s dual vehicle-dispatch/conformity-
  certificate history and `autoparts.store`'s dual part-lot-shipment/
  PPAP-certificate history, this actor has TWO actuation events
  (shipping a cable-run batch onward, issuing a harness certificate)
  acting on the SAME entity (a cable-run batch), each with its OWN
  history collection, sequence counter and dedicated double-actuation-
  guard boolean (`:batch-shipped?`/`:harness-certified?`, never a
  `:status` value) -- the same discipline every prior sibling
  governor's guards establish, informed by `cloud-itonami-isic-6492`'s
  status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which batch was
  screened for an unresolved end-of-line defect, which cable-run-batch
  shipment was dispatched, which harness certificate was issued, on
  what product-class basis, approved by whom' is always a query over
  an immutable log -- the audit trail a community trusting a wire/
  cable/harness manufacturer needs, and the evidence a manufacturer
  needs if a shipment or certificate decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [harnessworks.registry :as registry]
            [harnessworks.robotics :as robotics]
            [langchain.db :as d]))

(defprotocol Store
  (batch [s id])
  (all-batches [s])
  (eol-screen-of [s batch-id] "committed end-of-line-defect screening verdict for a batch, or nil")
  (requirements-verification-of [s batch-id] "committed harness-standard-rules evidence verification, or nil")
  (ledger [s])
  (shipment-history [s] "the append-only cable-run-batch-shipment history (harnessworks.registry drafts)")
  (certificate-history [s] "the append-only harness-certificate history (harnessworks.registry drafts)")
  (next-shipment-sequence [s jurisdiction] "next shipment-number sequence for a product-class scope")
  (next-certificate-sequence [s jurisdiction] "next certificate-number sequence for a product-class scope")
  (batch-already-shipped? [s batch-id] "has this batch already been shipped?")
  (batch-already-certified? [s batch-id] "has this batch's harness certificate already been issued?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-batches [s batches] "replace/seed the cable-run-batch directory (map id->batch)"))

;; ----------------------------- demo data -----------------------------

(defn- with-pull-test-telemetry
  "Merges REAL conductor/crimp tensile-pull-test telemetry onto a demo
  batch's base fields -- `harnessworks.robotics/pull-test-telemetry-
  for` actually runs `run-pull-test`'s `physics-2d`-stepped simulation
  for this batch's own `:crimp-effective-mass-kg` (ADR-2607152000), so
  even the 'already on file' seed data (as if from an earlier real
  pull-test report) is genuinely simulation-derived, never hand-typed
  doubles."
  [base]
  (merge base (select-keys (robotics/pull-test-telemetry-for base)
                           [:sim-peak-pull-force-n :sim-peak-decel-mps2])))

(defn demo-data
  "A small, self-contained cable-run-batch set covering both actuation
  lifecycles (shipping a batch, issuing a harness certificate) so the
  actor + tests run offline. `:crimp-effective-mass-kg`
  (ADR-2607152000) is a permanent batch-design field (like
  `:conductor-resistance-deviation-actual`); `:sim-peak-pull-force-n`/
  `:sim-peak-decel-mps2` are the REAL `harnessworks.robotics/run-pull-
  test`-computed telemetry for that field (`with-pull-test-
  telemetry`), the ground truth `harnessworks.robotics/simulation-out-
  of-tolerance?` independently rechecks. batch-5 (a crimp-terminal
  batch) is DELIBERATELY recorded with a much lighter
  `:crimp-effective-mass-kg` (0.03 kg) than its spec'd automotive-gauge
  conductor class should carry -- a genuine design-record
  inconsistency (an under-crimped/wrong-gauge terminal engaging far
  less conductor/crimp-barrel material than the spec calls for) that
  the real, re-run simulation catches on independent recheck even
  though `:robotics-sim-verified?` was seeded `true` (\"already on
  file\", i.e. someone/something marked it passed without this real
  check ever having run) -- the wire/cable/harness-manufacturer analog
  of `autoparts.store`'s lot-5 fastener-scale misclassification and
  automotive's vehicle-5 misclassified pickup. batch-1..4's
  `:crimp-effective-mass-kg` values (0.09-0.11 kg) are all genuinely
  consistent automotive/fine-gauge-class test masses, which all clear
  the real proof-load floor with margin (see `harnessworks.robotics/
  min-pull-force-n`)."
  []
  {:batches
   (into {}
         (map (fn [v] [(:id v) (with-pull-test-telemetry v)]))
         [{:id "batch-1" :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"
           :conductor-resistance-deviation-actual 0.05 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
           :crimp-effective-mass-kg 0.10
           :eol-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :harness-certified? false
           :jurisdiction "AUTO" :status :intake}
          {:id "batch-2" :batch-name "Atlas Flex-Cable Lot FC-1187"
           :conductor-resistance-deviation-actual 0.05 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
           :crimp-effective-mass-kg 0.09
           :eol-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :harness-certified? false
           :jurisdiction "ELEX" :status :intake}
          {:id "batch-3" :batch-name "田中ワイヤーハーネス・ロット WH-215"
           :conductor-resistance-deviation-actual 0.35 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
           :crimp-effective-mass-kg 0.11
           :eol-defect-unresolved? false
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :harness-certified? false
           :jurisdiction "AUTO" :status :intake}
          {:id "batch-4" :batch-name "佐藤フレキシブルケーブル・ロット FC-330"
           :conductor-resistance-deviation-actual 0.05 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
           :crimp-effective-mass-kg 0.10
           :eol-defect-unresolved? true
           :robotics-sim-verified? false :robotics-sim-record nil
           :batch-shipped? false :harness-certified? false
           :jurisdiction "ELEX" :status :intake}
          {:id "batch-5" :batch-name "鈴木クリンプ端子ロット CT-118"
           :conductor-resistance-deviation-actual 0.05 :conductor-resistance-deviation-min -0.10 :conductor-resistance-deviation-max 0.10
           :crimp-effective-mass-kg 0.03
           :eol-defect-unresolved? false
           :robotics-sim-verified? true :robotics-sim-record nil
           :batch-shipped? false :harness-certified? false
           :jurisdiction "AUTO" :status :intake}])})

;; ----------------------------- shared commit logic -----------------------------

(defn- ship-cable-run-batch!
  "Backend-agnostic `:batch/mark-shipped` -- looks up the batch via
  the protocol and drafts the cable-run-batch-shipment record, and
  returns {:result .. :batch-patch ..} for the caller to persist."
  [s batch-id]
  (let [a (batch s batch-id)
        seq-n (next-shipment-sequence s (:jurisdiction a))
        result (registry/register-cable-run-batch-shipment batch-id (:jurisdiction a) seq-n)]
    {:result result
     :batch-patch {:batch-shipped? true
                   :shipment-number (get result "shipment_number")}}))

(defn- issue-harness-certificate!
  "Backend-agnostic `:batch/mark-certified` -- looks up the batch via
  the protocol and drafts the harness-certificate record, and returns
  {:result .. :batch-patch ..} for the caller to persist."
  [s batch-id]
  (let [a (batch s batch-id)
        seq-n (next-certificate-sequence s (:jurisdiction a))
        result (registry/register-harness-certificate batch-id (:jurisdiction a) seq-n)]
    {:result result
     :batch-patch {:harness-certified? true
                   :certificate-number (get result "certificate_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (batch [_ id] (get-in @a [:batches id]))
  (all-batches [_] (sort-by :id (vals (:batches @a))))
  (eol-screen-of [_ id] (get-in @a [:eol-screens id]))
  (requirements-verification-of [_ batch-id] (get-in @a [:verifications batch-id]))
  (ledger [_] (:ledger @a))
  (shipment-history [_] (:shipments @a))
  (certificate-history [_] (:certificates @a))
  (next-shipment-sequence [_ jurisdiction] (get-in @a [:shipment-sequences jurisdiction] 0))
  (next-certificate-sequence [_ jurisdiction] (get-in @a [:certificate-sequences jurisdiction] 0))
  (batch-already-shipped? [_ batch-id] (boolean (get-in @a [:batches batch-id :batch-shipped?])))
  (batch-already-certified? [_ batch-id] (boolean (get-in @a [:batches batch-id :harness-certified?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :batch/upsert
      (swap! a update-in [:batches (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :eol-screen/set
      (swap! a assoc-in [:eol-screens (first path)] payload)

      :batch/mark-shipped
      (let [batch-id (first path)
            {:keys [result batch-patch]} (ship-cable-run-batch! s batch-id)
            jurisdiction (:jurisdiction (batch s batch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:shipment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:batches batch-id] merge batch-patch)
                       (update :shipments registry/append result))))
        result)

      :batch/mark-certified
      (let [batch-id (first path)
            {:keys [result batch-patch]} (issue-harness-certificate! s batch-id)
            jurisdiction (:jurisdiction (batch s batch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:certificate-sequences jurisdiction] (fnil inc 0))
                       (update-in [:batches batch-id] merge batch-patch)
                       (update :certificates registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-batches [s batches] (when (seq batches) (swap! a assoc :batches batches)) s))

(defn seed-db
  "A MemStore seeded with the demo cable-run-batch set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :eol-screens {} :ledger [] :shipment-sequences {}
                           :shipments [] :certificate-sequences {} :certificates []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/eol-screen payloads, ledger facts,
  shipment/certificate records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:batch/id                          {:db/unique :db.unique/identity}
   :verification/batch-id             {:db/unique :db.unique/identity}
   :eol-screen/batch-id                {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :shipment/seq                      {:db/unique :db.unique/identity}
   :certificate/seq                   {:db/unique :db.unique/identity}
   :shipment-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :certificate-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- batch->tx [{:keys [id batch-name conductor-resistance-deviation-actual conductor-resistance-deviation-min conductor-resistance-deviation-max
                           crimp-effective-mass-kg sim-peak-pull-force-n sim-peak-decel-mps2
                           eol-defect-unresolved? robotics-sim-verified? robotics-sim-record
                           batch-shipped? harness-certified?
                           jurisdiction status shipment-number certificate-number]}]
  (cond-> {:batch/id id}
    batch-name                                  (assoc :batch/batch-name batch-name)
    conductor-resistance-deviation-actual        (assoc :batch/conductor-resistance-deviation-actual conductor-resistance-deviation-actual)
    conductor-resistance-deviation-min           (assoc :batch/conductor-resistance-deviation-min conductor-resistance-deviation-min)
    conductor-resistance-deviation-max           (assoc :batch/conductor-resistance-deviation-max conductor-resistance-deviation-max)
    crimp-effective-mass-kg                      (assoc :batch/crimp-effective-mass-kg crimp-effective-mass-kg)
    sim-peak-pull-force-n                        (assoc :batch/sim-peak-pull-force-n sim-peak-pull-force-n)
    sim-peak-decel-mps2                          (assoc :batch/sim-peak-decel-mps2 sim-peak-decel-mps2)
    (some? eol-defect-unresolved?)               (assoc :batch/eol-defect-unresolved? eol-defect-unresolved?)
    (some? robotics-sim-verified?)                (assoc :batch/robotics-sim-verified? robotics-sim-verified?)
    (some? robotics-sim-record)                   (assoc :batch/robotics-sim-record (enc robotics-sim-record))
    (some? batch-shipped?)                       (assoc :batch/batch-shipped? batch-shipped?)
    (some? harness-certified?)                   (assoc :batch/harness-certified? harness-certified?)
    jurisdiction                                 (assoc :batch/jurisdiction jurisdiction)
    status                                       (assoc :batch/status status)
    shipment-number                              (assoc :batch/shipment-number shipment-number)
    certificate-number                           (assoc :batch/certificate-number certificate-number)))

(def ^:private batch-pull
  [:batch/id :batch/batch-name :batch/conductor-resistance-deviation-actual
   :batch/conductor-resistance-deviation-min :batch/conductor-resistance-deviation-max
   :batch/crimp-effective-mass-kg :batch/sim-peak-pull-force-n :batch/sim-peak-decel-mps2
   :batch/eol-defect-unresolved? :batch/robotics-sim-verified? :batch/robotics-sim-record
   :batch/batch-shipped? :batch/harness-certified?
   :batch/jurisdiction :batch/status :batch/shipment-number :batch/certificate-number])

(defn- pull->batch [m]
  (when (:batch/id m)
    {:id (:batch/id m) :batch-name (:batch/batch-name m)
     :conductor-resistance-deviation-actual (:batch/conductor-resistance-deviation-actual m)
     :conductor-resistance-deviation-min (:batch/conductor-resistance-deviation-min m)
     :conductor-resistance-deviation-max (:batch/conductor-resistance-deviation-max m)
     :crimp-effective-mass-kg (:batch/crimp-effective-mass-kg m)
     :sim-peak-pull-force-n (:batch/sim-peak-pull-force-n m)
     :sim-peak-decel-mps2 (:batch/sim-peak-decel-mps2 m)
     :eol-defect-unresolved? (boolean (:batch/eol-defect-unresolved? m))
     :robotics-sim-verified? (boolean (:batch/robotics-sim-verified? m))
     :robotics-sim-record (dec* (:batch/robotics-sim-record m))
     :batch-shipped? (boolean (:batch/batch-shipped? m))
     :harness-certified? (boolean (:batch/harness-certified? m))
     :jurisdiction (:batch/jurisdiction m) :status (:batch/status m)
     :shipment-number (:batch/shipment-number m) :certificate-number (:batch/certificate-number m)}))

(defrecord DatomicStore [conn]
  Store
  (batch [_ id]
    (pull->batch (d/pull (d/db conn) batch-pull [:batch/id id])))
  (all-batches [_]
    (->> (d/q '[:find [?id ...] :where [?e :batch/id ?id]] (d/db conn))
         (map #(pull->batch (d/pull (d/db conn) batch-pull [:batch/id %])))
         (sort-by :id)))
  (eol-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :eol-screen/batch-id ?aid] [?k :eol-screen/payload ?p]]
              (d/db conn) id)))
  (requirements-verification-of [_ batch-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :verification/batch-id ?aid] [?a :verification/payload ?p]]
              (d/db conn) batch-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (shipment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :shipment/seq ?s] [?e :shipment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (certificate-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :certificate/seq ?s] [?e :certificate/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-shipment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :shipment-sequence/jurisdiction ?j] [?e :shipment-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-certificate-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :certificate-sequence/jurisdiction ?j] [?e :certificate-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (batch-already-shipped? [s batch-id]
    (boolean (:batch-shipped? (batch s batch-id))))
  (batch-already-certified? [s batch-id]
    (boolean (:harness-certified? (batch s batch-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :batch/upsert
      (d/transact! conn [(batch->tx value)])

      :verification/set
      (d/transact! conn [{:verification/batch-id (first path) :verification/payload (enc payload)}])

      :eol-screen/set
      (d/transact! conn [{:eol-screen/batch-id (first path) :eol-screen/payload (enc payload)}])

      :batch/mark-shipped
      (let [batch-id (first path)
            {:keys [result batch-patch]} (ship-cable-run-batch! s batch-id)
            jurisdiction (:jurisdiction (batch s batch-id))
            next-n (inc (next-shipment-sequence s jurisdiction))]
        (d/transact! conn
                     [(batch->tx (assoc batch-patch :id batch-id))
                      {:shipment-sequence/jurisdiction jurisdiction :shipment-sequence/next next-n}
                      {:shipment/seq (count (shipment-history s)) :shipment/record (enc (get result "record"))}])
        result)

      :batch/mark-certified
      (let [batch-id (first path)
            {:keys [result batch-patch]} (issue-harness-certificate! s batch-id)
            jurisdiction (:jurisdiction (batch s batch-id))
            next-n (inc (next-certificate-sequence s jurisdiction))]
        (d/transact! conn
                     [(batch->tx (assoc batch-patch :id batch-id))
                      {:certificate-sequence/jurisdiction jurisdiction :certificate-sequence/next next-n}
                      {:certificate/seq (count (certificate-history s)) :certificate/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-batches [s batches]
    (when (seq batches) (d/transact! conn (mapv batch->tx (vals batches)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:batches ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [batches]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-batches s batches))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo cable-run-batch set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
