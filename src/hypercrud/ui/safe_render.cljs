(ns hypercrud.ui.safe-render
  (:require [contrib.cljs-platform :refer [code-for-browser code-for-nodejs]]
            [cuerdas.core :as string]
            [reagent.core :as reagent]))


(code-for-nodejs
  (require '[reagent.dom.server :as reagent-server]))

(declare user-portal)

(code-for-nodejs
  (defn user-portal [with-error & children]
    [:div.hyperfiddle-userportal
     {:dangerouslySetInnerHTML
      {:__html
       (try
         ; todo just make a fragment and render it to string
         (->> children
              (map reagent-server/render-to-string)
              (string/join "\n"))
         (catch js/Error e
           (reagent-server/render-to-string [with-error e])))}}]))

(code-for-browser
  (defn user-portal [with-error & children]
    (let [show-error (atom false)
          e-state (reagent/atom nil)]
      (reagent/create-class
        {:reagent-render (fn [with-error & children]
                           (into [:div.hyperfiddle-userportal]
                                 (let [e @e-state]
                                   (if (and @show-error e)
                                     (do
                                       (reset! show-error false) ; only show the error once, retry after that
                                       [[with-error e]])
                                     children))))

         :component-did-catch (fn [#_this e info]           ; args will need updating in reagent0.8.x
                                (reset! show-error true)
                                (reset! e-state e))}))))

(defn portal-markup [control & args]
  [:div.hyperfiddle-userportal
   (into [control] args)])
