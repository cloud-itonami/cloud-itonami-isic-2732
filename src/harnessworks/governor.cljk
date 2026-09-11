(ns harnessworks.governor
  "Cable-Integrity Governor -- the independent compliance layer that
  earns the Harness Advisor the right to commit. The LLM has no notion
  of IPC/WHMA-A-620 evidence discipline, whether a cable-run batch's
  own measured conductor-resistance deviation actually stays within
  its own recorded spec bounds, whether an end-of-line-detected defect
  against the batch has actually stayed unresolved, or when an act
  stops being a draft and becomes a real-world robot batch shipment or
  harness-certificate issuance, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD -- the wire/cable/
  harness-manufacturer analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Seven checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated harness-standard spec-basis, incomplete evidence, a robot
  continuity/tensile-pull/insulation-resistance simulation that never
  ran or that independently re-checks out-of-tolerance, an out-of-spec
  batch, an unresolved end-of-line defect, or a double shipment/
  certificate-issuance). The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `harnessworks.phase`: for `:stake :actuation/ship-
  cable-run-batch`/`:actuation/issue-harness-certificate` (a real
  safety-critical act) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the harness-standard-rules
                                       proposal cite an OFFICIAL source
                                       (`harnessworks.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:actuation/ship-cable-run-
                                       batch`/`:actuation/issue-
                                       harness-certificate`, has the
                                       batch actually been verified
                                       with a full IPC/WHMA-A-620
                                       workmanship-acceptance-report/
                                       SAE-J1128-or-UL-758-test-report/
                                       ISO-6722-or-IPC-A-610-report/
                                       end-of-line-quality-chain-of-
                                       custody-record evidence
                                       checklist on file?
    3. Robot simulation missing or
       independently out-of-
       tolerance                    -- for `:actuation/ship-cable-run-
                                       batch`, has the robot
                                       continuity/tensile-pull/
                                       insulation-resistance
                                       verification mission
                                       (`harnessworks.robotics`)
                                       actually run and been recorded
                                       on the batch (`:robotics-sim-
                                       verified?`)? AND INDEPENDENTLY
                                       recompute whether the batch's
                                       own recorded REAL `physics-2d`-
                                       simulated conductor/crimp
                                       tensile-pull-test telemetry
                                       (`:sim-peak-pull-force-n`, from
                                       ADR-2607152000's real
                                       time-stepped simulation) falls
                                       below the real minimum required
                                       pull force (`harnessworks.
                                       robotics/simulation-out-of-
                                       tolerance?`), ignoring whatever
                                       :passed? verdict the mission run
                                       itself stored -- the same
                                       'ground truth, not self-report'
                                       discipline check 4 below uses
                                       for conductor resistance.
    4. Cable-run batch conductor-
       resistance out of range      -- for `:actuation/ship-cable-run-
                                       batch`, INDEPENDENTLY recompute
                                       whether the batch's own measured
                                       conductor-resistance deviation
                                       falls outside its own recorded
                                       spec bounds (`harnessworks.
                                       registry/cable-run-batch-
                                       resistance-out-of-range?`) --
                                       needs no proposal inspection or
                                       stored-verdict lookup at all. A
                                       further instance of this fleet's
                                       two-sided range check family
                                       (see `harnessworks.registry`'s
                                       ns docstring for the lineage).
    5. End-of-line defect unresolved -- reported by THIS proposal itself
                                       (an `:end-of-line-quality/
                                       screen` that just found an
                                       unresolved defect), or
                                       already on file for the
                                       batch (`:end-of-line-quality/
                                       screen`/`:actuation/issue-
                                       harness-certificate`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME
                                       discipline `casualty.governor/
                                       sanctions-violations`/
                                       `automotive.governor/end-of-
                                       line-defect-unresolved-
                                       violations`/`autoparts.governor/
                                       process-capability-defect-
                                       unresolved-violations`
                                       established -- exercised in
                                       tests/demo via `:end-of-line-
                                       quality/screen` DIRECTLY, not via
                                       an actuation op against an
                                       unscreened batch -- see this
                                       ns's own test suite.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/ship-
                                       cable-run-batch`/`:actuation/
                                       issue-harness-certificate` (REAL
                                       safety-critical acts) ->
                                       escalate.

  Two more guards, double-shipment/double-certificate-issuance
  prevention, are enforced but NOT listed as numbered HARD checks
  above because they need no upstream comparison at all --
  `already-shipped-violations`/`already-certified-violations` refuse
  to ship a batch action/issue a harness certificate for the SAME
  batch twice, off dedicated `:batch-shipped?`/`:harness-certified?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior sibling governor's
  guards establish, informed by `cloud-itonami-isic-6492`'s status-
  lifecycle bug (ADR-2607071320)."
  (:require [harnessworks.facts :as facts]
            [harnessworks.registry :as registry]
            [harnessworks.robotics :as robotics]
            [harnessworks.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Shipping a real cable-run batch onward and issuing a real harness
  certificate are the two real-world actuation events this actor
  performs -- a two-member set, matching every prior dual-actuation
  sibling's shape."
  #{:actuation/ship-cable-run-batch :actuation/issue-harness-certificate})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:harness-standard-rules/verify` (or actuation) proposal with no
  spec-basis citation is a HARD violation -- never invent a product
  class's harness-standard requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:harness-standard-rules/verify :actuation/ship-cable-run-batch :actuation/issue-harness-certificate} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は harness-standard 要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/ship-cable-run-batch`/`:actuation/issue-harness-
  certificate`, the product class's required IPC/WHMA-A-620/SAE-J1128-
  or-UL-758/ISO-6722-or-IPC-A-610/end-of-line-quality-chain-of-custody
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/ship-cable-run-batch :actuation/issue-harness-certificate} op)
    (let [a (store/batch st subject)
          verification (store/requirements-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "製品クラスの必要書類(IPC/WHMA-A-620 workmanship 受入報告書/SAE J1128 or UL 758 試験報告書/ISO 6722 or IPC-A-610 報告書/完成検査連鎖記録等)が充足していない状態での提案"}]))))

(defn- robotics-simulation-violations
  "For `:actuation/ship-cable-run-batch`: HARD hold if the robot
  continuity/tensile-pull/insulation-resistance verification mission
  (`harnessworks.robotics`) never ran and was recorded on the batch
  (`:robotics-sim-verified?`), OR if it did but an INDEPENDENT
  recompute of the batch's own REAL `physics-2d`-simulated conductor/
  crimp tensile-pull-test telemetry (`:sim-peak-pull-force-n`,
  ADR-2607152000 -- `harnessworks.robotics/simulation-out-of-
  tolerance?`) says out-of-tolerance right now -- never trusts the
  mission's own stored :passed? verdict alone, the same discipline
  `cable-run-batch-resistance-out-of-range-violations` below uses for
  conductor resistance."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cable-run-batch)
    (let [a (store/batch st subject)]
      (cond
        (not (:robotics-sim-verified? a))
        [{:rule :robotics-simulation-missing
          :detail (str subject " の連続性/引張プル/絶縁抵抗検証ロボットミッションが未実行・未合格")}]

        (robotics/simulation-out-of-tolerance? a)
        [{:rule :robotics-simulation-out-of-tolerance
          :detail (str subject " の実測ピーク引張荷重(" (:sim-peak-pull-force-n a)
                       "N)が独立再検証で許容下限(" robotics/min-pull-force-n "N)を下回る")}]))))

(defn- cable-run-batch-resistance-out-of-range-violations
  "For `:actuation/ship-cable-run-batch`, INDEPENDENTLY recompute
  whether the batch's own conductor-resistance deviation falls outside
  its own recorded spec bounds via `harnessworks.registry/cable-run-
  batch-resistance-out-of-range?` -- needs no proposal inspection or
  stored-verdict lookup at all, since its inputs are permanent
  ground-truth fields already on the batch."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cable-run-batch)
    (let [a (store/batch st subject)]
      (when (registry/cable-run-batch-resistance-out-of-range? a)
        [{:rule :cable-run-batch-resistance-out-of-range
          :detail (str subject " の実測導体抵抗偏差(" (:conductor-resistance-deviation-actual a)
                      ")が仕様範囲[" (:conductor-resistance-deviation-min a) "," (:conductor-resistance-deviation-max a) "]を逸脱")}]))))

(defn- end-of-line-defect-unresolved-violations
  "An unresolved end-of-line-detected defect -- reported by THIS
  proposal (e.g. an `:end-of-line-quality/screen` that itself just
  found one), or already on file in the store for the batch
  (`:end-of-line-quality/screen`/`:actuation/issue-harness-
  certificate`) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        batch-id (when (contains? #{:end-of-line-quality/screen :actuation/issue-harness-certificate} op) subject)
        hit-on-file? (and batch-id (= :unresolved (:verdict (store/eol-screen-of st batch-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :end-of-line-defect-unresolved
        :detail "未解決の完成検査欠陥がある状態でのハーネス証明書発行提案は進められない"}])))

(defn- already-shipped-violations
  "For `:actuation/ship-cable-run-batch`, refuses to ship a batch
  action for the SAME batch twice, off a dedicated `:batch-shipped?`
  fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/ship-cable-run-batch)
    (when (store/batch-already-shipped? st subject)
      [{:rule :already-shipped
        :detail (str subject " は既に出荷実行済み")}])))

(defn- already-certified-violations
  "For `:actuation/issue-harness-certificate`, refuses to issue a
  harness certificate for the SAME batch twice, off a dedicated
  `:harness-certified?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/issue-harness-certificate)
    (when (store/batch-already-certified? st subject)
      [{:rule :already-certified
        :detail (str subject " は既にハーネス証明書発行済み")}])))

(defn check
  "Censors a Harness Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (robotics-simulation-violations request st)
                           (cable-run-batch-resistance-out-of-range-violations request st)
                           (end-of-line-defect-unresolved-violations request proposal st)
                           (already-shipped-violations request st)
                           (already-certified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
