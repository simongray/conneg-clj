(ns dk.simongray.conneg.generative-test
  "Properties that hold for any Accept or Accept-Language value, tried on
  random ones in both Clojure and ClojureScript."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.generators :as g]
            [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]
            [dk.simongray.conneg.impl.syntax :as syntax]
            [dk.simongray.whatwg-mime :as mime]
            [dk.simongray.whatwg-url.params :as params]))

(defspec any-text-picks-an-offer-or-nil 2000
  (prop/for-all [s      (gen/one-of [g/text g/accept g/accept-language])
                 offers (gen/one-of [g/media-types g/tags])]
    (let [picks [(conneg/media-type s offers) (conneg/language s offers)]]
      (and (every? #(or (nil? %) (some #{%} offers)) picks)
           (vector? (conneg/media-ranges s))))))

(defspec media-ranges-are-in-order-of-weight 1000
  (prop/for-all [s g/accept]
    (let [ranges (conneg/media-ranges s)]
      (and (apply >= (map :weight (cons {:weight 1.0} ranges)))
           (= ranges (conneg/media-ranges ranges))))))

(defspec a-media-type-of-weight-0-never-wins 2000
  (prop/for-all [s      g/accept
                 offers g/media-types]
    (if-let [offer (conneg/media-type s offers)]
      (pos? (:weight (media/weigh (media/preferences s) (mime/parse offer))))
      true)))

(defspec a-language-of-weight-0-never-wins 2000
  (prop/for-all [s      g/accept-language
                 offers g/tags]
    (if-let [offer (conneg/language s offers)]
      (pos? (:weight (language/weigh (language/preferences s)
                                     (syntax/lower offer))))
      true)))

(defspec the-winner-has-the-highest-weight 2000
  (prop/for-all [s      g/accept
                 offers g/media-types]
    (let [ranges (media/preferences s)
          weight #(or (:weight (media/weigh ranges (mime/parse %))) 0)]
      (if-let [offer (conneg/media-type s offers)]
        (every? #(<= (weight %) (weight offer)) offers)
        (every? #(zero? (weight %)) offers)))))

(defspec types-and-tags-ignore-case 2000
  (prop/for-all [s1       g/accept-language
                 s2       (gen/fmap #(str "text/html;q=0.5, " %)
                                    (gen/elements ["*/*" "text/*" "IMAGE/*"]))
                 change   g/random-case
                 offers   g/tags]
    (and (= (conneg/language s1 offers) (conneg/language (change s1) offers))
         (= (conneg/media-type s2 ["image/png" "text/html"])
            (conneg/media-type (change s2) ["image/png" "text/html"])))))

(def negotiation
  "A negotiation with overrides in both dimensions."
  {:media-type {:offers  ["text/html" "text/turtle"]
                :query   "format"
                :aliases {"turtle" "text/turtle"}}
   :language   {:offers  ["da" "en-GB"]
                :default "da"
                :query   "lang"
                :cookie  "lang"}})

(defspec any-request-gets-a-decision 3000
  (prop/for-all [request g/request]
    (let [{:keys [media-type language vary not-acceptable] :as decision}
          (conneg/negotiate negotiation request)
          query (some-> (:query-string request) params/parse)]
      (and (or (nil? media-type) (some #{media-type} ["text/html" "text/turtle"]))
           (some #{language} ["da" "en-GB"])
           (= (some? not-acceptable) (nil? media-type))
           (if (not-empty (params/get query "format"))
             (not (some #{"Accept"} vary))
             (some #{"Accept"} vary))
           (if (not-empty (params/get query "lang"))
             (empty? (remove #{"Accept"} vary))
             (some #{"Cookie"} vary))))))
