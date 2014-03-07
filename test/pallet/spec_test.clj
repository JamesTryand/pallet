(ns pallet.spec-test
  (:require
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.node :as node]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :refer :all]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.user :as user]
   [schema.core :as schema :refer [validate]]))


(deftest server-spec-test
  (is (server-spec {}))
  (is (server-spec {:phases {:x (plan-fn [_]) :y (plan-fn [_])}}))
  (let [spec (server-spec {:phases {:x (plan-fn [_])}
                           :phases-meta {:x {:m 1}}})]
    (is (= {:m 1} (meta (-> spec :phases :x)))))
  (let [f (fn [] :f)]
    (is (= {:phases {:a f} :default-phases [:configure]}
           (server-spec {:phases {:a f}})))
    (testing "phases-meta"
      (let [spec (server-spec {:phases {:a f}
                               :phases-meta {:a {:phase-execution-f f}}})]
        (is (= :f ((-> spec :phases :a))))
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "phases-meta extension"
      (let [spec1 (server-spec {:phases {:a f}
                                :phases-meta {:a {:phase-execution-f f}}})
            spec2 (server-spec {:phases {:a #()}})
            spec (server-spec {:extends [spec1 spec2]})]
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "default phases-meta"
      (let [spec (server-spec {:phases {:bootstrap f}})]
        (is (= (:bootstrap default-phase-meta)
               (-> spec :phases :bootstrap meta)))))
    (is (= {:phases {:a f} :default-phases [:configure]}
           (server-spec {:extends (server-spec {:phases {:a f}})}))
        "extends a server-spec"))
  (testing ":roles"
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles :r1})) "Allow roles as keyword")
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles [:r1]})) "Allow roles as sequence")
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles #{:r1}})) "Allow roles as et"))
  (testing "type"
    (is (= :pallet.spec/server-spec (type (server-spec {:roles :r1}))))))

(deftest node-targets-test
  (testing "two nodes with different os and same ssh-port"
    (let [n1 {:id "n1" :os-family :ubuntu :ssh-port 22}
          n2 {:id "n2" :os-family :centos :ssh-port 22}]
      (testing "two specs with different roles"
        (let [s1 (server-spec {:roles :r1})
              s2 (server-spec {:roles :r2})]
          (is (= [(assoc (server-spec {:extends [s1 s2]}) :node n1)
                  (assoc (server-spec {:extends [s2]}) :node n2)]
                 (node-targets
                  [[(fn [n] (= :ubuntu (node/os-family n))) s1]
                   [(fn [n] (= 22 (node/ssh-port n))) s2]]
                  [n1 n2]))
              "Targets have correct roles"))))))