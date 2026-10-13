(ns dk.simongray.conneg
  "Proactive content negotiation by RFC 9110, section 12: the media type
  and the language of a response, picked from the offers of a server by
  the Accept and Accept-Language fields of a request.

  The functions that pick take a field value and the offers in the
  server's order of preference. A nil field value means that the client
  has no preference, so the first offer wins. They return an offer as
  given, or nil when none is acceptable. With a function that returns the
  media type or language tag of an item, they pick items instead."
  (:require [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]))

;; RFC 9110, section 12.5.1
(defn media-ranges
  "The media ranges of `x`, an Accept value or a coll of ranges, in order
  of weight.

      (media-ranges \"text/*;q=0.3, text/plain;format=flowed\")
      ;; => [{:type \"text\" :subtype \"plain\" :parameters {\"format\" \"flowed\"}
      ;;      :weight 1.0}
      ;;     {:type \"text\" :subtype \"*\" :weight 0.3}]

  A range is a map as whatwg-mime parses a media type, with its weight
  under :weight. Ranges that don't follow the grammar, e.g. with q=abc,
  or that repeat an earlier one are left out."
  [x]
  (media/media-ranges x))

;; RFC 9110, section 12.5.1
(defn media-type
  "The offer in `offers` that best matches `accept`, or nil when none is
  acceptable. With `f`, it picks an item of `coll` by its media type.

      (media-type \"application/json;q=0.2, text/*;q=0.9\"
                  [\"text/html\" \"application/json\" \"text/turtle\"])
      ;; => \"text/html\"

  The `accept` is an Accept value or a coll of media ranges. An offer
  takes the weight of the most specific range that matches it, and an
  offer of weight 0 is never acceptable. At the same weight, the offer of
  the more specific range wins, e.g. text/html over text/* for
  \"text/*, text/html\", then the range that the client lists first, then
  the offer that comes first.

  A range with parameters only matches an offer with the same values of
  them, while a range without parameters matches any offer of its type.
  An Accept value that doesn't parse counts as no preference, but an
  empty one accepts nothing."
  ([accept offers]
   (media-type accept identity offers))
  ([accept f coll]
   (first (media/ranked accept f coll))))

;; RFC 9110, section 12.5.4
(defn language
  "The offer in `offers` that best matches `accept-language`, or nil when
  none is acceptable. With `f`, it picks an item of `coll` by its language
  tag.

      (language \"da-DK, en;q=0.8\" [\"da\" \"en\"])
      ;; => \"da\"

  The `accept-language` is an Accept-Language value or a coll of language
  ranges, e.g. js/navigator.languages. A tag takes the weight of the
  longest range that matches it by basic filtering (RFC 4647, section
  3.3.1), so * only covers the tags that no other range matches. A tag
  that no range matches takes the weight of a range that lookup falls
  back from (section 3.4), e.g. da from da-DK. A tag of weight 0 is never
  acceptable.

  At the same weight, a range that names a language wins over *, then the
  range that the client lists first. For one range, the tag itself wins
  over a longer tag, which wins over a fallback, e.g. en over en-GB for
  \"en\". Then the offer that comes first wins."
  ([accept-language offers]
   (language accept-language identity offers))
  ([accept-language f coll]
   (first (language/ranked accept-language f coll))))

(comment
  (media-ranges "text/*;q=0.3, text/plain;format=flowed")
  (media-type "text/*, text/html;q=0" ["text/html" "text/turtle"])
  (language "da-DK, en;q=0.8" ["da" "en"])
  (language "da-DK" :lang [{:text "hund" :lang "da"} {:text "dog" :lang "en"}])
  #_.)
