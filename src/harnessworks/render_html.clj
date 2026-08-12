(ns harnessworks.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo. Before this
  namespace existed, `docs/samples/operator-console.html` was a
  HAND-WRITTEN stub: its batch rows carried invented, rounded figures
  (`~100 N (sim)`, `~30 N (sim, below 60 N floor)`) that nothing in
  this repo ever computed, and it hard-coded its own raw-hex CSS
  instead of this workspace's base design system. Every number, id,
  status, rule name and reason on the page is now REAL output of the
  actor stack (`harnessworks.operation` -> `harnessworks.governor` ->
  `harnessworks.phase` -> `harnessworks.store`), driven through the
  same `langgraph.graph/run*` entry point `harnessworks.sim` uses.

  Nothing here is a mock: `run-demo!` seeds a real `harnessworks.store`
  MemStore, runs real operations through the real Cable-Integrity
  Governor and the real phase gate, and `render` reads only the
  resulting store, ledger and registry drafts. The action-gate table is
  likewise DERIVED -- it calls `harnessworks.phase/gate` for every op
  in `harnessworks.phase/write-ops` and reports what the real gate
  answers, rather than restating the gate in prose that can drift away
  from it.

  Determinism: the whole stack is pure (the advisor is
  `harnessworks.harnessworksadvisor/mock-advisor`, the pull-test
  telemetry is `harnessworks.robotics`' deterministic `physics-2d`
  simulation, and `harnessworks.registry` numbers records off a
  product-class-scoped sequence). No timestamp, wall clock, random id
  or hash-map iteration order reaches the page -- reruns from the same
  seed are byte-identical.

  Build-time invariant: `-main` REFUSES to write the file if the
  resulting ledger contains zero `:governor-hold` facts. A console that
  only ever shows happy paths would silently stop demonstrating the one
  property this actor exists to have -- that some proposals never reach
  a human at all -- so the HARD-hold requirement is enforced by the
  build, not by convention (precedent: cloud-itonami-isic-2513).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
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
  "The human quality engineer on whose behalf the actor runs -- the
  same context `harnessworks.sim` injects."
  {:actor-id "op-1" :actor-role :quality-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

;; ----------------------------- the real run -----------------------------

(defn run-demo!
  "Runs a freshly seeded `harnessworks.store` through a scenario that
  reaches every disposition this actor can produce, so the console
  shows both sides of the gate rather than a happy path only.

  Clean/approved paths:
    batch-1 walks a complete lifecycle -- intake (the ONLY op phase 3
    may auto-commit), harness-standard-rules evidence verification,
    end-of-line-defect screening, the real `physics-2d` conductor/crimp
    tensile-pull-test mission, then the cable-run-batch shipment and
    the harness certificate. The last two ALWAYS escalate (no phase
    ever auto-commits an actuation) and are approved by a human here.

  HARD holds -- none of these ever reaches a human, and no approval can
  override them (all eight of `harnessworks.governor`'s hard rules
  fire):
    batch-1 shipment attempted before ANY verification
              -> :evidence-incomplete + :robotics-simulation-missing
    batch-2 verification for an unregistered product class
              -> :no-spec-basis
    batch-3 shipment attempted before its pull-test mission ran
              -> :robotics-simulation-missing
    batch-3 shipment after a passing mission, but its recorded
            conductor-resistance deviation is outside its own spec
              -> :cable-run-batch-resistance-out-of-range
    batch-5 shipment with `:robotics-sim-verified?` already `true` on
            file, but an independent recheck of the REAL simulated peak
            pull force disagrees (under-crimped / wrong-gauge terminal)
              -> :robotics-simulation-out-of-tolerance
    batch-4 end-of-line screening that itself finds an unresolved
            defect -> :end-of-line-defect-unresolved
    batch-1 shipped a second time  -> :already-shipped
    batch-1 certified a second time -> :already-certified

  Returns the resulting store. Every field `render` reads below comes
  out of this run."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; --- batch-1: the clean lifecycle (plus one premature actuation) ---
    (exec! actor "b1-intake"
           {:op :cable-run-batch/intake :subject "batch-1"
            :patch {:id "batch-1"
                    :batch-name "Meridian Automotive Wiring Harness Lot WH-2044"}})

    (exec! actor "b1-ship-premature"
           {:op :actuation/ship-cable-run-batch :subject "batch-1"})

    (exec! actor "b1-verify" {:op :harness-standard-rules/verify :subject "batch-1"})
    (approve! actor "b1-verify")

    (exec! actor "b1-eol" {:op :end-of-line-quality/screen :subject "batch-1"})
    (approve! actor "b1-eol")

    (exec! actor "b1-sim" {:op :robotics/simulate-tensile-pull-test :subject "batch-1"})
    (approve! actor "b1-sim")

    (exec! actor "b1-ship" {:op :actuation/ship-cable-run-batch :subject "batch-1"})
    (approve! actor "b1-ship")

    (exec! actor "b1-certify" {:op :actuation/issue-harness-certificate :subject "batch-1"})
    (approve! actor "b1-certify")

    ;; --- batch-2: no official spec-basis for its product class ---
    (exec! actor "b2-verify"
           {:op :harness-standard-rules/verify :subject "batch-2" :no-spec? true})

    ;; --- batch-3: verified, simulated, but out of resistance spec ---
    (exec! actor "b3-verify" {:op :harness-standard-rules/verify :subject "batch-3"})
    (approve! actor "b3-verify")

    (exec! actor "b3-ship-premature"
           {:op :actuation/ship-cable-run-batch :subject "batch-3"})

    (exec! actor "b3-sim" {:op :robotics/simulate-tensile-pull-test :subject "batch-3"})
    (approve! actor "b3-sim")

    (exec! actor "b3-ship" {:op :actuation/ship-cable-run-batch :subject "batch-3"})

    ;; --- batch-5: mission "on file", independent recheck disagrees ---
    (exec! actor "b5-verify" {:op :harness-standard-rules/verify :subject "batch-5"})
    (approve! actor "b5-verify")

    (exec! actor "b5-ship" {:op :actuation/ship-cable-run-batch :subject "batch-5"})

    ;; --- batch-4: the screening op HARD-holds on its own finding ---
    (exec! actor "b4-eol" {:op :end-of-line-quality/screen :subject "batch-4"})

    ;; --- batch-1 again: double-actuation guards ---
    (exec! actor "b1-ship-again" {:op :actuation/ship-cable-run-batch :subject "batch-1"})
    (exec! actor "b1-certify-again" {:op :actuation/issue-harness-certificate :subject "batch-1"})
    db))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str [v]
  (if (keyword? v) (name v) (str v)))

(defn- span [cls v] (str "<span class=\"" cls "\">" (esc v) "</span>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- auto-ops
  "The ops the CURRENT phase may auto-commit, straight out of
  `harnessworks.phase/phases` -- never restated by hand."
  []
  (:auto (get phase/phases phase/default-phase)))

(defn- ledger-for [ledger batch-id]
  (filterv #(= batch-id (:subject %)) ledger))

(defn- last-status-cell [ledger batch-id]
  (let [f (last (ledger-for ledger batch-id))]
    (cond
      (nil? f) (span "muted" "no activity")
      (= :governor-hold (:t f))
      (str (span "critical" "HARD hold")
           " " (code (str/join " " (map kw-str (:basis f)))))
      (= :committed (:t f))
      (str (span "ok" (if (contains? (auto-ops) (:op f)) "auto-committed" "approved & committed"))
           " " (code (kw-str (:op f))))
      :else (span "muted" (kw-str (:t f))))))

(defn- batch-row
  "One cable-run batch. The resistance and pull-force verdicts are
  INDEPENDENTLY recomputed here through the very same predicates the
  governor uses (`harnessworks.registry/cable-run-batch-resistance-out-
  of-range?`, `harnessworks.robotics/simulation-out-of-tolerance?`), so
  the console can never disagree with the gate."
  [ledger {:keys [id batch-name jurisdiction
                  conductor-resistance-deviation-actual
                  conductor-resistance-deviation-min
                  conductor-resistance-deviation-max
                  sim-peak-pull-force-n eol-defect-unresolved?
                  batch-shipped? harness-certified?
                  shipment-number certificate-number] :as batch}]
  (let [r-bad? (registry/cable-run-batch-resistance-out-of-range? batch)
        p-bad? (robotics/simulation-out-of-tolerance? batch)]
    (row (code id)
         (esc batch-name)
         (esc jurisdiction)
         (span (if r-bad? "err" "ok")
               (str conductor-resistance-deviation-actual
                    " in [" conductor-resistance-deviation-min
                    ", " conductor-resistance-deviation-max "]"))
         (span (if p-bad? "err" "ok")
               (str sim-peak-pull-force-n " N (floor " robotics/min-pull-force-n " N)"))
         (if eol-defect-unresolved?
           (span "err" "unresolved")
           (span "ok" "none"))
         (if batch-shipped? (span "ok" shipment-number) (span "muted" "not shipped"))
         (if harness-certified? (span "ok" certificate-number) (span "muted" "not certified"))
         (last-status-cell ledger id))))

(defn- gate-row
  "What the REAL phase gate answers for one op, asked twice: once with a
  clean governor verdict and once with a HARD one."
  [op]
  (let [clean (phase/gate phase/default-phase {:op op} :commit)
        hard  (phase/gate phase/default-phase {:op op} :hold)
        auto? (contains? (auto-ops) op)
        stakes? (contains? governor/high-stakes op)]
    (row (code op)
         (if stakes?
           (span "critical" "safety-critical actuation")
           (span "muted" "none"))
         (case (:disposition clean)
           :commit (span "ok" "auto-commit")
           :escalate (span "warn" (str "human approval (" (kw-str (:reason clean)) ")"))
           (span "err" (str "hold (" (kw-str (:reason clean)) ")")))
         (span "critical" (str (kw-str (:disposition hard)) " -- no override"))
         (if auto? (span "ok" "yes") (span "muted" "no")))))

(defn- hold-rule-rows
  "Every HARD rule that actually fired in this run, grouped by rule name
  with the governor's own `:detail` text. Sorted by rule name so the
  page is order-stable."
  [ledger]
  (let [violations (mapcat :violations (filter #(= :governor-hold (:t %)) ledger))
        by-rule (group-by :rule violations)]
    (for [rule (sort-by name (keys by-rule))
          :let [hits (get by-rule rule)]]
      (row (code rule)
           (str (count hits))
           (esc (:detail (first hits)))))))

(defn- ledger-row [i {:keys [t op subject summary violations confidence]}]
  (row (str i)
       (if (= :governor-hold t)
         (span "critical" "governor-hold")
         (span "ok" (kw-str t)))
       (code (kw-str op))
       (code subject)
       (str confidence)
       (if (= :governor-hold t)
         (str "<span class=\"basis\">"
              (str/join " " (map #(code (kw-str (:rule %))) violations))
              "</span>")
         (str "<span class=\"basis\">" (esc summary) "</span>"))))

(defn- draft-row [kind r]
  (row (code (get r "record_id"))
       (esc kind)
       (code (get r "batch_id"))
       (esc (get r "jurisdiction"))
       (span "muted" "unsigned draft -- the plant signs offline")))

(defn- coverage-row [[product-class {:keys [name owner-authority required-evidence legal-basis]}]]
  (row (code product-class)
       (esc name)
       (esc owner-authority)
       (str (count required-evidence))
       (str "<span class=\"basis\">" (esc legal-basis) "</span>")))

;; ----------------------------- page -----------------------------

(def ^:private app-css
  "The single app-level rule this page needs: the governor's own basis
  text and standards citations contain long unbroken URLs, which would
  otherwise force the whole table wider than the viewport. No colour, no
  spacing, no type -- those all come from jp-go-dds + its console skin."
  ".basis { overflow-wrap: anywhere; word-break: break-word; }")

(defn render
  "Renders the operator console from a store that has already been
  driven by `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        counts (:counts (export/audit-package db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)
        auto-commits (filterv #(contains? (auto-ops) (:op %)) commits)
        phase-label (:label (get phase/phases phase/default-phase))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2732 &middot; wire/cable/harness plant &middot; Operator Console</title>\n"
     "<style>\n" (skin/dds+skin) "\n" app-css "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wire, cable &amp; wiring-harness manufacturing (ISIC 2732) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never dispatches hardware</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "This run"
      (str "Generated at build time by <code>harnessworks.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>) from a real "
           "<code>harnessworks.operation</code> actor run — every figure below is "
           "actor output, none of it is written into this page by hand. "
           "Rollout phase <strong>" phase/default-phase " (" (esc phase-label)
           ")</strong>, governor confidence floor <strong>" governor/confidence-floor
           "</strong>.")
      (table ["Cable-run batches" "Ledger facts" "Committed" "of which auto-committed"
              "HARD governor holds" "Shipment drafts" "Harness certificates"]
             [(row (str (:batches counts))
                   (str (:ledger counts))
                   (span "ok" (count commits))
                   (span "ok" (count auto-commits))
                   (span "critical" (count holds))
                   (str (:shipments counts))
                   (str (:harness-certificates counts)))]))

     (section
      "Cable-run batches"
      (str "Live snapshot of <code>harnessworks.store</code> after the run. The "
           "resistance and pull-force verdicts are recomputed here with the same "
           "predicates the Cable-Integrity Governor uses "
           "(<code>registry/cable-run-batch-resistance-out-of-range?</code>, "
           "<code>robotics/simulation-out-of-tolerance?</code>), so the console "
           "cannot disagree with the gate.")
      (table ["Batch" "Name" "Product class" "Conductor-resistance deviation"
              "Peak pull force (physics-2d)" "End-of-line defect" "Shipment"
              "Harness certificate" "Last op"]
             (map (partial batch-row ledger) batches)))

     (section
      "Action gate"
      (str "Not a description of the gate — this table is produced by calling "
           "<code>harnessworks.phase/gate</code> for every op in "
           "<code>phase/write-ops</code> at phase " phase/default-phase
           ", once with a clean governor verdict and once with a HARD one. "
           "The two actuation ops are absent from every phase's "
           "<code>:auto</code> set as a permanent structural fact, and "
           "<code>harnessworks.governor/high-stakes</code> escalates them "
           "independently — two layers, not one.")
      (table ["Op" "Stake" "Governor-clean disposition" "Governor-HARD disposition"
              (str "Auto-eligible at phase " phase/default-phase "?")]
             (map gate-row (sort-by str phase/write-ops))))

     (section
      "HARD governor holds this run"
      (str "Every rule below actually fired. A HARD hold is never routed to the "
           "approval node — no human ever sees it, and no approval can override "
           "it. The wording is the governor's own <code>:detail</code>, quoted "
           "verbatim from the ledger.")
      (table ["Rule" "Times fired" "Governor detail"] (hold-rule-rows ledger)))

     (section
      "Audit ledger (this run)"
      (str "The append-only decision-fact log written by the actor's "
           "<code>:commit</code> and <code>:hold</code> nodes — "
           (count ledger) " facts, in the order they were appended.")
      (table ["#" "Fact" "Op" "Batch" "Confidence" "Basis / summary"]
             (map-indexed (fn [i f] (ledger-row (inc i) f)) ledger)))

     (section
      "Registry drafts"
      (str "Records <code>harnessworks.registry</code> built for the two committed "
           "actuations. Both are UNSIGNED: numbering and record construction are "
           "this actor's job, signing is the manufacturer's own act.")
      (table ["Record" "Kind" "Batch" "Product class" "Status"]
             (concat (map (partial draft-row "cable-run-batch-shipment-draft")
                          (store/shipment-history db))
                     (map (partial draft-row "harness-certificate-draft")
                          (store/certificate-history db)))))

     (section
      "Spec-basis coverage"
      (str "<code>harnessworks.facts/catalog</code> as it stands — "
           (:covered (facts/coverage)) " product classes seeded. A product class "
           "that is not in this table has NO spec-basis, and a verification "
           "proposal for it HARD-holds on <code>:no-spec-basis</code> rather than "
           "inventing requirements (batch-2 above).")
      (table ["Product class" "Name" "Owner authority" "Required evidence items"
              "Legal / standards basis"]
             (map coverage-row (sort-by key facts/catalog))))

     "</main>\n"
     "<footer>\n"
     "  <p>Regenerate with <code>clojure -M:dev:render-html</code>. Deterministic: "
     "the advisor is the deterministic mock, the pull-test telemetry is "
     "<code>harnessworks.robotics</code>' <code>physics-2d</code> simulation, and no "
     "timestamp or random id enters the page — reruns from the same seed are "
     "byte-identical. Styling is <code>jp-go-dds</code> (デジタル庁デザインシステム) "
     "plus its console skin.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)]
    ;; Build-time invariant: this console exists to show that some
    ;; proposals never reach a human. A run with no HARD hold would
    ;; quietly stop demonstrating that, so refuse to publish it.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO :governor-hold ledger facts. "
                           "The operator console must show at least one HARD hold "
                           "(a hold that never reaches a human) -- otherwise it only "
                           "documents the happy path. Fix run-demo! so a hard governor "
                           "rule actually fires.")
                      {:out out
                       :ledger-facts (count ledger)
                       :governor-holds 0
                       :committed (count commits)})))
    (spit out (render db))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count commits) " committed, "
                  (count holds) " HARD governor holds, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/certificate-history db)) " harness certificates)"))))
