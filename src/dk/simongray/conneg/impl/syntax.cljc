(ns ^:no-doc dk.simongray.conneg.impl.syntax
  "The syntax that the Accept fields share in RFC 9110: lists, quoted
  strings and weights."
  (:require [clojure.string :as str]))

;; tokens and language ranges are ASCII, so this is their lower case in any
;; locale, unlike str/lower-case on the JVM
(defn lower
  "The text `s` in lower case, whatever the locale."
  [s]
  #?(:clj  (.toLowerCase ^String s java.util.Locale/ROOT)
     :cljs (.toLowerCase s)))

;; RFC 9110, section 5.6.1, with the quoted strings of section 5.6.4, where
;; a backslash escapes the character after it.
;; TODO: whatwg-mime's split-values, once it's public.
(defn elements
  "The elements of the list in the field value `s`, split at the commas
  outside quoted strings and trimmed. An empty element stays, as \"\"."
  [s]
  (let [n (count s)]
    (loop [i       0
           start   0
           quoted? false
           acc     []]
      (if (== i n)
        (conj acc (str/trim (subs s start)))
        (let [c (nth s i)]
          (cond
            (and quoted? (= \\ c))   (recur (min n (+ i 2)) start true acc)
            (= \" c)                 (recur (inc i) start (not quoted?) acc)
            (or quoted? (not= \, c)) (recur (inc i) start quoted? acc)

            :else
            (recur (inc i) (inc i) false
                   (conj acc (str/trim (subs s start i))))))))))

(defn distinct-by
  "The `xs`, keeping only the first of each key that `f` returns."
  [f xs]
  (first (reduce (fn [[kept seen] x]
                   (let [k (f x)]
                     (if (contains? seen k)
                       [kept seen]
                       [(conj kept x) (conj seen k)])))
                 [[] #{}]
                 xs)))

(defn empty-list?
  "Whether `x`, a field value or a coll of its elements, is a list with no
  elements, e.g. \"\" or \" , \"."
  [x]
  (if (string? x)
    (every? str/blank? (elements x))
    (and (coll? x) (empty? x))))

;; RFC 9110, section 12.4.2
(def qvalue-pattern
  "A qvalue: from 0 to 1 with at most three decimals."
  #"0(?:\.[0-9]{0,3})?|1(?:\.0{0,3})?")

(defn weight
  "The weight of the qvalue `s`, or nil when it doesn't follow the grammar."
  [s]
  (when (re-matches qvalue-pattern s)
    (parse-double s)))

(comment
  (elements "text/html;x=\"a,b\", */*;q=0.1")
  (empty-list? " , ")
  (weight "0.5")
  #_.)
