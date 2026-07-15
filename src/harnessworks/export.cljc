(ns harnessworks.export
  "Audit-package export for social / regulatory hand-off.

  Produces plain EDN maps and CSV strings over a `harnessworks.store/
  Store` snapshot -- the same append-only ledger, cable-run-batch-
  shipment drafts and harness-certificate drafts the governor writes.
  Pure data transforms only: no I/O, no network, no signature. The
  manufacturer's own act is to sign and file the package; this
  namespace only materializes the package body.

  This is the honest delivery of the industry-stack `:export?` contract
  (robotics / audit-ledger capabilities) for ISIC 2732."
  (:require [clojure.string :as str]
            [harnessworks.store :as store]))

(defn- csv-escape [v]
  (let [s (str (if (nil? v) "" v))]
    (if (re-find #"[,\"\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- csv-row [cols]
  (str/join "," (map csv-escape cols)))

(defn ledger-rows
  "Normalize ledger facts into flat row maps suitable for CSV."
  [st]
  (mapv (fn [i f]
          {:seq i
           :t (:t f)
           :op (str (:op f))
           :actor (:actor f)
           :subject (:subject f)
           :disposition (str (:disposition f))
           :basis (pr-str (:basis f))
           :summary (:summary f)})
        (range)
        (store/ledger st)))

(defn shipment-rows [st]
  (mapv (fn [i r]
          {:seq i
           :record_id (get r "record_id")
           :kind (get r "kind")
           :batch_id (get r "batch_id")
           :jurisdiction (get r "jurisdiction")})
        (range)
        (store/shipment-history st)))

(defn certificate-rows [st]
  (mapv (fn [i r]
          {:seq i
           :record_id (get r "record_id")
           :kind (get r "kind")
           :batch_id (get r "batch_id")
           :jurisdiction (get r "jurisdiction")})
        (range)
        (store/certificate-history st)))

(defn batches-snapshot [st]
  (mapv (fn [b]
          (select-keys b [:id :batch-name :jurisdiction :status
                          :conductor-resistance-deviation-actual
                          :conductor-resistance-deviation-min
                          :conductor-resistance-deviation-max
                          :sim-peak-pull-force-n
                          :eol-defect-unresolved?
                          :batch-shipped?
                          :harness-certified?
                          :shipment-number
                          :certificate-number]))
        (store/all-batches st)))

(defn audit-package
  "Full audit package for a store snapshot -- the body a wire/cable/
  harness manufacturer would hand to OEM inspectors, market-regulator
  inspectors or internal compliance. `:format` is always `:edn-maps`
  for the nested package; use `package->csv-bundle` for CSV strings."
  [st]
  {:isic "2732"
   :business-id "cloud-itonami-isic-2732"
   :format :edn-maps
   :batches (batches-snapshot st)
   :ledger (vec (store/ledger st))
   :shipments (vec (store/shipment-history st))
   :harness-certificates (vec (store/certificate-history st))
   :counts {:batches (count (store/all-batches st))
            :ledger (count (store/ledger st))
            :shipments (count (store/shipment-history st))
            :harness-certificates (count (store/certificate-history st))}})

(defn rows->csv
  "Render a seq of flat maps as CSV using `header` column order."
  [header rows]
  (let [lines (into [(csv-row (map name header))]
                    (map (fn [r] (csv-row (map #(get r %) header))) rows))]
    (str (str/join "\n" lines) (when (seq lines) "\n"))))

(defn package->csv-bundle
  "CSV bundle for spreadsheet hand-off. Keys are filenames; values are
  CSV body strings."
  [st]
  {"batches.csv" (rows->csv [:id :batch-name :jurisdiction :status
                            :conductor-resistance-deviation-actual
                            :sim-peak-pull-force-n
                            :batch-shipped? :harness-certified?
                            :shipment-number :certificate-number]
                           (batches-snapshot st))
   "ledger.csv" (rows->csv [:seq :t :op :actor :subject :disposition :basis :summary]
                           (ledger-rows st))
   "shipments.csv" (rows->csv [:seq :record_id :kind :batch_id :jurisdiction]
                              (shipment-rows st))
   "harness-certificates.csv" (rows->csv [:seq :record_id :kind :batch_id :jurisdiction]
                                (certificate-rows st))})

#?(:clj
(defn write-csv-bundle!
  "Write `package->csv-bundle` files under `dir` (created if missing).
  Returns the absolute path of `dir`. JVM-only I/O seam for social
  hand-off scripts; pure package construction stays in `package->csv-bundle`."
  [st dir]
  (let [d (java.io.File. (str dir))
        _ (.mkdirs d)
        bundle (package->csv-bundle st)]
    (doseq [[name body] bundle]
      (spit (java.io.File. d (str name)) body))
    (.getAbsolutePath d))))
