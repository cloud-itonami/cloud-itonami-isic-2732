(ns harnessworks.harnessworksadvisor
  "Harness Advisor client -- the *contained intelligence node* for the
  wire/cable/harness-manufacturing actor.

  It normalizes cable-run-batch intake, drafts a per-product-class
  harness-standard evidence checklist, screens batches for an
  unresolved end-of-line-detected defect, drafts the batch-shipment
  action, and drafts the harness-certificate-issuance action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real robot shipment/harness-certificate
  issuance. Every output is censored downstream by `harnessworks.
  governor` before anything touches the SSoT, and `:actuation/ship-
  cable-run-batch`/`:actuation/issue-harness-certificate` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/ship-cable-run-batch | :actuation/issue-harness-certificate | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [harnessworks.facts :as facts]
            [harnessworks.registry :as registry]
            [harnessworks.robotics :as robotics]
            [harnessworks.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the batch, conductor-resistance-deviation figures or
  product class. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "ケーブルランバッチ記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-requirements
  "Per-product-class harness-standard evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a product class with NO official
  spec-basis in `harnessworks.facts` -- the Cable-Integrity Governor
  must reject this (never invent a product class's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/batch db subject)
        product-class (if no-spec? "UNK" (:jurisdiction a))
        sb (facts/spec-basis product-class)]
    (if (nil? sb)
      {:summary    (str product-class " の公式spec-basisが見つかりません")
       :rationale  "harnessworks.facts に未登録の製品クラス。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction product-class :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str product-class " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction product-class
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-eol-defect
  "End-of-line-defect screening draft. `:eol-defect-unresolved?` on the
  batch record injects the failure mode: the Cable-Integrity Governor
  must HOLD, un-overridably, on any unresolved defect."
  [db {:keys [subject]}]
  (let [a (store/batch db subject)]
    (cond
      (nil? a)
      {:summary "対象バッチ記録が見つかりません" :rationale "no batch record"
       :cites [] :effect :eol-screen/set :value {:batch-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:eol-defect-unresolved? a))
      {:summary    (str (:batch-name a) ": 未解決の完成検査欠陥を検出")
       :rationale  "完成検査(連続性/絶縁抵抗)スクリーニングが未解決の欠陥を検出。人手確認とホールドが必須。"
       :cites      [:eol-check]
       :effect     :eol-screen/set
       :value      {:batch-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:batch-name a) ": 未解決の完成検査欠陥なし")
       :rationale  "完成検査(連続性/絶縁抵抗)欠陥スクリーニング完了。"
       :cites      [:eol-check]
       :effect     :eol-screen/set
       :value      {:batch-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- simulate-tensile-pull-test
  "Runs the robot continuity/tensile-pull/insulation-resistance
  verification mission (`harnessworks.robotics`) and drafts its result
  as a proposal. High confidence -- the mission itself is a REAL
  time-stepped `physics-2d` simulation of the conductor/crimp
  tensile-pull-test derived from the batch's own recorded
  `:crimp-effective-mass-kg`, not an LLM guess; the Cable-Integrity
  Governor still independently re-derives :passed? from the real
  simulated `:sim-peak-pull-force-n` telemetry before any
  `:actuation/ship-cable-run-batch` proposal may commit -- see
  `harnessworks.governor`'s `robotics-simulation-violations`."
  [db {:keys [subject]}]
  (let [a (store/batch db subject)]
    (if (nil? a)
      {:summary "対象バッチ記録が見つかりません" :rationale "no batch record"
       :cites [] :effect :batch/upsert :value {:id subject :robotics-sim-verified? false}
       :stake nil :confidence 0.0}
      (let [{:keys [mission actions passed? sim-peak-pull-force-n sim-peak-decel-mps2]}
            (robotics/simulate-tensile-pull-test subject a)]
        {:summary    (str subject ": 連続性/引張プル/絶縁抵抗検証ミッション " (if passed? "合格" "不合格")
                          " (実測ピーク引張荷重=" sim-peak-pull-force-n "N)")
         :rationale  (str "mission=" (:mission/id mission) " actions=" (count actions)
                          " sim-peak-decel-mps2=" sim-peak-decel-mps2
                          " crimp-effective-mass-kg=" (:crimp-effective-mass-kg a))
         :cites      [(:mission/id mission)]
         :effect     :batch/upsert
         :value      {:id subject
                      :robotics-sim-verified? passed?
                      :sim-peak-pull-force-n sim-peak-pull-force-n
                      :sim-peak-decel-mps2 sim-peak-decel-mps2
                      :robotics-sim-record {:mission-id (:mission/id mission)
                                            :actions (mapv #(dissoc % :action) actions)
                                            :passed? passed?
                                            :sim-peak-pull-force-n sim-peak-pull-force-n}}
         :stake      nil
         :confidence 0.95}))))

(defn- propose-cable-run-batch-shipment
  "Draft the actual CABLE-RUN-BATCH-SHIPMENT action -- dispatching a
  real robot shipment/handling action releasing a finished cable-run
  batch onward. ALWAYS `:stake :actuation/ship-cable-run-batch` --
  this is a REAL-WORLD safety-critical act (the batch feeds automotive
  wiring-harness and smartphone flex-cable supply chains), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`harnessworks.phase`); the
  governor also always escalates on `:actuation/ship-cable-run-batch`.
  Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/batch db subject)]
    {:summary    (str subject " 向け出荷実行提案"
                      (when a (str " (batch=" (:batch-name a) ")")))
     :rationale  (if a
                   (str "conductor-resistance-deviation-actual=" (:conductor-resistance-deviation-actual a)
                        " spec=[" (:conductor-resistance-deviation-min a) "," (:conductor-resistance-deviation-max a) "]")
                   "バッチ記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :batch/mark-shipped
     :value      {:batch-id subject}
     :stake      :actuation/ship-cable-run-batch
     :confidence (if (and a (not (registry/cable-run-batch-resistance-out-of-range? a))) 0.9 0.3)}))

(defn- propose-harness-certificate
  "Draft the actual HARNESS-CERTIFICATE action -- issuing a real
  Cable/Harness Test Certificate (per IPC/WHMA-A-620) certifying a
  batch as shippable. ALWAYS `:stake :actuation/issue-harness-
  certificate` -- this is a REAL-WORLD safety-critical act, never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`harnessworks.phase`); the
  governor also always escalates on `:actuation/issue-harness-
  certificate`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/batch db subject)]
    {:summary    (str subject " 向けハーネス証明書発行提案"
                      (when a (str " (batch=" (:batch-name a) ")")))
     :rationale  (if a
                   "product-class-evidence-checklist referenced"
                   "バッチ記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :batch/mark-certified
     :value      {:batch-id subject}
     :stake      :actuation/issue-harness-certificate
     :confidence (if a 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :cable-run-batch/intake                       (normalize-intake db request)
    :harness-standard-rules/verify                (verify-requirements db request)
    :end-of-line-quality/screen                   (screen-eol-defect db request)
    :robotics/simulate-tensile-pull-test          (simulate-tensile-pull-test db request)
    :actuation/ship-cable-run-batch               (propose-cable-run-batch-shipment db request)
    :actuation/issue-harness-certificate          (propose-harness-certificate db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはワイヤーハーネス/ケーブル製造工場の出荷・ハーネス証明書発行エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:batch/upsert|:verification/set|:eol-screen/set|"
       ":batch/mark-shipped|:batch/mark-certified) "
       "(:robotics/simulate-tensile-pull-test も :batch/upsert で "
       ":robotics-sim-verified? を提案する) "
       ":stake(:actuation/ship-cable-run-batch か :actuation/issue-harness-certificate か nil) :confidence(0..1)。\n"
       "重要: 登録されていない製品クラスの要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :harness-standard-rules/verify                {:batch (store/batch st subject)}
    :end-of-line-quality/screen                   {:batch (store/batch st subject)}
    :robotics/simulate-tensile-pull-test           {:batch (store/batch st subject)}
    :actuation/ship-cable-run-batch                {:batch (store/batch st subject)}
    :actuation/issue-harness-certificate           {:batch (store/batch st subject)}
    {:batch (store/batch st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Cable-Integrity Governor
  escalates/holds -- an LLM hiccup can never auto-ship a cable-run
  batch or auto-issue a harness certificate."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :harnessworksadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
