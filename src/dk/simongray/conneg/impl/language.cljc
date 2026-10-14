(ns ^:no-doc dk.simongray.conneg.impl.language
  "Language tags weighed by an Accept-Language value (RFC 9110, section
  12.5.4): by basic filtering (RFC 4647, section 3.3.1), with the fallback
  of lookup (section 3.4) for a tag that no range names, e.g. da for da-DK."
  (:require [clojure.string :as str]
            [dk.simongray.conneg.impl.ranking :as ranking]
            [dk.simongray.conneg.impl.syntax :as syntax]
            [dk.simongray.langtag :as langtag]))

(defn priority-list
  "The priority list of `x`, an Accept-Language value or a coll of ranges,
  with each range in lower case."
  [x]
  (mapv #(update % :range syntax/lower) (langtag/priority-list x)))

(def any-range
  "The priority list of a client with no preference."
  [{:range "*" :weight 1.0}])

;; RFC 9110, section 12.4.1
(defn preferences
  "The priority list of `x` to match tags with: * when it's nil or none of
  its elements parse, and none when it's an empty list."
  [x]
  (let [ranges (priority-list x)]
    (if (and (empty? ranges) (not (syntax/empty-list? x)))
      any-range
      ranges)))

(defn subtag-count
  "The number of subtags of the `range`, or 0 for *."
  [range]
  (if (= "*" range)
    0
    (count (str/split range #"-"))))

;; RFC 4647, section 3.4.
;; TODO: langtag/fallbacks, once it's public.
(defn falls-back?
  "Whether lookup falls back from the `range` to the shorter `tag`, e.g.
  from da-dk to da."
  [range tag]
  (and (not= range tag)
       (some? (langtag/lookup [range] [tag]))))

(defn closeness
  "How close the basic `range` is to the `tag` that it matches: 3 for the
  tag itself, 2 for its start and 0 for *."
  [range tag]
  (cond
    (= "*" range) 0
    (= range tag) 3
    :else         2))

(defn matches
  "The matches of the `tag` in the `priority-list`, both in lower case: by
  basic filtering, or by lookup from a range of weight above 0."
  [priority-list tag]
  (for [[i {:keys [range weight]}] (map-indexed vector priority-list)
        match (cond
                (langtag/basic-match? range tag)
                [{:subtags   (subtag-count range)
                  :closeness (closeness range tag)}]

                (and (pos? weight) (falls-back? range tag))
                [{:subtags   (subtag-count tag)
                  :closeness 1}])]
    (assoc match
      :weight weight
      :specificity (if (= "*" range) 0 1)
      :index i)))

;; RFC 2616, section 14.4: the longest range that matches decides, and *
;; only matches a tag that no other range does
(defn weigh
  "The match of the `tag` in the `priority-list`, both in lower case,
  decided by the longest range that matches it, or nil when none does. At
  the same length, a range that names the tag beats a fallback."
  [priority-list tag]
  (reduce (fn [best match]
            (if (or (nil? best)
                    (pos? (compare [(:subtags match) (:closeness match)]
                                   [(:subtags best) (:closeness best)])))
              match
              best))
          nil
          (matches priority-list tag)))

(defn ranked
  "The items of `coll` that the Accept-Language value `x` accepts, best
  first, by the tag that `f` returns for each."
  [x f coll]
  (let [priority-list (preferences x)]
    (ranking/ranked (fn [item]
                      (let [tag (f item)]
                        (when (string? tag)
                          (weigh priority-list (syntax/lower tag)))))
                    coll)))

(comment
  (priority-list "da-DK, en;q=0.8")
  (weigh (priority-list "da-DK, en;q=0.8") "da")
  (ranked "da-DK, en;q=0.8" identity ["en" "da"])
  #_.)
