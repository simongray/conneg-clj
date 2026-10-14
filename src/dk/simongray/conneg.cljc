(ns dk.simongray.conneg
  "Proactive content negotiation by RFC 9110, section 12: the media type
  and the language of a response, picked from the offers of a server by
  the Accept and Accept-Language fields of a request.

  The functions that pick take a field value and the offers in the
  server's order of preference. A nil field value means that the client
  has no preference, so the first offer wins. They return an offer as
  given, or nil when none is acceptable. With a function that returns the
  media type or language tag of an item, they pick items instead.

  The negotiate function decides for a whole Ring request, with overrides
  from query parameters and cookies, and returns the fields for Vary. The
  functions after it make the headers and responses that follow."
  (:require [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]
            [dk.simongray.conneg.impl.negotiation :as negotiation]
            [dk.simongray.conneg.impl.response :as response]))

;; RFC 9110, section 12.5.1
(defn media-ranges
  "The media ranges of `x`, an Accept value or a coll of ranges, in order
  of weight.

      (media-ranges \"text/*;q=0.3, text/plain;format=flowed\")
      ;; => [{:type \"text\" :subtype \"plain\"
      ;;      :parameters {\"format\" \"flowed\"} :weight 1.0}
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

;; RFC 9110, sections 12.1, 12.4.1 and 12.5.5
(defn negotiate
  "The media type and language of the response to the Ring `request`, by
  what the `negotiation` offers, and the fields for its Vary header.

      (negotiate {:media-type {:offers [\"text/html\" \"text/turtle\"]
                               :query  \"format\"}
                  :language   {:offers  [\"da\" \"en\"]
                               :default \"en\"
                               :cookie  \"lang\"}}
                 {:headers {\"accept\"          \"text/turtle\"
                            \"accept-language\" \"da-DK\"}})
      ;; => {:media-type \"text/turtle\"
      ;;     :language   \"da\"
      ;;     :vary       [\"Accept\" \"Cookie\" \"Accept-Language\"]}

  The `negotiation` maps :media-type and :language to a map of these:

  - :offers, in your order of preference
  - :default, the offer to choose when none is acceptable
  - :query, a query parameter that overrides the field
  - :cookie, a cookie that overrides the field, or a map of its :name and
    a :read function that returns its preference from its text
  - :aliases, a map of short names to offers, for the query parameter and
    the cookie

  A query parameter or cookie holds an alias or what the field would hold,
  e.g. turtle or text/turtle, and a query parameter decides even when no
  offer matches it. A cookie that matches no offer counts as missing.

  Beside the dimensions, the `negotiation` takes:

  - :vary, other fields that your response depends on, e.g. Origin
  - :href, a function of the request and a map of dimension to offer that
    returns the URL of that representation, or nil, for alternates and
    content-location. By default the URL sets the query parameters.

  The decision holds the offer chosen in each dimension. When none is
  acceptable and the dimension has no :default, its :not-acceptable set
  holds the dimension instead. Its :vary lists the fields that the choice
  looked at, also when they were missing, but never a query parameter,
  since that is part of the URL."
  [negotiation request]
  (negotiation/negotiate negotiation request))

(defn alternates
  "The offers of the `negotiation` as alternate representations of the
  Ring `request`, e.g. for a 406 or a Link field.

      (alternates {:media-type {:offers  [\"text/html\" \"text/turtle\"]
                                :query   \"format\"
                                :aliases {\"turtle\" \"text/turtle\"}}}
                  {:uri \"/word/1\" :query-string \"lang=da\"})
      ;; => [{:media-type \"text/html\"
      ;;      :href       \"/word/1?lang=da&format=text%2Fhtml\"}
      ;;     {:media-type \"text/turtle\"
      ;;      :href       \"/word/1?lang=da&format=turtle\"}]

  An alternate has an :href when it has a URL: by the :href function of
  the `negotiation`, or else when its dimension has a query parameter, the
  URL of the request with the parameter set to the shortest alias of the
  offer, or to the offer itself."
  [negotiation request]
  (negotiation/alternates negotiation request))

;; RFC 8288, section 3, with the alternate relation of RFC 9110, section
;; 15.4.1
(defn link-header
  "The Link value that lists the `alternates` with an :href, or nil when
  none has one.

      (link-header
       [{:media-type \"text/turtle\" :href \"/word/1?format=turtle\"}
        {:language \"da\" :href \"/word/1?lang=da\"}])
      ;; => \"</word/1?format=turtle>; rel=\\\"alternate\\\";
      ;;     type=\\\"text/turtle\\\", </word/1?lang=da>;
      ;;     rel=\\\"alternate\\\"; hreflang=da\""
  [alternates]
  (response/link-header alternates))

;; RFC 9110, section 15.5.7
(defn not-acceptable
  "The 406 Not Acceptable response to the request of the `decision`, which
  lists the `alternates` in an HTML page and in its Link field.

  It only lists the alternates of the dimensions in which nothing is
  acceptable. It has the Vary field of the decision, since the same
  request with other Accept fields might succeed."
  [decision alternates]
  (response/not-acceptable decision alternates))

;; RFC 9110, section 8.7
(defn content-location
  "The URL of the representation that the `decision` chose for the Ring
  `request` by the `negotiation`, or nil when it has none.

      (content-location {:media-type {:offers  [\"text/html\" \"text/turtle\"]
                                      :query   \"format\"
                                      :aliases {\"turtle\" \"text/turtle\"}}
                         :language   {:offers [\"da\" \"en\"] :query \"lang\"}}
                        {:uri \"/word/1\"}
                        {:media-type \"text/turtle\" :language \"da\"})
      ;; => \"/word/1?format=turtle&lang=da\"

  A GET on the URL must return the same representation, so the URL pins
  down each dimension: by the :href function of the `negotiation`, or else
  by a query parameter for each dimension."
  [negotiation request decision]
  (negotiation/content-location negotiation request decision))

;; RFC 9110, section 15.4.4, and Cool URIs for the Semantic Web, sections
;; 4.2 and 4.3
(defn see-other
  "The 303 See Other response that sends the request of the `decision` to
  the `location`, e.g. from a thing to a document about it.

      (see-other \"/doc/alice\" {:media-type \"text/html\" :vary [\"Accept\"]})
      ;; => {:status  303
      ;;     :headers {\"Location\"     \"/doc/alice\"
      ;;               \"Content-Type\" \"text/html;charset=utf-8\"
      ;;               \"Vary\"         \"Accept\"}
      ;;     :body    \"<!DOCTYPE html>...\"}

  It has the Vary field of the decision, since the location may depend on
  it, and a short HTML note with a link to the location."
  [location decision]
  (response/see-other location decision))

;; RFC 9110, section 12.5.5
(defn vary-header
  "The Vary value of the field `names`, added to the Vary value `s` when
  given, or nil when there are none.

      (vary-header \"Origin\" [\"Accept\" \"origin\" \"Cookie\"])
      ;; => \"Origin, Accept, Cookie\"

  Field names ignore case, and * replaces the others."
  ([names]
   (vary-header nil names))
  ([s names]
   (response/vary-header s names)))

(comment
  (media-ranges "text/*;q=0.3, text/plain;format=flowed")
  (media-type "text/*, text/html;q=0" ["text/html" "text/turtle"])
  (language "da-DK, en;q=0.8" ["da" "en"])
  (language "da-DK" :lang [{:text "hund" :lang "da"} {:text "dog" :lang "en"}])
  #_.)
