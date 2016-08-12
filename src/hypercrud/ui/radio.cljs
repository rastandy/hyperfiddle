(ns hypercrud.ui.radio
  (:require [hypercrud.client.core :as hc]
            [hypercrud.client.tx :as tx-util]
            [hypercrud.ui.form :as form]))


(defn radio-option [label name change! checked?]
  [:div.radio-group
   [:input {:type "radio"
            :name name
            :checked checked?
            :on-change change!}]
   [:label {:on-click change!} label]])


(defn radio* [graph forms {:keys [label-prop metatype form query]} value expanded-cur
              change! transact! tempid!]
  ; TODO only one radio-group on the page until we get a unique form-name
  (let [form-name (or metatype "TODO")
        option-eids (hc/select graph (hash query))
        change! (fn [eid]
                  (let [eid (if (= "create-new" eid) (tempid!) eid)]
                    ;reset the cursor before change! otherwise npe when trying to render
                    ;todo these both set the same cursor, and should be atomic
                    (reset! expanded-cur (if (= "create-new" eid) {} nil))
                    (change! [:db/retract value]
                             [:db/add eid])))
        create-new? (some-> value tx-util/tempid?)
        show-form? (or (not= nil @expanded-cur) create-new?)]
    [:div.editable-radio {:key (hash option-eids)}
     (map (fn [eid]
            (let [entity (hc/entity graph eid)
                  label (get entity label-prop)
                  checked? (= eid value)]
              ^{:key eid}
              [radio-option label form-name #(change! eid) checked?]))
          option-eids)
     (if form
       ^{:key :create-new}
       [radio-option "Create New" form-name #(change! "create-new") create-new?])
     ^{:key :blank}
     [radio-option "--" form-name #(change! nil) (= nil value)]
     (if show-form?
       ;; TODO branch the client in create-new case
       [form/form graph value metatype forms expanded-cur transact! tempid!])]

    ;todo how should editing existing entries work?
    #_[:div.editable-select {:key (hash option-eids)}
       (if (and form (not show-form?))
         [:button {:on-click #(swap! expanded-cur (constantly {}))
                   :disabled (= nil value)} "Edit"])
       ]))