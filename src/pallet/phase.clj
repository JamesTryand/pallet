(ns pallet.phase
  "# Pallet phase maps.

Phase maps provide a way of naming functions at runtime.  A phase map
is just a hash-map with keys that are keywords (the phases) and values
that are pallet plan functions.

Phase maps enable composition of operations across heterogenous nodes."
  (:require
   [taoensso.timbre :refer [debugf tracef]]
   [schema.core :as schema])
  (:import
   clojure.lang.IFn))

;;; # Phase specification functions

(def phase-with-args-schema
  [(schema/one schema/Keyword "phase-kw") schema/Any])

(def phase-schema
  (schema/either schema/Keyword IFn phase-with-args-schema))

(def PhaseSpec
  {:phase schema/Keyword
   (schema/optional-key :args) [schema/Any]})

(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

(defn phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

(defn phase-spec
  "Return a phase spec map for a phase call.  A phase call is either a
  phase keyword, or a sequence of phase keyword an arguments for the
  phase function."
  [phase]
  {:pre [(schema/validate phase-schema phase)]
   :post [(schema/validate PhaseSpec %)]}
  (let [args (phase-args phase)]
    (cond-> {:phase (phase-kw phase)}
      (seq args) (assoc :args args))))

;;; # Phase metadata
(defn phases-with-meta
  "Takes a `phases-map` and applies the default phase metadata and the
  `phases-meta` to the phases in it."
  [phases-map phases-meta default-phase-meta]
  (reduce-kv
   (fn [result k v]
     (let [dm (default-phase-meta k)
           pm (get phases-meta k)]
       (assoc result k (if (or dm pm)
                         ;; explicit overrides default
                         (vary-meta v #(merge dm % pm))
                         v))))
   nil
   (or phases-map {})))

;;; # Phase Specification
(defn process-phases
  "Process phases. Returns a phase list and a phase-map. Functions specified in
  `phases` are identified with a keyword and a map from keyword to function.
  The return vector contains a sequence of phase keywords and the map
  identifying the anonymous phases."
  [phases]
  (let [phases (if (or (keyword? phases) (fn? phases)) [phases] phases)]
    (reduce
     (fn [[phase-kws phase-map] phase]
       (if (or (keyword? phase)
               (and (or (vector? phase) (seq? phase)) (keyword? (first phase))))
         [(conj phase-kws phase) phase-map]
         (let [phase-kw (-> (gensym "phase")
                            name keyword)]
           [(conj phase-kws phase-kw)
            (assoc phase-map phase-kw phase)])))
     [[] {}] phases)))
