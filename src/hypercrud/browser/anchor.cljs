(ns hypercrud.browser.anchor
  (:require [cats.core :as cats :refer [mlet return]]
            [cats.monad.either :as either :refer-macros [try-either]]
            [clojure.set :as set]
            [hypercrud.browser.context :as context]
            [hypercrud.browser.q-util :as q-util]
            [hypercrud.browser.routing :as routing]
            [hypercrud.compile.eval :as eval :refer [eval-str']]
            [hypercrud.state.actions.core :as actions]
            [hypercrud.types.Entity :refer [Entity]]
            [hypercrud.types.ThinEntity :refer [->ThinEntity]]
            [hypercrud.util.core :refer [pprint-str]]
            [promesa.core :as p]
            [reagent.core :as reagent]))


(defn option-anchor? [anchor]
  ; don't care if its inline or not, just do the right thing.
  (= :options (:anchor/ident anchor)))

(defn popover-anchor? [anchor]
  (:anchor/managed? anchor))

(defn build-anchor-route' [anchor ctx]
  (js/console.warn "Warning: hypercrud.browser.anchor/build-anchor-route' is deprecated for hypercrud.browser.routing/build-route' and will be removed in the future.")
  (hypercrud.browser.routing/build-route' anchor ctx))

; todo belongs in routing ns
; this is same business logic as base/request-for-link
; this is currently making assumptions on dbholes
(defn validated-route' [link route]
  ; We specifically hydrate this deep just so we can validate anchors like this.
  (let [have (set (keys (into {} (remove (comp nil? val) (or (:request-params route) (:query-params route))))))]
    (case (:request/type link)
      :query (let [q (-> (q-util/safe-parse-query-validated link)
                         (cats/mplus (either/right []))
                         (cats/extract))
                   ; todo check fe conn
                   ; todo merge in dbhole lookup, see: hypercrud.browser.base/request-for-link
                   ; todo parse-param-holes can throw
                   need (set (q-util/parse-param-holes q))
                   missing (set/difference need have)]
               (if (empty? missing)
                 (either/right route)
                 (either/left {:message "missing query params" :data {:have have :missing missing}})))
      :entity (if (not= nil (-> (or (:request-params route) (:query-params route)) :entity)) ; add logic for a
                ; todo check fe conn
                (either/right route)
                (either/left {:message "missing query params" :data {:x route :have have :missing #{:entity}}}))
      :blank (either/right route)
      (either/left {:message "route has no link" :data {:route route}}))))

(defn get-or-apply' [expr & args]
  (if (fn? expr)
    (try-either (apply expr args))
    (either/right expr)))

(defn build-anchor-props-raw [unvalidated-route' anchor ctx] ; ctx is for display-mode

  ; this is a fine place to eval, put error message in the tooltip prop
  ; each prop might have special rules about his default, for example :visible is default true, does this get handled here?

  (let [link (:anchor/link anchor)                          ; can be nil - in which case route is invalid
        route (-> unvalidated-route' (cats/mplus (either/right nil)) (cats/extract))
        validated-route' (validated-route' link route)
        user-props' (if-let [user-code-str (eval/validate-user-code-str (:hypercrud/props anchor))]
                      (mlet [user-expr (eval-str' user-code-str)]
                        (get-or-apply' user-expr ctx))
                      (either/right nil))
        user-props-map-raw (cats/extract (cats/mplus user-props' (either/right nil)))
        user-prop-val's (map #(get-or-apply' % ctx) (vals user-props-map-raw))
        user-prop-vals (map #(cats/extract (cats/mplus % (either/right nil))) user-prop-val's)
        errors (->> (concat [user-props' unvalidated-route' validated-route'] user-prop-val's)
                    (filter either/left?) (map cats/extract) (into #{}))
        user-props (zipmap (keys user-props-map-raw) user-prop-vals)]
    (merge
      user-props                                            ; e.g. disabled, tooltip, style, class - anything, it gets passed to a renderer maybe user renderer
      ; doesn't handle tx-fn - meant for the self-link. Weird and prob bad.
      {:route (-> unvalidated-route' (cats/mplus (either/right nil)) (cats/extract))
       :tooltip (if-not (empty? errors)
                  [:warning (pprint-str errors)]
                  (case @(:display-mode ctx)
                    :xray [nil (pr-str (dissoc route :code-database :link-id))]
                    :user (:tooltip user-props)))
       :class (->> [(:class user-props)
                    (if-not (empty? errors) "invalid")]
                   (remove nil?)
                   (interpose " ")
                   (apply str))})))

(defn stage! [anchor route ctx]
  (let [user-txfn (some-> (eval/validate-user-code-str (:anchor/tx-fn anchor)) eval-str' (cats/mplus (either/right nil)) (cats/extract))
        user-txfn (or user-txfn (constantly nil))]
    (-> (p/promise
          (fn [resolve! reject!]
            (let [swap-fn (fn [multi-color-tx]
                            ; todo why does the user-txfn have access to the parent link's context
                            (let [result (let [result (user-txfn ctx multi-color-tx route)]
                                           ; txfn may be sync or async
                                           (if-not (p/promise? result) (p/resolved result) result))]
                              ; let the caller of this :stage fn know the result
                              ; This is super funky, a swap-fn should not be effecting, but seems like it would work.
                              (p/branch result
                                        (fn [v] (resolve! nil))
                                        (fn [e]
                                          (reject! e)
                                          (js/console.warn e)))

                              ; return the result to the action, it could be a promise
                              result))]
              ((:dispatch! ctx) (actions/stage-popover (:branch ctx) swap-fn)))))
        ; todo something better with these exceptions (could be user error)
        (p/catch #(-> % pprint-str js/alert)))))

(defn cancel! [ctx]
  ((:dispatch! ctx) (actions/cancel-popover (:branch ctx))))

(defn managed-popover-body [anchor route ctx]
  (let [stage! (reagent/partial stage! anchor route ctx)
        cancel! (reagent/partial cancel! ctx)
        ; NOTE: this ctx logic and structure is the same as the popover branch of browser-request/recurse-request
        ctx (-> ctx
                (context/clean)
                (update :debug #(str % ">popover-link[" (:db/id anchor) ":" (or (:anchor/ident anchor) (:anchor/prompt anchor)) "]")))]
    [:div.managed-popover
     [hypercrud.browser.core/ui-from-route route ctx]       ; cycle
     [:button {:on-click stage!} "stage"]
     [:button {:on-click cancel!} "cancel"]]))

(defn visible? [anchor ctx]
  (-> (if-let [code-str (eval/validate-user-code-str (:anchor/visible? anchor))] ; also inline links !
        (mlet [user-fn (eval-str' code-str)]
          (try-either (user-fn ctx)))
        (either/right true))
      (cats/mplus (either/right true))
      (cats/extract)))

(defn open! [ctx]
  ((:dispatch! ctx) (actions/open-popover (:branch ctx))))

; if this is driven by anchor, and not route, it needs memoized.
; the route is a fn of the formulas and the formulas can have effects
; which have to be run only once.
(defn build-anchor-props [anchor ctx]
  ; Draw as much as possible even in the presence of errors, still draw the link, collect all errors in a tooltip.
  ; Error states:
  ; - no route
  ; - invalid route
  ; - broken user formula
  ; - broken user txfn
  ; - broken user visible fn
  ; If these fns are ommitted (nil), its not an error.
  (let [visible? (visible? anchor ctx)
        route' (routing/build-route' anchor ctx)
        hypercrud-props (build-anchor-props-raw route' anchor ctx)
        popover-props (if (popover-anchor? anchor)
                        (if-let [route (and (:anchor/managed? anchor) (either/right? route') (cats/extract route'))]
                          ; If no route, there's nothing to draw, and the anchor tooltip shows the error.
                          (let [ctx (context/anchor-branch ctx anchor)]
                            {:showing? (reagent/cursor (-> ctx :peer .-state-atom) [:popovers (:branch ctx)])
                             :body [managed-popover-body anchor route ctx]
                             :open! (reagent/partial open! ctx)})))
        anchor-props-hidden {:hidden (not visible?)}]
    (merge anchor-props-hidden hypercrud-props {:popover popover-props})))
