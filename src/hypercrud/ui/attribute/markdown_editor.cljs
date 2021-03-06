(ns hypercrud.ui.attribute.markdown-editor
  (:require [contrib.datomic-tx :as tx]
            [hypercrud.ui.control.code :as code]
            [hypercrud.ui.control.link-controls :as link-controls]))


(defn ^:export markdown-editor [field props ctx]
  (let [path [(:fe-pos ctx) (:hypercrud.browser/attribute ctx)]
        change! #((:user-with! ctx) (tx/update-entity-attr @(:cell-data ctx) @(:hypercrud.browser/fat-attribute ctx) %))]
    ;^{:key ident}
    [:div.value
     [:div.anchors (link-controls/anchors path true ctx)]
     (let [widget (case (:layout ctx) :block code/code-block*
                                      :inline-block code/code-inline-block*
                                      :table code/code-inline-block*)
           props (assoc props :mode "markdown" :lineWrapping true)]
       [widget props @(:value ctx) change!])                 ; backwards args - props last
     (link-controls/iframes path true ctx)]))
