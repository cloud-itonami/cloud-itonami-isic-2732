(ns harnessworks.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL wire/cable/harness OperationActor
  (`harnessworks.operation/build` -> a compiled langgraph-clj
  StateGraph) over the REAL seeded store (`harnessworks.store/seed-db`,
  whose `:sim-peak-pull-force-n` telemetry is itself produced by
  `harnessworks.robotics`' real `physics-2d` time-stepped conductor/
  crimp tensile-pull simulation), through the REAL Cable-Integrity
  Governor (`harnessworks.governor/check`) and the REAL rollout phase
  gate (`harnessworks.phase/gate`), and renders whatever those
  produced. Nothing on the page is written by hand:

    - every batch row is read back out of the store after the run
      (`store/all-batches`), and its in-spec / out-of-tolerance verdicts
      are re-derived through the SAME public predicates the governor
      uses (`registry/cable-run-batch-resistance-out-of-range?`,
      `robotics/simulation-out-of-tolerance?`) rather than restated;
    - every HARD-hold rule name and every violation detail string is the
      governor's own `:violations` entry off the ledger fact -- never a
      literal in this namespace;
    - the op-gate table is derived from `phase/write-ops`,
      `phase/phases` and `governor/high-stakes`;
    - the hand-off table is `store/shipment-history` /
      `store/certificate-history` and `export/audit-package` counts.

  Subject provenance (the demo may not invent subjects): every batch id
  driven below (`batch-1` .. `batch-5`) is seeded by
  `harnessworks.store/demo-data`, and every draft record id is minted
  by `harnessworks.registry` inside the run itself. Confirmed against
  `clojure -M:dev:run` BEFORE this file was written.

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [harnessworks.export :as export]
            [harnessworks.facts :as facts]
            [harnessworks.governor :as governor]
            [harnessworks.operation :as op]
            [harnessworks.phase :as phase]
            [harnessworks.registry :as registry]
            [harnessworks.robotics :as robotics]
            [harnessworks.store :as store]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition and every HARD-hold rule this actor defends:

    batch-1 clears a full lifecycle -- intake (auto-commits clean at
      phase 3, no capital risk), harness-standard-rules verification
      (phase-gated, approved), end-of-line quality screen (approved),
      the robot continuity/tensile-pull/insulation-resistance mission
      (approved), then a cable-run-batch shipment and a harness
      certificate (both ALWAYS escalate -- permanently high-stakes,
      never auto at any phase -- approved). Before any of that, a
      shipment attempted with no verification on file HARD-holds
      (`:evidence-incomplete` + `:robotics-simulation-missing`), and
      after it a repeat shipment / repeat certificate HARD-hold
      (`:already-shipped`, `:already-certified`).
    batch-2 HARD-holds a harness-standard verification proposed with no
      official spec-basis (`:no-spec-basis`).
    batch-3 clears its own verification but HARD-holds a shipment
      first for the missing robot mission and then -- once the real
      physics-2d pull test has run and passed -- for its own measured
      conductor-resistance deviation 0.35 falling outside its own
      recorded spec bounds (`:cable-run-batch-resistance-out-of-range`).
    batch-5 carries `:robotics-sim-verified? true` \"already on file\",
      but the governor's INDEPENDENT recheck of the batch's own REAL
      simulated peak pull force rejects it
      (`:robotics-simulation-out-of-tolerance`).
    batch-4 HARD-holds its own end-of-line quality screen on the
      unresolved defect it detects (`:end-of-line-defect-unresolved`).

  Every HARD hold above is produced by genuinely violating a governor
  rule through the actor graph -- no fact is ever appended by hand.
  Returns the resulting store."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; batch-1 -- full clean lifecycle, bracketed by real HARD holds.
    (exec! actor "t1-intake"
           {:op :cable-run-batch/intake :subject "batch-1"
            :patch {:id "batch-1"
                    :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}})

    ;; shipment before ANY verification / robot mission -> HARD hold.
    (exec! actor "t1-premature-ship" {:op :actuation/ship-cable-run-batch :subject "batch-1"})

    (exec! actor "t1-verify" {:op :harness-standard-rules/verify :subject "batch-1"})
    (approve! actor "t1-verify")

    (exec! actor "t1-eol" {:op :end-of-line-quality/screen :subject "batch-1"})
    (approve! actor "t1-eol")

    (exec! actor "t1-robotics" {:op :robotics/simulate-tensile-pull-test :subject "batch-1"})
    (approve! actor "t1-robotics")

    (exec! actor "t1-ship" {:op :actuation/ship-cable-run-batch :subject "batch-1"})
    (approve! actor "t1-ship")

    (exec! actor "t1-certificate" {:op :actuation/issue-harness-certificate :subject "batch-1"})
    (approve! actor "t1-certificate")

    ;; batch-2 -- no official spec-basis -> HARD hold.
    (exec! actor "t2-verify" {:op :harness-standard-rules/verify :subject "batch-2" :no-spec? true})

    ;; batch-3 -- verified, but out-of-spec conductor resistance.
    (exec! actor "t3-verify" {:op :harness-standard-rules/verify :subject "batch-3"})
    (approve! actor "t3-verify")

    (exec! actor "t3-premature-ship" {:op :actuation/ship-cable-run-batch :subject "batch-3"})

    (exec! actor "t3-robotics" {:op :robotics/simulate-tensile-pull-test :subject "batch-3"})
    (approve! actor "t3-robotics")

    (exec! actor "t3-ship" {:op :actuation/ship-cable-run-batch :subject "batch-3"})

    ;; batch-5 -- mission "already on file", independent recheck disagrees.
    (exec! actor "t5-verify" {:op :harness-standard-rules/verify :subject "batch-5"})
    (approve! actor "t5-verify")

    (exec! actor "t5-ship" {:op :actuation/ship-cable-run-batch :subject "batch-5"})

    ;; batch-4 -- the screen itself finds an unresolved end-of-line defect.
    (exec! actor "t4-eol" {:op :end-of-line-quality/screen :subject "batch-4"})

    ;; batch-1 again -- double shipment / double certificate issuance.
    (exec! actor "t1-ship-again" {:op :actuation/ship-cable-run-batch :subject "batch-1"})
    (exec! actor "t1-certificate-again" {:op :actuation/issue-harness-certificate :subject "batch-1"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw->s [v]
  (cond (keyword? v) (subs (str v) 1)
        :else (str v)))

(defn holds
  "Every real `:governor-hold` fact on the ledger, in order."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- last-fact-for [ledger batch-id]
  (last (filter #(= (:subject %) batch-id) ledger)))

(defn- status-cell [ledger batch-id]
  (let [f (last-fact-for ledger batch-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kw->s (:basis f)))) "</span>")
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      :else (str "<span class=\"muted\">" (esc (kw->s (:t f))) "</span>"))))

(defn- lifecycle-cell [{:keys [batch-shipped? harness-certified?]}]
  (cond
    (and batch-shipped? harness-certified?) "<span class=\"ok\">shipped &amp; certified</span>"
    batch-shipped? "<span class=\"warn\">shipped, not yet certified</span>"
    :else "<span class=\"muted\">in plant</span>"))

(defn- resistance-cell
  "In/out of spec is re-derived through the SAME public predicate the
  governor calls -- never restated from the demo data."
  [{:keys [conductor-resistance-deviation-actual
           conductor-resistance-deviation-min
           conductor-resistance-deviation-max] :as batch}]
  (let [bounds (str "[" conductor-resistance-deviation-min ", "
                    conductor-resistance-deviation-max "]")]
    (if (registry/cable-run-batch-resistance-out-of-range? batch)
      (str "<span class=\"critical\">" (esc conductor-resistance-deviation-actual)
           " &notin; " (esc bounds) "</span>")
      (str "<span class=\"ok\">" (esc conductor-resistance-deviation-actual)
           " &isin; " (esc bounds) "</span>"))))

(defn- newtons
  "Display formatting only: the raw simulated double rendered to 2 dp
  (`physics-2d` yields values like 89.99999999999999). The pass/fail
  verdict beside it is always computed from the UNROUNDED value by the
  governor's own predicate, never from this string."
  [n]
  (if (number? n)
    ;; Locale/ROOT, not the JVM default: the generated file must be
    ;; byte-identical on any machine, and a comma decimal separator
    ;; would not be.
    (String/format java.util.Locale/ROOT "%.2f" (into-array Object [(double n)]))
    (str n)))

(defn- pull-force-cell
  "The peak pull force is the batch's own REAL `physics-2d`-simulated
  telemetry; the verdict is `robotics/simulation-out-of-tolerance?`,
  the same independent recheck the governor runs."
  [{:keys [sim-peak-pull-force-n crimp-effective-mass-kg] :as batch}]
  (let [mass (str " <span class=\"muted\">(m=" (esc crimp-effective-mass-kg) " kg)</span>")]
    (if (robotics/simulation-out-of-tolerance? batch)
      (str "<span class=\"critical\">" (esc (newtons sim-peak-pull-force-n)) " N &lt; "
           (esc (newtons robotics/min-pull-force-n)) " N floor</span>" mass)
      (str "<span class=\"ok\">" (esc (newtons sim-peak-pull-force-n)) " N &ge; "
           (esc (newtons robotics/min-pull-force-n)) " N floor</span>" mass))))

(defn- eol-cell [{:keys [eol-defect-unresolved?]}]
  (if eol-defect-unresolved?
    "<span class=\"critical\">unresolved defect</span>"
    "<span class=\"ok\">clear</span>"))

(defn- sim-cell [{:keys [robotics-sim-verified?]}]
  (if robotics-sim-verified?
    "<span class=\"ok\">on file</span>"
    "<span class=\"muted\">not run</span>"))

(defn- batch-row [ledger {:keys [id batch-name jurisdiction] :as batch}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc batch-name)
          (esc (str jurisdiction " — " (:name (facts/spec-basis jurisdiction) "no spec-basis on file")))
          (resistance-cell batch)
          (pull-force-cell batch)
          (eol-cell batch)
          (sim-cell batch)
          (lifecycle-cell batch)
          (status-cell ledger id)))

(defn- hold-row [{:keys [op subject violations confidence]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td class=\"num\">%s</td></tr>"
          (esc (kw->s (or op :n-a))) (esc subject)
          (str/join "<br>" (map #(str "<span class=\"critical\">" (esc (kw->s (:rule %))) "</span>") violations))
          (str/join "<br>" (map #(esc (:detail %)) violations))
          (esc confidence)))

(defn- ledger-detail [{:keys [t basis summary violations]}]
  (cond
    (= :governor-hold t)
    (str "<span class=\"critical\">"
         (esc (str/join ", " (map (comp kw->s :rule) violations))) "</span>")
    (seq summary) (esc summary)
    :else (esc (str/join ", " (map kw->s basis)))))

(defn- ledger-row [{:keys [t op subject disposition] :as fact}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw->s t)) (esc (kw->s (or op :n-a))) (esc subject)
          (esc (kw->s (or disposition :n-a)))
          (ledger-detail fact)))

(defn- gate-row
  "One row of the op-gate table, DERIVED from `harnessworks.phase`'s
  own `phases`/`write-ops` sets and `harnessworks.governor/high-stakes`
  -- not a hand-typed description of them."
  [op]
  (let [ph phase/default-phase
        {:keys [writes auto]} (get phase/phases ph)
        stakes? (contains? governor/high-stakes op)
        gate (cond
               (not (contains? writes op))
               "<span class=\"critical\">HARD hold &middot; phase-disabled</span>"
               stakes?
               (str "<span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span>")
               (contains? auto op)
               "<span class=\"ok\">auto-commit when governor-clean</span>"
               :else
               "<span class=\"warn\">human approval &middot; not auto-eligible at this phase</span>")]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (kw->s op))
            (if stakes?
              "<span class=\"critical\">safety-critical</span>"
              "<span class=\"muted\">none</span>")
            gate)))

(defn- record-row [kind record]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (get record "record_id")) (esc kind)
          (esc (get record "batch_id")) (esc (get record "jurisdiction"))))

(defn- count-row [[k v]]
  (format "        <tr><td>%s</td><td class=\"num\">%s</td></tr>" (esc (kw->s k)) (esc v)))

;; The ONLY hand-written prose on the page: a static description of the
;; fixed op-gate contract (README `Core contract` / `harnessworks.phase`
;; ns docstring). It documents behavior that is structurally fixed in
;; code, not runtime telemetry -- every number, verdict and rule name
;; elsewhere on the page comes from the live run.
(def ^:private op-gate-contract
  (str "Both actuation ops (<code>:actuation/ship-cable-run-batch</code>, "
       "<code>:actuation/issue-harness-certificate</code>) are deliberately absent from "
       "every phase's auto set, including phase 3 — a permanent structural fact, not a "
       "rollout milestone still to come. HARD governor violations can never be approved "
       "away; a human approver only ever sees a proposal the Cable-Integrity Governor "
       "has already cleared."))

(defn render
  "Renders the whole operator-console document from a store `db` that
  has already been driven by `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        hs (holds db)
        pkg (export/audit-package db)
        gate-ops (sort-by kw->s phase/write-ops)]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-2732 &middot; wire/cable/harness plant</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wire/cable/harness plant (ISIC 2732) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never dispatches hardware</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Cable-run batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot — generated from a real <code>harnessworks.operation</code> actor run over <code>harnessworks.store/seed-db</code> by <code>harnessworks.render-html</code> (<code>clojure -M:dev:render-html</code>). Peak pull force is this batch's own <code>physics-2d</code>-simulated tensile-pull telemetry; the in-spec / out-of-tolerance verdicts are re-derived through the same predicates the Cable-Integrity Governor calls.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Name</th><th>Product class</th><th>Conductor-resistance deviation</th><th>Sim peak pull force</th><th>End-of-line</th><th>Robot mission</th><th>Lifecycle</th><th>Last op</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run (" (count hs) ")</h2>\n"
     "    <p class=\"muted\">Every row is a real <code>:governor-hold</code> fact the Cable-Integrity Governor wrote to the append-only ledger. Rule names and detail strings are the governor's own <code>:violations</code> entries. HARD holds are un-overridable — none of these ever reached a human approver.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Batch</th><th>Rule</th><th>Governor detail</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row hs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Op gate (Cable-Integrity Governor + phase " phase/default-phase " “"
     (esc (:label (get phase/phases phase/default-phase))) "”)</h2>\n"
     "    <p class=\"muted\">Derived from <code>harnessworks.phase/write-ops</code>, <code>harnessworks.phase/phases</code> and <code>harnessworks.governor/high-stakes</code>. Advisor confidence floor: <span class=\"num\">" governor/confidence-floor "</span>. Minimum crimp/conductor pull force: <span class=\"num\">" robotics/min-pull-force-n "</span> N.</p>\n"
     "    <p class=\"muted\">" op-gate-contract "</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Stake</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row gate-ops)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run — " (count ledger) " facts)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log: every commit and every hold this scenario produced, in order.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Batch</th><th>Disposition</th><th>Basis / summary</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Social hand-off</h2>\n"
     "    <p class=\"muted\">Draft records minted by <code>harnessworks.registry</code> during this run — unsigned; signature and OEM/inspector submission are the plant's own acts. Counts come from <code>harnessworks.export/audit-package</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Batch</th><th>Product class</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (concat
                     (map (partial record-row "cable-run-batch shipment (draft)")
                          (store/shipment-history db))
                     (map (partial record-row "harness certificate (draft)")
                          (store/certificate-history db)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <table>\n"
     "      <thead><tr><th>Audit package</th><th>Count</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map count-row (sort-by (comp kw->s key) (:counts pkg)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        hs (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is
    ;; not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :out out})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render db)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/certificate-history db)) " certificate drafts)"))))
