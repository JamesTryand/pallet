(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [clojure.string :as string]
   [taoensso.timbre :as logging]
   [com.palletops.log-config.timbre :refer [with-context]]
   [pallet.core.executor.echo :refer [echo-executor]]
   [pallet.core.executor.plan :refer [plan plan-executor]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.environment :as environment]
   [pallet.group :refer [group-spec]]
   [pallet.kb :refer [packager-for-os]]
   [pallet.node :refer [script-template]]
   [pallet.phase :as phase]
   [pallet.plan :refer [execute-plan plan-fn]]
   [pallet.script :as script :refer [with-script-context]]
   [pallet.session
    :refer [plan-state target target-session? validate-target-session]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.test-utils :as test-utils :refer [remove-source-line-comments]]
   [pallet.user :refer [*admin-user*]]
   [pallet.utils :as utils]))

(defn- trim-if-string [s]
  (when s (string/trim s)))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session f]
  (let [phase (:phase session)
        session (dissoc session :phase)
        _ (validate-target-session session)
        target (target session)
        plan-fn f]
    (logging/debugf "produce-phases %s" session)
    (assert phase)
    (with-script-context (script-template target)
      (with-script-language :pallet.stevedore.bash/bash
        (let [session (dissoc session :target)
              {:keys [action-results] :as result-map}
              (execute-plan session target plan-fn)]
          (logging/debugf "build-actions result-map %s" result-map)
          result-map)))))

(defn build-session
  "Takes the session map, and tries to add the most keys possible.
   The default session is
       {:target {:override {:packager :aptitude :os-family :ubuntu}}
        :phase :configure}"
  [session]
  {:post [(validate-target-session (dissoc % :phase))]}
  (let [session (or session {})
        ;; session (update-in session [:target]
        ;;                    #(or
        ;;                      %
        ;;                      (group-spec
        ;;                          (or
        ;;                           ;; (when-let [node (-> session :target)]
        ;;                           ;; (node/group-name node))
        ;;                           :id)
        ;;                        {})))
        session (update-in
                 session [:target]
                 #(or
                   %
                   (test-utils/make-node
                    (or (-> session :target :group-name) "testnode")
                    {:os-family (or (-> session :target :override :os-family)
                                    :ubuntu)
                     :os-version (or (-> session :target :override :os-version)
                                     "10.04")
                     :packager (or (-> session :target :override :packager)
                                   (packager-for-os
                                    (or (-> session
                                            :target :override :os-family)
                                        :ubuntu)
                                    nil))
                     ;; :id (or (-> session :target :node) :id)
                     :is-64bit (get-in session
                                       [:target :override :is-64bit] true)})))
        session (update-in session [:target :os-family]
                           #(or % :ubuntu))
        session (update-in session [:target :id]
                           #(or % "id"))

        ;; session (update-in session [:server] merge (:group session))
        ;; session (update-in session [:service-state]
        ;            #(or % [(:target session)]))
        session (update-in session [:execution-state :action-options]
                           #(merge {:script-comments nil} %))
        session (update-in session [:execution-state :executor]
                           #(or % (echo-executor)))
        session (update-in session [:phase] #(or % :test-phase))
        session (update-in session [:plan-state]
                           #(or % (in-memory-plan-state)))
        session (update-in session [:execution-state :user]
                           #(or % *admin-user*))]
    (logging/tracef "session %s" session)
    (assoc session :type :pallet.session/session)))

(defn target-session
  "Return a target-session for the map m"
  [m]
  {:post [(validate-target-session %)
          (target-session? %)]}
  (dissoc (build-session m) :phase))

(defn build-actions*
  "Implementation for build-actions."
  [f session]
  (let [session (build-session session)
        f (if-let [phase-context (:phase-context session)]
            (fn []
              (with-context {:plan phase-context}
                (f)))
            f)]
    (produce-phases session f)))

(defn join-script
  [{:keys [action-results]}]
  (str
   (string/join "\n" (map (comp trim-if-string :script) action-results))
   \newline))

(defmacro build-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     ((juxt join-script identity)
      (build-actions* (plan-fn [~session-sym] ~@body) session#))))

(defmacro build-script
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (join-script (build-actions* (plan-fn [~session-sym] ~@body) session#))))

(defn plan-result
  [result]
  (let [result
        (-> result
            (update-in [:options]
                       (fn [{:keys [script-comments user] :as options}]
                         (let [options (if (= user *admin-user*)
                                         (dissoc options :user)
                                         options)
                               options (if (nil? script-comments)
                                         (dissoc options :script-comments)
                                         options)]
                           options))))
        result (if (empty? (:options result))
                 (dissoc result :options)
                 result)]
    result))

(defn plan-results
  [executor]
  (->> (plan executor)
       (map plan-result)))

(defmacro build-plan
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [[session-sym session] & body]
  `(let [executor# (plan-executor)
         session# (assoc-in (or ~session {})
                            [:execution-state :executor] executor#)]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (plan-fn [~session-sym] ~@body) session#)
     (plan-results executor#)))

(defmacro let-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  {:indent 1}
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (plan-fn [~session-sym] ~@body) session#)))

(def ubuntu-session
  (build-session {:target {:override {:os-family :ubuntu}}}))
(def centos-session
  (build-session {:target {:override {:os-family :centos}}}))

(defn action-phase-errors
  [result]
  (filter :error (:result result)))
