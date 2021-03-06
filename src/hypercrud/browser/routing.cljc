(ns hypercrud.browser.routing
  (:require [bidi.bidi :as bidi]
            [cats.core :as cats :refer [mlet]]
            [cats.monad.either :as either]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [contrib.data :refer [xorxs]]
            [contrib.eval :as eval]
            [contrib.reader :refer [read-edn-string]]
            [contrib.try :refer [try-either]]
            [cuerdas.core :as string]
            [hypercrud.browser.dbname :as dbname]
            [hypercrud.browser.router :as router]
            [hypercrud.types.Entity :refer [#?(:cljs Entity)]]
            [hypercrud.types.ThinEntity :refer [->ThinEntity #?(:cljs ThinEntity)]]
            [hyperfiddle.runtime :as runtime])
  #?(:clj
     (:import (hypercrud.types.Entity Entity)
              (hypercrud.types.ThinEntity ThinEntity))))


(defn invalid-route? [[fiddle ?datomic-args ?initial-state :as route]]
  (if-let [msg (cond
                 (map? route) "legacy format"
                 (nil? fiddle) "missing fiddle"
                 (not (keyword? fiddle)) "fiddle must be a keyword")]
    (ex-info (str "Invalid route: " msg)
             {:hyperfiddle.io/http-status-code 400 :route route})))

(defn invert-route [domain [fiddle args] invert-id]
  (let [args (->> {:request-params args}                    ; code compat
                  (walk/postwalk (fn [v]                    ; works on [args] instead of (:request-param args) ?
                                   (cond
                                     (instance? Entity v) (assert false "hyperfiddle/hyperfiddle.net#150")

                                     (instance? ThinEntity v)
                                     (let [uri (get-in domain [:domain/environment (.-dbname v)])
                                           id (invert-id (.-id v) uri)]
                                       (->ThinEntity (.-dbname v) id))

                                     :else v))))]
    [fiddle (:request-params args)]))

(defn ctx->id-lookup [uri ctx]
  ; todo what about if the tempid is on a higher branch in the uri?
  @(runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :tempid-lookups uri]))

(defn id->tempid [route ctx]
  (let [invert-id (fn [id uri]
                    (let [id->tempid (ctx->id-lookup uri ctx)]
                      (get id->tempid id id)))]
    (invert-route (:hypercrud.browser/domain ctx) route invert-id)))

(defn tempid->id [route ctx]
  (let [invert-id (fn [temp-id uri]
                    (let [tempid->id (-> (ctx->id-lookup uri ctx)
                                         (set/map-invert))]
                      (get tempid->id temp-id temp-id)))]
    (invert-route (:hypercrud.browser/domain ctx) route invert-id)))

(defn normalize-args [porps]
  {:pre [(not (:entity porps)) #_"legacy"
         ; There is some weird shit hitting this assert, like {:db/id nil}
         #_(not (map? porps)) #_"legacy"]
   :post [(vector? %) #_"route args are associative by position"]}

  ; careful here -
  ; (seq [1]) - truthy
  ; (seq? [1]) - false
  ; (seq 1) - IllegalArgumentException
  (cond (instance? ThinEntity porps) [porps]                ; entity is also a map so do this first
        :else (vec (xorxs porps))))

(let [memoized-eval-string (memoize eval/safe-eval-string)]
  (defn ^:export build-route' [link ctx]
    (mlet [fiddle-id (if-let [page (:link/fiddle link)]
                       (either/right (:fiddle/ident page))
                       (either/left {:message "link has no fiddle" :data {:link link}}))
           formula (let [formula-str (:link/formula link)]
                     (if (and (string? formula-str) (not (string/blank? formula-str)))
                       (memoized-eval-string formula-str)
                       (either/right (constantly nil))))
           args (try-either (formula ctx))
           :let [args (->> {:remove-this-wrapper args}      ; walk trees wants a map
                           ; shadow-links can be hdyrated here, and we need to talk them.
                           ; Technical debt. Once shadow-links are identities, this is just a mapv.
                           (walk/postwalk (fn [v]
                                            (if (instance? Entity v)
                                              (let [dbname (some-> v .-uri (dbname/uri->dbname ctx))]
                                                (->ThinEntity dbname (or (:db/ident v) (:db/id v))))
                                              v)))
                           (into {}))]]
      ;_ (timbre/debug args (-> (:link/formula link) meta :str))
      (cats/return
        (id->tempid [fiddle-id (normalize-args (:remove-this-wrapper args))] ctx)))))

(defn compare-routes [a b]
  ; return true if = ignoring fragment
  (= (apply router/canonicalize (take 3 a))
     (apply router/canonicalize (take 3 b))))

(def encode router/encode)
(def decode router/decode)

(defn decode' [route]
  (if route
    (try-either (decode route))
    (either/right nil)))


; Bidi routing protocols
(def regex-keyword "^:[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*(?:%2F[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*)?")
(def regex-string "^[A-Za-z0-9\\-\\_\\.]+")
(def regex-long "^[0-9]+")

(extend-type ThinEntity

  ; must be safe to user input. if they throw, the app won't fallback to a system-route

  bidi/PatternSegment
  (segment-regex-group [_]
    ; the value mathign the placeholder can be a keyword or a long
    (str "(?:" regex-long ")|(?:" regex-keyword ")"))
  (param-key [this]
    ; Abusing .-id to store the param-key in the entity placeholder in the route
    (.-id this))
  (transform-param [this]
    (fn [v]
      (let [$ (.-dbname this)                               ; the "$" is provided by entity placeholder in the route
            e (read-edn-string v)]                          ; the reader will need to subs ! to /
        (->ThinEntity $ e))))
  (matches? [this s]
    (let [r (re-pattern
              "[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*(?:%2F[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*)?")]
      (re-matches r s)))
  (unmatch-segment [this params]
    (let [entity (get params (.-id this))]
      ; lookup refs not implemented, but eid and ident work
      ; safe
      (some-> entity .-id)))

  bidi/Pattern
  (match-pattern [this env]
    ; is this even in play? I don't think I ever hit this bp
    (let [read (read-edn-string (:remainder env))]
      (-> env
          (update-in [:route-params] assoc (.-id this) (->ThinEntity (.-dbname this) read))
          ; totally not legit to count read bc whitespace
          (assoc :remainder (subs (:remainder env) 0 (count (pr-str read)))))))
  (unmatch-pattern [this m]
    (let [param-key (.-id this)]
      (-> m :params (get param-key) .-id pr-str)))

  ;bidi/Matched
  ;(resolve-handler [this m] (bidi/succeed this m))
  ;(unresolve-handler [this m] (when (= this (:handler m)) ""))
  )


; Bidi is not a great fit for the sys router because there is only one route with varargs params
; params are dynamically typed; need to model an `any`, eager-consume the path segment,
; and figure out the edn from the path segment. How to determine #entity vs scalar?
;(def sys-router
;  [["/" :fiddle-id "/" #edn 0] :browse])