(ns hypercrud.ui.auto-control
  (:require [cuerdas.core :as str]
            [hypercrud.ui.attribute.edn :as edn]
            [hypercrud.ui.attribute.instant :as instant]
            [hypercrud.ui.safe-render :refer [unify-portal-markup]]
            [hypercrud.ui.table-cell :as table-cell]
            [hypercrud.ui.user-attribute-renderer :refer [safe-eval-user-control-fn]]
            [hypercrud.ui.widget :as widget]
            [hypercrud.util.reactive :as reactive]))


(defn schema-control-form [ctx]
  (let [isComponent @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/isComponent])
        valueType @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/valueType :db/ident])
        cardinality @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/cardinality :db/ident])
        widget (cond
                 (and (= valueType :db.type/boolean) (= cardinality :db.cardinality/one)) widget/boolean
                 (and (= valueType :db.type/keyword) (= cardinality :db.cardinality/one)) widget/keyword
                 (and (= valueType :db.type/string) (= cardinality :db.cardinality/one)) widget/string
                 (and (= valueType :db.type/long) (= cardinality :db.cardinality/one)) widget/long
                 (and (= valueType :db.type/instant) (= cardinality :db.cardinality/one)) instant/instant
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/one) isComponent) widget/ref-component
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/one)) widget/ref
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/many) isComponent) widget/ref-many-table
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/many)) edn/edn-many
                 (nil? valueType) widget/text
                 :else edn/edn)]
    widget))

; Can be unified; inspect (:layout ctx)
(defn schema-control-table [ctx]
  (let [isComponent @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/isComponent])
        valueType @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/valueType :db/ident])
        cardinality @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:db/cardinality :db/ident])
        widget (cond
                 (and (= valueType :db.type/boolean) (= cardinality :db.cardinality/one)) widget/boolean
                 (and (= valueType :db.type/keyword) (= cardinality :db.cardinality/one)) widget/keyword
                 (and (= valueType :db.type/string) (= cardinality :db.cardinality/one)) widget/string
                 (and (= valueType :db.type/long) (= cardinality :db.cardinality/one)) widget/long
                 (and (= valueType :db.type/instant) (= cardinality :db.cardinality/one)) instant/instant
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/one) isComponent) table-cell/ref-one-component
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/one)) widget/ref
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/many) isComponent) table-cell/ref-many
                 (and (= valueType :db.type/ref) (= cardinality :db.cardinality/many)) edn/edn-many
                 (and (= cardinality :db.cardinality/many)) edn/edn-many
                 (and (= cardinality :db.cardinality/one)) edn/edn
                 (nil? valueType) widget/text
                 :else edn/edn)]
    widget))

(defn fiddle-field-control [ctx]                            ; TODO :renderer -> :control
  ;(timbre/info "using fiddle ctx/field renderer" (-> attr :db/ident str) user-str)
  (safe-eval-user-control-fn (get-in ctx [:fields (:hypercrud.browser/attribute ctx) :renderer])))

(defn attribute-control [ctx]
  ;(timbre/info "using attribute/renderer " (-> attr :db/ident str) user-str)
  (safe-eval-user-control-fn @(reactive/cursor (:hypercrud.browser/fat-attribute ctx) [:attribute/renderer])))

(defn auto-control' [ctx]
  ; todo binding renderers should be pathed for aggregates and values
  ;
  ; Old comment, what does this mean now: (I think it means nothing, field is dead)
  ; --What is the user-field allowed to change? The ctx. Can it change links or anchors? no.
  ;
  ; todo control can have access to repeating contextual values (color, owner, result, entity, value, etc) but field should NOT
  ; this leads to inconsistent location formulas between non-repeating links in tables vs forms
  ;
  ; Return value just needs a ctx.
  ; Dynamic logic is done; user can't further override it with the field-ctx

  ; This is not quite right; each stage wants to be able to wrap the stage before.
  ; So it's kind of backwards right now and user-controls have
  ; knowledge of this pipeline.

  (or (case @(:hypercrud.ui/display-mode ctx) :user (some-> (:control ctx) unify-portal-markup) :xray nil)
      (case @(:hypercrud.ui/display-mode ctx) :user (fiddle-field-control ctx) :xray nil)
      ;(case @(:display-mode ctx) :user (fiddle-control ctx) :xray nil)
      (attribute-control ctx)
      (some-> (case (:layout ctx) :block (schema-control-form ctx)
                                  :inline-block (schema-control-table ctx)
                                  :table (schema-control-table ctx))
              unify-portal-markup)))

; What even is this scar
; Not clear if auto-control needs props. For now this is compat as the next
; layer down of controls (aka widgets) take props.
; hypercrud/props is on links. I dont think there is even a way for users
; to pass props here. But, how do you pass through things to the native widget?
(defn control-props [ctx]
  ; why does this need the field - it needs the ident for readonly in "Edit Anchors"
  ; todo clean this interface up
  {:read-only ((get ctx :read-only) @(:hypercrud.browser/fat-attribute ctx) ctx)})

(defn auto-control [maybe-field props _ ctx]                ; compat
  [(some-> (case (:layout ctx) :block (schema-control-form ctx)
                               :inline-block (schema-control-table ctx)
                               :table (schema-control-table ctx)))
   maybe-field props ctx])

(comment
  ; Find a home for this:
  (def validators {"clojure" #(-> (safe-read-edn-string %) (either/right?))})

  (let [valid? ((get validators (:mode props) (constantly true)))
        class (str/join " " (list (if (:readOnly props) "read-only")
                                  (if (not valid?) "invalid")))])

  )
