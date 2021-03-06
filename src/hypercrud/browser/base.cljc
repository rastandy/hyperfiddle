(ns hypercrud.browser.base
  (:require [cats.core :as cats :refer [mlet return]]
            [cats.monad.either :as either]
            [contrib.reactive :as r]
            [contrib.string :refer [memoized-safe-read-edn-string]]
            [contrib.try :refer [try-either]]
            [hypercrud.browser.auto-link :refer [auto-links]]
            [hypercrud.browser.context :as context]
            [hypercrud.browser.find-element :as find-element]
            [hypercrud.browser.q-util :as q-util]
            [hypercrud.browser.routing :as routing]
            [hypercrud.browser.system-fiddle :as system-fiddle]
            [hypercrud.browser.user-bindings :as user-bindings]
            [hypercrud.client.core :as hc]
            [hypercrud.client.schema :as schema-util]
            [hypercrud.types.Entity :refer [#?(:cljs Entity)]]
            [hypercrud.types.EntityRequest :refer [->EntityRequest]]
            [hypercrud.types.QueryRequest :refer [->QueryRequest]]
            [hypercrud.types.ThinEntity :refer [#?(:cljs ThinEntity)]])
  #?(:clj
     (:import (hypercrud.types.Entity Entity)
              (hypercrud.types.ThinEntity ThinEntity))))


(def meta-pull-exp-for-link
  [:db/id
   :db/doc
   :fiddle/bindings
   :fiddle/css
   :fiddle/entrypoint
   :fiddle/ident
   {:fiddle/links [:db/id
                   :hypercrud/props
                   :link/create?
                   :link/dependent?
                   :link/disabled?
                   {:link/fiddle [:db/id
                                  :fiddle/ident             ; routing
                                  :fiddle/query             ; validation
                                  :fiddle/type              ; validation
                                  ]}
                   :link/formula
                   :link/ident
                   :link/managed?
                   :link/path
                   :link/rel
                   :link/render-inline?
                   :link/tx-fn]}
   :fiddle/markdown
   :fiddle/pull
   :fiddle/query
   :fiddle/renderer
   :fiddle/type])

(defn legacy-fiddle-ident->lookup-ref [fiddle]              ; SHould be an ident but sometimes is a long today
  ; Keywords are not a db/ident, turn it into the fiddle-id lookup ref.
  ; Otherwise, pass it through, its already a lookup-ref or eid or whatever.
  (if (keyword? fiddle)
    [:fiddle/ident fiddle]
    fiddle))

(defn meta-request-for-fiddle [ctx]
  (if (system-fiddle/system-fiddle? (get-in ctx [:route 0]))
    (either/right nil)
    (try-either
      (let [[fiddle] (get-in ctx [:route])
            _ (assert fiddle "missing fiddle-id")
            _ (assert (:hypercrud.browser/domain ctx) "missing domain")
            dbval (hc/db (:peer ctx) (get-in ctx [:hypercrud.browser/domain :domain/fiddle-repo]) (:branch ctx))]
        (->EntityRequest (legacy-fiddle-ident->lookup-ref fiddle) nil dbval meta-pull-exp-for-link)))))

(defn validate-fiddle [fiddle]
  (if-not (:db/id fiddle)
    (either/left (ex-info (str :hyperfiddle.error/fiddle-not-found)
                          {:ident :hyperfiddle.error/fiddle-not-found
                           :human "Fiddle not found (did you just edit :fiddle/ident?)"
                           :fiddle fiddle}))
    (either/right fiddle)))

(defn hydrate-fiddle [meta-fiddle-request ctx]
  (if (system-fiddle/system-fiddle? (get-in ctx [:route 0]))
    (system-fiddle/hydrate-system-fiddle (get-in ctx [:route 0]))
    (mlet [fiddle @(hc/hydrate (:peer ctx) (:branch ctx) @meta-fiddle-request)]
      (validate-fiddle fiddle))))

(defn- fix-param [param]
  (cond
    (instance? Entity param) (:db/id param)
    (instance? ThinEntity param) (:db/id param)
    :else param))

(defn validate-query-params [q args ctx]
  (mlet [query-holes (try-either (q-util/parse-holes q)) #_"normalizes for :in $"
         :let [;_ (timbre/debug params query-holes q)
               db-lookup (q-util/build-dbhole-lookup ctx)
               ; Add in named database params that aren't formula params
               [params' unused] (loop [acc []
                                       args args
                                       [x & xs] query-holes]
                                  (let [is-db (.startsWith x "$")
                                        next-arg (if is-db (get db-lookup x)
                                                           (fix-param (first args)))
                                        args (if is-db args (rest args))
                                        acc (conj acc next-arg)]
                                    (if xs
                                      (recur acc args xs)
                                      [acc args])))]]
    ;(assert (= 0 (count (filter nil? params')))) ; datomic will give a data source error
    ; validation. better to show the query and overlay the params or something?
    (cond (seq unused) (either/left {:message "unused param" :data {:query q :params params' :unused unused}})
          (not= (count params') (count query-holes)) (either/left {:message "missing params" :data {:query q :params params' :unused unused}})
          :else-valid (either/right params'))))

(defn request-for-fiddle [fiddle ctx]
  (case @(r/cursor fiddle [:fiddle/type])
    :query (mlet [q (memoized-safe-read-edn-string @(r/cursor fiddle [:fiddle/query]))
                  args (validate-query-params q (get-in ctx [:route 1]) ctx)]
             (return (->QueryRequest q args)))

    :entity
    (let [[_ [e #_"fat" a :as args]] (get-in ctx [:route])
          uri (try (let [dbname (.-dbname e)]               ;todo report this exception better
                     (get-in ctx [:hypercrud.browser/domain :domain/environment dbname]))
                   (catch #?(:clj Exception :cljs js/Error) e nil))
          pull-exp (or (-> (memoized-safe-read-edn-string @(r/cursor fiddle [:fiddle/pull]))
                           (either/branch (constantly nil) identity)
                           first)
                       ['*])]
      (if (or (nil? uri) (nil? (:db/id e)))
        (either/left {:message "malformed entity param" :data {:params args}})
        (either/right
          (->EntityRequest
            (:db/id e) a (hc/db (:peer ctx) uri (:branch ctx)) pull-exp))))

    :blank (either/right nil)

    (either/right nil)))

(let [nil-or-hydrate (fn [peer branch request]
                       (if-let [request @request]
                         @(hc/hydrate peer branch request)
                         (either/right nil)))]
  (defn process-results [fiddle request ctx]
    (mlet [reactive-schemas @(r/apply-inner-r (schema-util/hydrate-schema ctx))
           reactive-result @(r/apply-inner-r (r/track nil-or-hydrate (:peer ctx) (:branch ctx) request))
           :let [ctx (assoc ctx
                       :hypercrud.browser/fiddle fiddle     ; for :db/doc
                       :hypercrud.browser/request request
                       :hypercrud.browser/result reactive-result
                       ; For tx/entity->statements in userland.
                       :hypercrud.browser/schemas reactive-schemas)
                 ctx (context/with-relations ctx)]
           ctx (user-bindings/user-bindings ctx)
           reactive-fes @(r/apply-inner-r (r/track find-element/auto-find-elements ctx))
           :let [ctx (assoc ctx :hypercrud.browser/ordered-fes reactive-fes)
                 ctx (assoc ctx :hypercrud.browser/links (r/track auto-links ctx))]]
      (cats/return ctx))))

(defn data-from-route [route ctx]                           ; todo rename
  (let [ctx (-> (context/clean ctx)
                (context/route route))]
    (mlet [meta-fiddle-request @(r/apply-inner-r (r/track meta-request-for-fiddle ctx))
           fiddle @(r/apply-inner-r (r/track hydrate-fiddle meta-fiddle-request ctx))
           fiddle-request @(r/apply-inner-r (r/track request-for-fiddle fiddle ctx))]
      (process-results fiddle fiddle-request ctx))))

(defn from-link [link ctx with-route]
  (mlet [route (routing/build-route' link ctx)]
    ; entire context must be encoded in the route
    (with-route route ctx)))

(defn data-from-link [link ctx]                             ; todo rename
  (from-link link ctx data-from-route))
