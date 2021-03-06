(ns hyperfiddle.ide.fiddles.schema-attribute
  (:require [clojure.set :as set]
            [contrib.datomic-tx :as tx]
            [contrib.reactive :as r]
            [hypercrud.browser.context :as context]
            [hypercrud.ui.auto-control :refer [auto-control]]
            [hypercrud.ui.result :refer [result]]))


(def special-case-attrs #{:db/ident :db/cardinality :db/valueType})

(defn- has-required-attrs? [entity] (set/subset? special-case-attrs (set (keys entity))))

(defn- read-only [ctx]
  (not (or (has-required-attrs? @(:cell-data ctx))
           (#{:db/ident :db/doc :db/valueType :db/cardinality} (:hypercrud.browser/attribute ctx)))))

(defn- merge-in-tx [entity tx ctx]
  (reduce (fn [entity [op e a v]]
            ; todo this fn has bare minimum support for this page
            ; e.g. doesnt support card/many or nested modals
            (let [valueType @(r/cursor (:hypercrud.browser/schemas ctx) ["$" a :db/valueType :db/ident])
                  v (if (= :db.type/ref valueType)
                      {:db/id v}
                      v)]
              (case op
                :db/add (assoc entity a v)
                :db/retract (dissoc entity a))))
          (into {} entity)
          tx))

(defn- build-valueType-and-cardinality-renderer [special-attrs-state]
  (let [user-with! (fn [ctx user-with! tx]
                     (let [entity @(:cell-data ctx)
                           new-entity (merge-in-tx entity tx ctx)]
                       (case [(has-required-attrs? entity) (has-required-attrs? new-entity)]
                         [false false]
                         (swap! special-attrs-state tx/into-tx tx)

                         [false true]
                         (do
                           (user-with! (tx/into-tx @special-attrs-state tx))
                           (reset! special-attrs-state nil))

                         [true false]
                         ; todo this case WILL throw (going from a valid tx to invalid)
                         (user-with! tx)

                         [true true]
                         (user-with! tx))))]
    (fn [field props ctx]
      (let [ctx (update ctx :user-with! #(r/partial user-with! ctx %))]
        (auto-control field props nil ctx)))))

(defn- build-ident-renderer [special-attrs-state]
  (let [user-with! (fn [ctx user-with! tx]
                     (let [entity @(:cell-data ctx)
                           new-entity (merge-in-tx entity tx ctx)]
                       (case [(has-required-attrs? entity) (has-required-attrs? new-entity)]
                         [false false]
                         (user-with! tx)

                         [false true]
                         (do
                           (user-with! (tx/into-tx @special-attrs-state tx))
                           (reset! special-attrs-state nil))

                         [true false]
                         ; todo this case WILL throw (going from a valid tx to invalid)
                         (user-with! tx)

                         [true true]
                         (user-with! tx))))]
    (fn [field props ctx]
      (let [ctx (update ctx :user-with! #(r/partial user-with! ctx %))]
        (auto-control field props nil ctx)))))

(declare renderer)

(defn renderer [ctx]
  (let [special-attrs-state (r/atom nil)
        valueType-and-cardinality-renderer (build-valueType-and-cardinality-renderer special-attrs-state)
        ident-renderer (build-ident-renderer special-attrs-state)
        reactive-merge #(merge-in-tx % @special-attrs-state ctx)]
    (fn [ctx]
      (let [ctx (-> ctx
                    (dissoc :relation :relations)
                    (update :hypercrud.browser/result (partial r/fmap reactive-merge))
                    (context/with-relations)
                    (assoc :read-only read-only)
                    (assoc-in [:fields :db/cardinality :renderer] valueType-and-cardinality-renderer)
                    (assoc-in [:fields :db/valueType :renderer] valueType-and-cardinality-renderer)
                    (assoc-in [:fields :db/ident :renderer] ident-renderer))]
        ; Elide doc and ident
        [:div (result ctx)]))))
