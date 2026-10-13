(ns ^:no-doc dk.simongray.conneg.impl.negotiation
  "The negotiation of a Ring request in each dimension that the server
  offers, from a query parameter, a cookie or the Accept fields, and the
  fields that the decision looked at.

  A negotiation is a map of each dimension (:media-type or :language) to
  what the server offers in it, plus :vary. A decision is a map of each
  dimension to the offer chosen in it, plus :vary and :not-acceptable."
  (:require [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]
            [dk.simongray.conneg.impl.request :as request]
            [dk.simongray.conneg.impl.response :as response]
            [dk.simongray.conneg.impl.syntax :as syntax]
            [dk.simongray.whatwg-url.params :as params]))

(def dimensions
  "The dimensions of a negotiation, in the order that it decides them."
  [:media-type :language])

(def fields
  "The request field of each dimension, as Vary writes it."
  {:media-type "Accept"
   :language   "Accept-Language"})

(defn- ranked
  "The `offers` of the dimension `d` that `x` accepts, best first."
  [d x offers]
  (case d
    :media-type (media/ranked x identity offers)
    :language   (language/ranked x identity offers)))

(defn- preferences
  "The ranges in `x` for the dimension `d`, without those that don't parse."
  [d x]
  (case d
    :media-type (media/media-ranges x)
    :language   (language/priority-list x)))

(defn override
  "The offer of the `dimension` d that the query parameter or cookie value
  `x` asks for, by an alias or as its field would, or nil.

  Unlike a field, an `x` that doesn't parse accepts nothing, e.g. xml."
  [d {:keys [offers aliases] :as dimension} x]
  (let [aliased (get aliases x)]
    (cond
      (some #{aliased} offers) aliased
      (seq (preferences d x))  (first (ranked d x offers)))))

(defn cookie-value
  "The value of the `cookie` of the Ring `request`, read by the :read of
  the cookie, or nil when it's missing or the :read throws."
  [request cookie]
  (let [{:keys [name read] :or {read identity}} (if (map? cookie)
                                                  cookie
                                                  {:name cookie})]
    (when-let [s (request/cookie request name)]
      (try
        (read s)
        (catch #?(:clj Exception :cljs :default) _
          nil)))))

;; RFC 9110, section 12.5.5: Vary names the fields that the choice looked
;; at, also when they were missing. A query parameter is part of the URL,
;; so it never goes in Vary.
(defn decide
  "The choice in the dimension `d` of the `dimension` for the Ring
  `request`, or nil, and the fields that it looked at.

  A query parameter decides first, then the cookie and then the field. A
  cookie whose value matches no offer counts as missing."
  [d {:keys [offers query cookie] :as dimension} request]
  (let [field        (fields d)
        query-value  (some->> query (request/query-param request) not-empty)
        cookie-offer (when (and cookie (not query-value))
                       (some->> (cookie-value request cookie)
                                (override d dimension)))]
    (cond
      query-value
      {:choice (override d dimension query-value)
       :vary   []}

      cookie-offer
      {:choice cookie-offer
       :vary   ["Cookie"]}

      :else
      {:choice (first (ranked d (get-in request [:headers (syntax/lower field)])
                              offers))
       :vary   (cond-> [] cookie (conj "Cookie") :always (conj field))})))

;; RFC 9110, section 12.4.1: when nothing is acceptable, a server either
;; answers 406 or ignores the field, which a :default stands for
(defn negotiate
  "The decision of the `negotiation` for the Ring `request`."
  [{:keys [vary] :as negotiation} request]
  (let [decided (for [d     dimensions
                      :let  [dimension (get negotiation d)]
                      :when dimension]
                  (let [decision (decide d dimension request)]
                    (assoc decision
                      :dimension d
                      :choice (or (:choice decision) (:default dimension)))))
        missing (set (keep #(when-not (:choice %) (:dimension %)) decided))]
    (cond-> (into {}
                  (keep #(when (:choice %) [(:dimension %) (:choice %)]))
                  decided)
      :always       (assoc :vary (response/merge-fields
                                  (concat (mapcat :vary decided) vary)))
      (seq missing) (assoc :not-acceptable missing))))

(defn alias-of
  "The shortest of the `aliases` of the `offer`, or the first in
  alphabetical order of the shortest, or else the offer itself."
  [aliases offer]
  (or (->> aliases
           (keep (fn [[k v]] (when (= offer v) k)))
           (sort-by (juxt count identity))
           first)
      offer))

(defn href
  "The URL of the Ring `request` with its query parameter `name` set to
  `value`, relative to its origin."
  [{:keys [uri query-string] :as request} name value]
  (str uri "?" (-> (params/parse query-string)
                   (params/set name value)
                   params/serialize)))

(defn alternates
  "The offers of the `negotiation` as alternates of the Ring `request`,
  with an :href where the dimension of an offer has a query parameter."
  [negotiation request]
  (vec (for [d     dimensions
             :let  [{:keys [offers query aliases]} (get negotiation d)]
             offer offers]
         (cond-> {d offer}
           query (assoc :href (href request query (alias-of aliases offer)))))))
