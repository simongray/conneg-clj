(ns dk.simongray.conneg-test
  "The public functions on the cases that other libraries get wrong, among
  them the bugs that DanNet had with Pedestal's negotiation."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]))

(def media-types
  "The offers of the Accept cases."
  ["text/html" "application/json" "text/turtle"])

(deftest media-type
  (doseq [[accept expected] [["application/json;q=0.2, text/*;q=0.9" "text/html"]
                             ["text/html;q=0" nil]
                             ["*/*;q=0.1, text/html;q=0" "application/json"]
                             ["text/*, text/html;q=0" "text/turtle"]
                             ["TEXT/HTML" "text/html"]
                             ["*/*" "text/html"]
                             ["text/turtle;q=0.5, application/ld+json" "text/turtle"]
                             ["image/*" nil]
                             ["text/html;q=0.1, */*" "application/json"]
                             ["text/html;level=1" nil]]]
    (is (= expected (conneg/media-type accept media-types)) accept))
  (testing "at the same weight, the more specific range wins"
    (is (= "text/html" (conneg/media-type "text/*, text/html"
                                          ["text/turtle" "text/html"]))))
  (testing "then the range that the client lists first"
    (is (= "application/json" (conneg/media-type "application/json, text/html"
                                                 media-types))))
  (testing "a range without parameters matches any offer of its type"
    (is (= "text/html;charset=utf-8"
           (conneg/media-type "text/html" ["text/html;charset=utf-8"]))))
  (testing "charset values ignore case, but other values don't"
    (is (= "text/html;charset=utf-8"
           (conneg/media-type "text/html;charset=UTF-8" ["text/html;charset=utf-8"])))
    (is (nil? (conneg/media-type "text/html;level=A" ["text/html;level=a"])))))

(deftest media-type-of-malformed-fields
  (testing "an element that doesn't parse is left out"
    (doseq [accept ["text/html;q=abc, application/json;q=0.5"
                    "text/html;q=1.5, application/json;q=0.5"
                    "text/html;q=.5, application/json;q=0.5"
                    "*/html, application/json;q=0.5"]]
      (is (= "application/json" (conneg/media-type accept media-types)) accept)))
  (testing "a field in which nothing parses counts as no preference"
    (doseq [accept ["application/json;q=abc" "@@@,,,;;;" "text/html;q" "\"" "*"]]
      (is (= "text/html" (conneg/media-type accept media-types)) accept)))
  (testing "an empty field accepts nothing"
    (doseq [accept ["" " " ", ," []]]
      (is (nil? (conneg/media-type accept media-types)) (pr-str accept))))
  (testing "no field means no preference"
    (is (= "text/html" (conneg/media-type nil media-types)))))

(deftest media-type-of-items
  (let [renderers [{:type "text/html" :name :html}
                   {:type "text/turtle" :name :turtle}
                   {:type nil :name :nothing}]]
    (is (= :turtle (:name (conneg/media-type "text/turtle" :type renderers))))
    (is (= :html (:name (conneg/media-type "*/*" :type renderers))))
    (testing "an item without a media type matches nothing"
      (is (nil? (conneg/media-type "*/*" :type [{:type nil}]))))))

(deftest media-ranges
  (is (= [{:type "text" :subtype "plain" :parameters {"format" "flowed"} :weight 1.0}
          {:type "text" :subtype "*" :weight 0.3}]
         (conneg/media-ranges "text/*;q=0.3, text/plain;format=flowed")))
  (testing "names in lower case"
    (is (= [{:type "text" :subtype "html" :parameters {"level" "1"} :weight 0.5}]
           (conneg/media-ranges "Text/HTML; Level=1; Q=0.5"))))
  (testing "repeats are left out"
    (is (= [{:type "text" :subtype "html" :weight 0.0}]
           (conneg/media-ranges "text/html;q=0, TEXT/html"))))
  (testing "a coll of ranges, as text or as maps"
    (is (= [{:type "text" :subtype "html" :weight 1.0}
            {:type "*" :subtype "*" :weight 0.1}]
           (conneg/media-ranges ["*/*;q=0.1" "text/html"])
           (conneg/media-ranges (conneg/media-ranges "text/html, */*;q=0.1")))))
  (testing "maps in lower case"
    (is (= [{:type "text" :subtype "html" :weight 1.0}]
           (conneg/media-ranges [{:type "Text" :subtype "HTML"}])))))

(deftest language-ranges
  (is (= [{:range "da-dk" :weight 1.0} {:range "en" :weight 0.8}]
         (conneg/language-ranges "en;q=0.8, da-DK")))
  (testing "repeats are left out"
    (is (= [{:range "de" :weight 1.0}] (conneg/language-ranges "de, DE"))))
  (testing "a coll of ranges, without nil"
    (is (= [{:range "de" :weight 1.0} {:range "en" :weight 1.0}]
           (conneg/language-ranges ["de" nil "en"])))))

(deftest language
  (doseq [[accept-language expected] [["da-DK" "da"]
                                      ["da-DK, en;q=0.8" "da"]
                                      ["EN" "en"]
                                      ["da;q=0" nil]
                                      ["*;q=0.5, da;q=0" "en"]
                                      ["en-GB,en;q=0.9,da;q=0.8" "en"]
                                      ["*" "da"]
                                      ["da-DK, da;q=0" nil]
                                      ["da-DK;q=0.2, *;q=0.9" "en"]
                                      ["da-DK, *;q=0.1" "da"]
                                      ["*, en" "en"]
                                      ["da-DK, en" "da"]
                                      ["en, da-DK" "en"]]]
    (is (= expected (conneg/language accept-language ["da" "en"])) accept-language))
  (testing "a range matches the tags that start with it"
    (is (= "en-GB" (conneg/language "en" ["da" "en-GB"]))))
  (testing "the tag itself beats a longer one"
    (is (= "en" (conneg/language "en" ["en-US" "en"]))))
  (testing "a longer tag beats a fallback"
    (is (= "zh-Hant-TW" (conneg/language "zh-Hant" ["zh" "zh-Hant-TW"]))))
  (testing "no fallback from a range of weight 0"
    (is (= "en" (conneg/language "*;q=0.1, en-US;q=0" ["en-US" "en"]))))
  (testing "no regional siblings"
    (is (nil? (conneg/language "en-US" ["en-GB"]))))
  (testing "a coll of ranges, e.g. js/navigator.languages"
    (is (= "da" (conneg/language ["da-DK" "en"] ["en" "da"])))))

(deftest language-of-malformed-fields
  (is (= "en" (conneg/language "da;q=abc, en;q=0.5" ["da" "en"])))
  (is (= "da" (conneg/language "da;q=abc" ["da" "en"])))
  (is (= "da" (conneg/language "en_US" ["da" "en"])))
  (is (nil? (conneg/language "" ["da" "en"])))
  (is (nil? (conneg/language [] ["da" "en"])))
  (is (= "da" (conneg/language nil ["da" "en"]))))

(deftest language-of-items
  (is (= {:text "hund" :lang "da"}
         (conneg/language "da-DK, en;q=0.8" :lang [{:text "dog" :lang "en"}
                                                   {:text "hund" :lang "da"}])))
  (testing "an item without a tag matches nothing"
    (is (nil? (conneg/language "*" :lang [{:text "?"}])))))
