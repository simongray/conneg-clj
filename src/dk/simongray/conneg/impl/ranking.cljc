(ns ^:no-doc dk.simongray.conneg.impl.ranking
  "How the offers that a field accepts rank against each other.

  A match is a map of the range that decides the weight of an offer:

  - :weight, the weight of that range
  - :specificity, how much of the offer the range names, e.g. 6 for
    text/html and 4 for text/*
  - :index, the place of the range in the client's priority list
  - :closeness, for language tags: 3 when the range is the tag itself, 2
    when it's the start of the tag, 1 when lookup falls back to the tag
    and 0 for *")

;; The order of jshttp/negotiator, where RFC 9110 leaves ties open, with
;; the closeness of a language tag before the order of the offers
(defn rank
  "The key that a `match` sorts by, best first."
  [{:keys [weight specificity index closeness]}]
  [(- weight) (- specificity) index (- (or closeness 0))])

(defn ranked
  "The items of `coll` whose match by `weigh` has a weight above 0, best
  first.

  The `weigh` function returns the match of an item, or nil when no range
  matches it. Items with the same rank stay in the order of `coll`."
  [weigh coll]
  (->> coll
       (keep (fn [x]
               (when-let [match (weigh x)]
                 (when (pos? (:weight match))
                   [(rank match) x]))))
       (sort-by first)
       (mapv second)))
