(ns contrib.char-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             #?(:clj :refer :cljs :refer-macros) [deftest is]]
            [contrib.char :refer [char-code dec->hex hex->dec char->hex-str hex-str->char]]))


(deftest char-code-1
         []
         (is (= (map char-code "abcd|")
                '(97 98 99 100 124))))

(deftest hex-1
         []
         (is (= (char->hex-str \space) "20"))
         (is (= (char->hex-str \newline) "0a"))
         (is (= ((comp hex-str->char char->hex-str) \space) \space))
         (is (= ((comp hex-str->char char->hex-str) \newline) \newline))
         (is (= ((comp hex-str->char char->hex-str) \a) \a)))