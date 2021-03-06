(ns hyperfiddle.io.hydrate-route
  (:require [clojure.set :as set]
            [cuerdas.core :as str]
            [contrib.base-64-url-safe :as base-64-url-safe]
            [contrib.data :refer [map-keys]]
            [contrib.performance :as perf]
            [hypercrud.client.peer :as peer]
            [hypercrud.types.EntityRequest :refer [#?(:cljs EntityRequest)]]
            [hypercrud.types.QueryRequest :refer [#?(:cljs QueryRequest)]]
            [hyperfiddle.appval.state.reducers :as reducers] ; this import is immoral
            [hyperfiddle.io.http.core :refer [http-request!]]
            [hyperfiddle.runtime :as runtime]
            [promesa.core :as p]
            [taoensso.timbre :as timbre])
  #?(:clj
     (:import
       (hypercrud.types.EntityRequest EntityRequest)
       (hypercrud.types.QueryRequest QueryRequest))))


(defn validate-user-qs [qs]
  {:pre [qs]
   :post [(not-any? nil? %)
          (every? #(or (instance? EntityRequest %) (instance? QueryRequest %)) %)]}
  (remove nil? qs) #_"userland convenience")

(def hydrate-loop-limit 25)

(defn- hydrate-loop-impl [rt request-fn local-basis branch stage {:keys [tempid-lookups ptm] :as data-cache} total-loops & [loop-limit]]
  (if (> total-loops loop-limit)
    (p/rejected (ex-info "Request limit reached" {:total-loops total-loops :ptm-at-cutoff (keys ptm)}))
    (let [all-requests (perf/time (fn [get-total-time] (timbre/debug "Computing needed requests" "total time: " (get-total-time)))
                                  (->> (request-fn tempid-lookups ptm)
                                       (validate-user-qs)
                                       (into #{})))
          missing-requests (let [have-requests (set (keys ptm))]
                             (->> (set/difference all-requests have-requests)
                                  (into [])))]
      (if (empty? missing-requests)
        (p/resolved {:tempid-lookups tempid-lookups
                     :ptm (select-keys ptm all-requests)    ; prevent memory leak by returning exactly what is needed
                     :total-loops total-loops})
        (p/then (runtime/hydrate-requests rt local-basis stage missing-requests)
                (fn [{:keys [pulled-trees] :as resp}]
                  (let [new-ptm (zipmap missing-requests pulled-trees)
                        ptm (merge ptm new-ptm)
                        data-cache {:tempid-lookups (merge-with merge tempid-lookups (get-in resp [:tempid-lookups branch]))
                                    :ptm ptm}]
                    (hydrate-loop-impl rt request-fn local-basis branch stage data-cache (inc total-loops) loop-limit))))))))

(defn hydrate-loop [rt request-fn local-basis branch stage & [data-cache]]
  (let [hydrate-loop-id #?(:cljs (js/Math.random)
                           :clj  (Math/random))]
    (timbre/debug "Starting hydrate-loop" (str "[" hydrate-loop-id "]"))
    (-> (perf/time-promise (hydrate-loop-impl rt request-fn local-basis branch stage data-cache 0 hydrate-loop-limit)
                           (fn [err get-total-time]
                             (timbre/debug "Finished hydrate-loop" (str "[" hydrate-loop-id "]") "total time:" (get-total-time)))
                           (fn [success get-total-time]
                             (timbre/debug "Finished hydrate-loop" (str "[" hydrate-loop-id "]") "total time:" (get-total-time) "total loops:" (:total-loops success))))
        (p/then #(dissoc % :total-loops)))))

(defn request-fn-adapter [local-basis route stage ctx ->Runtime f]
  ; Hacks because the hydrate-loop doesn't write to the state atom.
  (fn [tempid-lookups ptm]
    (let [ptm (map-keys #(peer/partitioned-request route (::runtime/branch-aux ctx) local-basis stage %) ptm)
          ctx (update ctx :peer (fn [peer]
                                  (-> @(runtime/state peer)
                                      ; want to keep all user/ui and bootstrapping state, just use overwrite the partition state.
                                      (select-keys [:user-profile ::runtime/domain])
                                      (assoc-in [::runtime/partitions (:branch ctx)]
                                                {:route route
                                                 ::runtime/branch-aux (::runtime/branch-aux ctx)
                                                 :local-basis local-basis
                                                 :ptm ptm
                                                 :tempid-lookups tempid-lookups})
                                      (assoc :stage stage)
                                      (reducers/root-reducer nil)
                                      (->Runtime))))]
      (f ctx))))

(defn hydrate-route-rpc! [service-uri local-basis route branch branch-aux stage]
  ; matrix params instead of path params
  (-> (merge {:url (str/format "%(service-uri)shydrate-route/$local-basis/$encoded-route/$branch/$branch-aux"
                               {:service-uri service-uri
                                :local-basis (base-64-url-safe/encode (pr-str local-basis))
                                :encoded-route (base-64-url-safe/encode (pr-str route))
                                :branch (base-64-url-safe/encode (pr-str branch))
                                :branch-aux (base-64-url-safe/encode (pr-str branch-aux))})
              :accept :application/transit+json :as :auto}
             (if (empty? stage)
               {:method :get}                               ; Try to hit CDN
               {:method :post
                :form stage
                :content-type :application/transit+json}))
      (http-request!)
      (p/then :body)))
