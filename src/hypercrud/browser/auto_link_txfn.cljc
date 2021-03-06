(ns hypercrud.browser.auto-link-txfn
  (:require [cats.monad.either :as either]
            [clojure.string :as string]
            [contrib.string :refer [memoized-safe-read-edn-string]]
            [contrib.template :as template]
            [taoensso.timbre :as timbre]))


(def auto-tx-fn-lookup
  {{:fe true :c? false :d? true :a false} nil
   {:fe true :c? false :d? true :a true} nil
   {:fe true :c? false :d? false :a false} nil
   {:fe true :c? false :d? false :a true} nil

   {:fe true :c? true :d? true :a false} nil
   {:fe true :c? true :d? true :a true} (-> (template/load-resource "auto-txfn/mt-fet-at.edn") string/trim)
   {:fe true :c? true :d? false :a false} nil
   {:fe true :c? true :d? false :a true} nil

   ; no fe = index or relation links
   {:fe false :c? false :d? true :a false} nil
   {:fe false :c? false :d? true :a true} nil
   {:fe false :c? false :d? false :a false} nil
   {:fe false :c? false :d? false :a true} nil

   {:fe false :c? true :d? true :a false} nil
   {:fe false :c? true :d? true :a true} nil
   {:fe false :c? true :d? false :a false} nil
   {:fe false :c? true :d? false :a true} nil})

(defn auto-txfn [link]
  ; tx-fn is not applicable if the link is not managed
  (if (or (:link/managed? link) false)
    (-> (memoized-safe-read-edn-string (str "[" (:link/path link) "]"))
        (either/branch
          (fn [e] (timbre/error e))
          (fn [path]
            (get auto-tx-fn-lookup
                 {:fe (not (nil? (first path)))
                  :c? (or (:link/create? link) false)
                  :d? (or (:link/dependent? link) false)
                  :a (not (nil? (second path)))}))))))
