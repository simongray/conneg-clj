(ns dk.simongray.conneg.generators
  "Random Accept and Accept-Language values and offers for the generative
  tests.

  Most parts come from small sets, so that ranges and offers often match."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

(def qvalue
  "A weight as Accept writes it, or nil for none."
  (gen/one-of [(gen/return nil)
               (gen/elements ["0" "0.1" "0.5" "0.9" "1" "1.0" "0.001" "0.123"])]))

(defn with-weight
  "Text from the generator `g`, with a weight from qvalue."
  [g]
  (gen/fmap (fn [[s q]]
              (cond-> s q (str ";q=" q)))
            (gen/tuple g qvalue)))

(defn field
  "A field value of the elements of the generator `element`, joined by
  commas with random whitespace."
  [element]
  (gen/fmap (fn [[elements separators]]
              (str/join (map str elements separators)))
            (gen/tuple (gen/vector element 0 6)
                       (gen/vector (gen/elements ["," ", " " , " ",, "]) 6))))

(def media-type
  "A media type from a small set, maybe with a parameter."
  (gen/fmap (fn [[type subtype parameter]]
              (str type "/" subtype parameter))
            (gen/tuple (gen/elements ["text" "application" "image"])
                       (gen/elements ["html" "plain" "json" "turtle" "png"])
                       (gen/elements ["" "" "" ";level=1" ";charset=utf-8"]))))

(def media-range
  "A media type or range, maybe with a parameter."
  (gen/one-of [media-type
               (gen/elements ["*/*" "text/*" "application/*" "image/*"])]))

(def accept
  "An Accept value."
  (field (with-weight media-range)))

(def media-types
  "Offers of media types."
  (gen/vector-distinct media-type {:min-elements 1 :max-elements 5}))

(def tag
  "A language tag from a small set."
  (gen/elements ["da" "da-DK" "en" "en-GB" "en-US" "de" "de-CH" "zh-Hant-TW"]))

(def language-range
  "A language range."
  (gen/one-of [tag (gen/elements ["*" "zh-Hant" "zh"])]))

(def accept-language
  "An Accept-Language value."
  (field (with-weight language-range)))

(def tags
  "Offers of language tags."
  (gen/vector-distinct tag {:min-elements 1 :max-elements 5}))

(def random-case
  "A function that changes the case of each letter of a text at random."
  (gen/fmap (fn [ups]
              (fn [s]
                (str/join (map #(if %2 (str/upper-case %1) (str/lower-case %1))
                               s (cycle ups)))))
            (gen/vector gen/boolean 1 5)))

(def text
  "Text that is mostly the characters of fields."
  (gen/fmap str/join
            (gen/vector (gen/frequency [[8 (gen/elements (seq "a/*;=q0.1, \"\\-"))]
                                        [1 gen/char]])
                        0 30)))
