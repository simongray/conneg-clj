(ns dk.simongray.conneg.rfc-test
  "The examples of RFC 9110 for Accept and Accept-Language, and of RFC
  7231 where RFC 9110 changed them."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]
            [dk.simongray.whatwg-mime :as mime]))

(defn- weight
  "The weight that the Accept value `accept` gives the media type `s`, or
  nil when no range matches it."
  [accept s]
  (:weight (media/weigh (media/media-ranges accept) (mime/parse s))))

(defn- language-weight
  "The weight that the Accept-Language value `accept-language` gives the
  `tag`, or nil when no range matches it."
  [accept-language tag]
  (:weight (language/weigh (language/priority-list accept-language) tag)))

;; RFC 9110, section 12.5.1
(deftest accept-examples
  (testing "audio/basic, or else any audio type at 0.2"
    (let [accept "audio/*; q=0.2, audio/basic"]
      (is (= 1.0 (weight accept "audio/basic")))
      (is (= 0.2 (weight accept "audio/mpeg")))
      (is (nil? (weight accept "text/html")))))
  (testing "two types that are equally preferred, then the others in order"
    (is (= ["text/html" "text/x-c" "text/x-dvi" "text/plain"]
           (media/ranked "text/plain; q=0.5, text/html, text/x-dvi; q=0.8, text/x-c"
                         identity
                         ["text/plain" "text/x-dvi" "text/x-c" "text/html"]))))
  (testing "more specific ranges have precedence"
    (is (= ["text/plain;format=flowed" "text/plain" "text/html" "image/jpeg"]
           (media/ranked "text/*, text/plain, text/plain;format=flowed, */*"
                         identity
                         ["image/jpeg" "text/html" "text/plain" "text/plain;format=flowed"])))))

;; RFC 9110, section 12.5.1, Table 5, with erratum 7138 for
;; text/html;level=3, whose weight the RFC misprints as 0.7
(deftest accept-weights
  (let [accept (str "text/*;q=0.3, text/plain;q=0.7, text/plain;format=flowed, "
                    "text/plain;format=fixed;q=0.4, */*;q=0.5")]
    (doseq [[s w] [["text/plain;format=flowed" 1.0]
                   ["text/plain" 0.7]
                   ["text/html" 0.3]
                   ["image/jpeg" 0.5]
                   ["text/plain;format=fixed" 0.4]
                   ["text/html;level=3" 0.3]]]
      (is (= w (weight accept s)) s))))

;; RFC 7231, section 5.3.2, where a range without parameters matches a
;; type with them
(deftest accept-weights-of-rfc-7231
  (let [accept (str "text/*;q=0.3, text/html;q=0.7, text/html;level=1, "
                    "text/html;level=2;q=0.4, */*;q=0.5")]
    (doseq [[s w] [["text/html;level=1" 1.0]
                   ["text/html" 0.7]
                   ["text/plain" 0.3]
                   ["image/jpeg" 0.5]
                   ["text/html;level=2" 0.4]
                   ["text/html;level=3" 0.7]]]
      (is (= w (weight accept s)) s))))

;; RFC 9110, sections 12.4.2 and 12.4.3
(deftest weight-0
  (testing "*/*;q=0 asks for a 406 when nothing else is acceptable"
    (is (nil? (conneg/media-type "*/*;q=0" ["text/html" "image/png"]))))
  (testing "a more specific range rules a type out"
    (is (= "text/html" (conneg/media-type "text/*, text/plain;q=0"
                                          ["text/plain" "text/html"])))))

;; RFC 9110, section 12.5.1
(deftest accept-parameters
  (testing "q is the weight wherever it is"
    (is (= [{:type "text" :subtype "html" :parameters {"level" "1"} :weight 0.5}]
           (conneg/media-ranges "text/html;q=0.5;level=1"))))
  (testing "parameters may be empty"
    (is (= [{:type "text" :subtype "html" :weight 1.0}]
           (conneg/media-ranges "text/html;"))))
  (testing "a quoted value may hold a comma"
    (is (= [{:type "text" :subtype "plain" :parameters {"x" "a,b"} :weight 0.5}]
           (conneg/media-ranges "text/plain;x=\"a,b\";q=0.5"))))
  (testing "no whitespace around ="
    (is (= [] (conneg/media-ranges "text/html;q = 0.5")))))

;; RFC 9110, section 5.6.1.2
(deftest empty-list-elements
  (doseq [accept [",text/html" "text/html,,text/turtle" "text/html, , text/turtle"]]
    (is (= [{:type "text" :subtype "html" :weight 1.0}]
           (take 1 (conneg/media-ranges accept)))
        accept)))

;; RFC 9110, section 12.5.4, with basic filtering (RFC 4647, section
;; 3.3.1) and the longest range of RFC 2616, section 14.4
(deftest accept-language-example
  (let [accept-language "da, en-gb;q=0.8, en;q=0.7"]
    (doseq [[tag w] [["da" 1.0]
                     ["da-dk" 1.0]
                     ["en-gb" 0.8]
                     ["en-us" 0.7]
                     ["en" 0.7]
                     ["fr" nil]]]
      (is (= w (language-weight accept-language tag)) tag))))
