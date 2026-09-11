(ns harnessworks.facts
  "Per-product-class harness/cable-assembly evidence catalog -- the
  G2-style spec-basis table the Cable-Integrity Governor checks every
  `:harness-standard-rules/verify` proposal against.

  Unlike `cloud-itonami-isic-2910`'s vehicle type-approval (a
  GOVERNMENT-mandated regime -- MLIT/NHTSA/DVSA/KBA statute), the
  standards catalogued here are INDUSTRY/standards-body specifications
  (IPC/WHMA, SAE, ISO, UL) -- international in scope, not scoped to a
  single national jurisdiction the way vehicle homologation is. This
  catalog is therefore keyed by PRODUCT CLASS, not by ISO3 country
  code (the same honest field-reinterpretation discipline
  `autoparts.facts` used when it disclosed PPAP as OEM/industry-driven
  rather than government statute, while still reusing the fleet's
  `:jurisdiction`-scoped sequence-number/store shape):

    \"AUTO\" -- automotive wiring-harness / cable-assembly product class
                (connects `cloud-itonami-isic-2910`/`-2920` body,
                powertrain and `cloud-itonami-isic-2720` battery
                terminals).
    \"ELEX\" -- fine-gauge internal electronics wiring / flex-cable
                product class (connects `cloud-itonami-isic-2630`
                smartphone battery, display and logic board).

  Coverage is reported HONESTLY: a product class not in this table has
  NO spec-basis. Seed values cite real, verifiable standards bodies and
  publications actually used in wire/cable/harness QA; this is a
  starting catalog, not a survey of every product class or every
  OEM's own supplement. Where this actor is not confident of a
  citation for a given product class/jurisdiction combination, it is
  left OUT of `catalog` and reported as missing by `coverage` --
  never fabricated.")

(def catalog
  {"AUTO" {:name "Automotive wiring harness / cable assembly"
           :owner-authority "IPC (Association Connecting Electronics Industries) / WHMA (Wire Harness Manufacturer's Association) joint committee; SAE International; ISO/TC 22 (Road vehicles)"
           :legal-basis "IPC/WHMA-A-620 (Requirements and Acceptance for Cable and Wire Harness Assemblies) / SAE J1128 (Low Tension Primary Cable) / ISO 6722 (Road vehicles -- 60 V and 600 V single-core cables) (reference; industry/standards-body specifications, not government statute)"
           :national-spec "IPC/WHMA-A-620 Class 1/2/3 acceptance criteria for crimped-terminal/wire-harness assembly workmanship, applied to the automotive wiring-harness product class"
           :provenance "https://www.ipc.org/ https://www.sae.org/standards/content/j1128_201704/ https://www.iso.org/standard/78037.html"
           :required-evidence ["IPC/WHMA-A-620 workmanship acceptance report (crimp/termination inspection)"
                               "SAE J1128 primary-cable conductor/insulation test report"
                               "ISO 6722 single-core cable voltage-class conformance report"
                               "end-of-line continuity/insulation-resistance chain-of-custody record"]}
   "ELEX" {:name "Fine-gauge internal electronics wiring / flex-cable assembly"
           :owner-authority "UL (Underwriters Laboratories) / IPC (Association Connecting Electronics Industries)"
           :legal-basis "UL 758 (Appliance Wiring Material) / IPC-A-610 (Acceptability of Electronic Assemblies) (reference; industry/standards-body specifications, not government statute)"
           :national-spec "UL 758 AWM conductor/insulation ratings applied to internal electronics wiring, IPC-A-610 fine-pitch flex-cable termination acceptance criteria"
           :provenance "https://www.ul.com/ https://www.ipc.org/"
           :required-evidence ["UL 758 appliance wiring material (AWM) test report"
                               "IPC-A-610 fine-pitch flex-cable termination acceptance report"
                               "end-of-line continuity/insulation-resistance chain-of-custody record"]}})

(defn spec-basis [product-class] (get catalog product-class))

(defn coverage
  ([] (coverage (keys catalog)))
  ([product-classes]
   (let [have (filter catalog product-classes)
         missing (remove catalog product-classes)]
     {:requested (count product-classes)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-2732 R0: " (count catalog)
                 " product classes seeded. Extend `harnessworks.facts/catalog`, "
                 "never fabricate a product class's requirements.")})))

(defn required-evidence-satisfied?
  [product-class submitted]
  (when-let [{:keys [required-evidence]} (spec-basis product-class)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [product-class]
  (:required-evidence (spec-basis product-class) []))
