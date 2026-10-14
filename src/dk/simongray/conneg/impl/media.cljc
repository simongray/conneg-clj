(ns ^:no-doc dk.simongray.conneg.impl.media
  "Media ranges from Accept (RFC 9110, section 12.5.1) and how they match
  media types.

  A media range is a map as whatwg-mime parses a media type, with its
  weight under :weight rather than as the parameter q."
  (:require [clojure.string :as str]
            [dk.simongray.conneg.impl.ranking :as ranking]
            [dk.simongray.conneg.impl.syntax :as syntax]
            [dk.simongray.whatwg-mime :as mime]))

;; RFC 9110, section 12.5.1, with the tokens of section 5.6.2, the quoted
;; strings of section 5.6.4 and the parameters of section 5.6.6, which may
;; be empty, e.g. text/html;
(def element-pattern
  "An element of Accept: a media type or range and its parameters, among
  them the weight."
  (let [token  "[!#$%&'*+\\-.^_`|~0-9A-Za-z]+"
        quoted "\"(?:[\\t \\x21\\x23-\\x5B\\x5D-\\x7E\\x80-\\xFF]|\\\\[\\t \\x21-\\x7E\\x80-\\xFF])*\""]
    (re-pattern (str token "/" token
                     "(?:[\\t ]*;[\\t ]*(?:"
                     token "=(?:" token "|" quoted "))?)*"))))

(defn element
  "The media range of the element `s` of an Accept value, or nil when it
  doesn't follow the grammar, e.g. when it's empty or a range of */html."
  [s]
  (when (re-matches element-pattern s)
    (let [{:keys [type subtype parameters] :as m} (mime/parse s)
          ;; RFC 9110 has a recipient take q as the weight wherever it is
          q          (get parameters "q")
          weight     (if q (syntax/weight q) 1.0)
          parameters (dissoc parameters "q")]
      (when (and weight (or (not= "*" type) (= "*" subtype)))
        (cond-> (assoc (dissoc m :parameters) :weight weight)
          (seq parameters) (assoc :parameters parameters))))))

(defn entry
  "The media range of `x`, the text of one or a map of one, or nil when it
  isn't one."
  [x]
  (cond
    (string? x) (element (str/trim x))
    (map? x)    (when (and (string? (:type x)) (string? (:subtype x)))
                  (-> (merge {:weight 1.0} x)
                      (update :type syntax/lower)
                      (update :subtype syntax/lower)))))

(defn media-ranges
  "The media ranges of `x`, an Accept value or a coll of ranges, in order
  of weight, without those that don't parse or repeat an earlier one."
  [x]
  (->> (if (string? x)
         (keep element (syntax/elements x))
         (keep entry x))
       (syntax/distinct-by #(dissoc % :weight))
       (sort-by :weight >)
       vec))

(def any-range
  "The media ranges of a client with no preference."
  [{:type "*" :subtype "*" :weight 1.0}])

;; RFC 9110, section 12.4.1
(defn preferences
  "The media ranges of `x` to match offers with: */* when it's nil or none
  of its elements parse, and none when it's an empty list."
  [x]
  (let [ranges (media-ranges x)]
    (if (and (empty? ranges) (not (syntax/empty-list? x)))
      any-range
      ranges)))

;; RFC 9110, section 8.3.2: charset names ignore case. Other values compare
;; exactly, as the meaning of a parameter decides (section 5.6.6).
(defn- same-value?
  "Whether the values `a` and `b` of the parameter `name` are the same."
  [name a b]
  (if (= "charset" name)
    (= (syntax/lower a) (syntax/lower b))
    (= a b)))

(defn matches?
  "Whether the media `range` matches the media type `m`: the type and
  subtype unless they're *, and each parameter of the range."
  [{:keys [type subtype parameters] :as range} m]
  (and (or (= "*" type) (= type (:type m)))
       (or (= "*" subtype) (= subtype (:subtype m)))
       (every? (fn [[k v]]
                 (some->> (get (:parameters m) k) (same-value? k v)))
               parameters)))

;; as in jshttp/negotiator
(defn specificity
  "How much of a media type the `range` names: 4 for the type, 2 for the
  subtype and 1 for parameters."
  [{:keys [type subtype parameters] :as range}]
  (+ (if (= "*" type) 0 4)
     (if (= "*" subtype) 0 2)
     (if (seq parameters) 1 0)))

;; RFC 9110, section 12.5.1: the most specific range that matches decides
(defn weigh
  "The match of the media type `m` in the `ranges`, decided by the most
  specific range that matches it, or nil when none does."
  [ranges m]
  (reduce (fn [best match]
            (if (or (nil? best) (> (:specificity match) (:specificity best)))
              match
              best))
          nil
          (keep-indexed (fn [i range]
                          (when (matches? range m)
                            {:weight      (:weight range)
                             :specificity (specificity range)
                             :index       i}))
                        ranges)))

(defn ranked
  "The items of `coll` that the Accept value `x` accepts, best first, by
  the media type that `f` returns for each."
  [x f coll]
  (let [ranges (preferences x)]
    (ranking/ranked (fn [item]
                      (let [s (f item)]
                        (some->> (when (string? s) (mime/parse s))
                                 (weigh ranges))))
                    coll)))

(comment
  (element "Text/HTML;Level=1;q=0.5")
  (media-ranges "text/*;q=0.3, text/plain;format=flowed")
  (weigh (media-ranges "text/*, text/html;q=0") (mime/parse "text/html"))
  (ranked "text/*, text/html;q=0" identity ["text/html" "text/turtle"])
  #_.)
