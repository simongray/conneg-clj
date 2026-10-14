(ns dk.simongray.conneg.negotiation-test
  "Negotiating a Ring request with overrides, Vary and the 406 response."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.request :as request]))

(def negotiation
  "A negotiation with overrides in both dimensions."
  {:media-type {:offers  ["text/html" "application/ld+json" "text/turtle"]
                :query   "format"
                :aliases {"html"    "text/html"
                          "json-ld" "application/ld+json"
                          "turtle"  "text/turtle"
                          "ttl"     "text/turtle"}}
   :language   {:offers  ["da" "en"]
                :default "en"
                :query   "lang"
                :cookie  "lang"}})

(defn- req
  "A Ring request for /word/1 with the `query-string` and the `headers`."
  [query-string headers]
  {:uri "/word/1" :query-string query-string :headers headers})

(deftest fields
  (is (= {:media-type "text/turtle"
          :language   "da"
          :vary       ["Accept" "Cookie" "Accept-Language"]}
         (conneg/negotiate negotiation (req nil {"accept"          "text/turtle"
                                                 "accept-language" "da-DK"}))))
  (testing "missing fields mean no preference, but count for Vary"
    (is (= {:media-type "text/html"
            :language   "da"
            :vary       ["Accept" "Cookie" "Accept-Language"]}
           (conneg/negotiate negotiation (req nil {})))))
  (testing "a dimension without a cookie doesn't vary on Cookie"
    (is (= ["Accept"] (:vary (conneg/negotiate (select-keys negotiation [:media-type])
                                               (req nil {})))))))

(deftest not-acceptable-or-default
  (is (= {:language       "en"
          :vary           ["Accept" "Cookie" "Accept-Language"]
          :not-acceptable #{:media-type}}
         (conneg/negotiate negotiation (req nil {"accept"          "image/png"
                                                 "accept-language" "fr"})))))

(deftest query-parameters
  (testing "a query parameter decides, by an alias or what the field would hold"
    (doseq [[query-string media-type] [["format=turtle" "text/turtle"]
                                       ["format=ttl" "text/turtle"]
                                       ["format=text/turtle" "text/turtle"]
                                       ["format=application%2Fld%2Bjson" "application/ld+json"]
                                       ["format=text/*" "text/html"]
                                       ["format=turtle&format=html" "text/turtle"]]]
      (is (= media-type (:media-type (conneg/negotiate negotiation (req query-string {}))))
          query-string)))
  (testing "Vary leaves out what the URL decides"
    (is (= {:media-type "text/turtle"
            :language   "da"
            :vary       []}
           (conneg/negotiate negotiation (req "format=turtle&lang=da-DK"
                                              {"accept" "text/html"})))))
  (testing "no Vary when the URL decides it all"
    (is (nil? (get-in (conneg/not-acceptable
                       (conneg/negotiate negotiation (req "format=xml&lang=da" {}))
                       [])
                      [:headers "Vary"]))))
  (testing "an unknown value accepts nothing"
    (doseq [query-string ["format=xml" "format=html5" "format=application/ld+json"]]
      (is (= #{:media-type}
             (:not-acceptable (conneg/negotiate negotiation (req query-string {}))))
          query-string))
    (is (= "en" (:language (conneg/negotiate negotiation (req "lang=fr" {"accept-language" "da"}))))))
  (testing "an empty value counts as missing"
    (is (= "text/turtle" (:media-type (conneg/negotiate negotiation
                                                        (req "format=" {"accept" "text/turtle"})))))))

(deftest cookies
  (testing "a cookie decides when no query parameter does"
    (is (= {:media-type "text/html" :language "en" :vary ["Accept" "Cookie"]}
           (conneg/negotiate negotiation (req nil {"cookie"          "x=1; lang=en"
                                                   "accept-language" "da"}))))
    (is (= "da" (:language (conneg/negotiate negotiation (req "lang=da" {"cookie" "lang=en"}))))))
  (testing "a cookie that matches no offer counts as missing"
    (is (= {:media-type "text/html"
            :language   "da"
            :vary       ["Accept" "Cookie" "Accept-Language"]}
           (conneg/negotiate negotiation (req nil {"cookie"          "lang=fr"
                                                   "accept-language" "da"})))))
  (testing "Ring's :cookies when a middleware has parsed them"
    (is (= "en" (:language (conneg/negotiate negotiation
                                             (assoc (req nil {"cookie" "lang=da"})
                                               :cookies {"lang" {:value "en"}}))))))
  (testing "a :read for a value in a format of its own"
    (let [negotiation (assoc-in negotiation [:language :cookie]
                                {:name "languages" :read #(re-find #"[a-z]+" %)})]
      (is (= "en" (:language (conneg/negotiate negotiation
                                               (req nil {"cookie" "languages=%5B%22en%22%5D"})))))
      (testing "that throws"
        (let [negotiation (assoc-in negotiation [:language :cookie :read]
                                    #(throw (ex-info "Can't read" {:s %})))]
          (is (= "da" (:language (conneg/negotiate negotiation
                                                   (req nil {"cookie" "languages=x"})))))))
      (testing "that returns neither text nor a coll"
        (doseq [read [keyword count]]
          (let [negotiation (assoc-in negotiation [:language :cookie :read] read)]
            (is (= "da" (:language (conneg/negotiate negotiation
                                                     (req nil {"cookie" "languages=en"}))))))))
      (testing "that returns a coll"
        (let [negotiation (assoc-in negotiation [:language :cookie :read] vector)]
          (is (= "en" (:language (conneg/negotiate negotiation
                                                   (req nil {"cookie" "languages=en"}))))))))))

(deftest cookie-fields
  (doseq [[s value] [["lang=da" "da"]
                     ["a=1; lang=da; b=2" "da"]
                     ["a=1;lang = da " "da"]
                     ["lang=\"da\"" "da"]
                     ["lang=da%2DDK" "da-DK"]
                     ["lang=da+DK" "da DK"]
                     ["lang=a=b" "a=b"]
                     ["lang" nil]
                     ["language=da" nil]
                     ["" nil]]]
    (is (= value (request/header-cookie s "lang")) s)))

(deftest other-fields
  (is (= ["Accept" "Cookie" "Accept-Language" "Origin"]
         (:vary (conneg/negotiate (assoc negotiation :vary ["cookie" "Origin"])
                                  (req nil {})))))
  (is (= ["*"] (:vary (conneg/negotiate (assoc negotiation :vary ["*"]) (req nil {}))))))

(deftest alternates
  (is (= [{:media-type "text/html" :href "/word/1?lang=da&x=a+b&format=html"}
          {:media-type "application/ld+json" :href "/word/1?lang=da&x=a+b&format=json-ld"}
          {:media-type "text/turtle" :href "/word/1?lang=da&x=a+b&format=ttl"}
          {:language "da" :href "/word/1?lang=da&x=a+b"}
          {:language "en" :href "/word/1?lang=en&x=a+b"}]
         (conneg/alternates negotiation (req "lang=da&x=a%20b" {}))))
  (testing "an offer without an alias, or a dimension without a query parameter"
    (is (= [{:media-type "text/html" :href "/?f=text%2Fhtml"}
            {:language "da"}]
           (conneg/alternates {:media-type {:offers ["text/html"] :query "f"}
                               :language   {:offers ["da"]}}
                              {:uri "/"}))))
  (testing "an offer that is shorter than its alias"
    (is (= [{:language "da" :href "/?lang=da"}]
           (conneg/alternates {:language {:offers  ["da"]
                                          :query   "lang"
                                          :aliases {"danish" "da"}}}
                              {:uri "/"})))))

(deftest query-href
  (is (= "/word/1?x=1&format=ttl&lang=da"
         (conneg/query-href negotiation
                            (req "x=1&format=html" {})
                            {:media-type "text/turtle" :language "da"})))
  (testing "nil when a dimension has no query parameter"
    (is (nil? (conneg/query-href (update negotiation :language dissoc :query)
                                 (req nil {})
                                 {:language "da"})))))

(deftest link-header
  (is (= (str "</word/1?format=html>; rel=\"alternate\"; type=\"text/html\", "
              "</word/1?lang=da>; rel=\"alternate\"; hreflang=da")
         (conneg/link-header [{:media-type "text/html;charset=utf-8" :href "/word/1?format=html"}
                              {:media-type "text/turtle"}
                              {:language "da" :href "/word/1?lang=da"}])))
  (is (nil? (conneg/link-header [{:language "da"}])))
  (testing "no type for an offer that isn't a media type"
    (is (= "</x>; rel=\"alternate\"" (conneg/link-header [{:media-type "html" :href "/x"}])))))

(deftest not-acceptable
  (let [request  (req "format=xml" {"accept-language" "da"})
        decision (conneg/negotiate negotiation request)
        {:keys [status headers body]} (conneg/not-acceptable
                                       decision
                                       (conneg/alternates negotiation request))]
    (is (= 406 status))
    (is (= {"Content-Type" "text/html;charset=utf-8"
            "Vary"         "Cookie, Accept-Language"
            "Link"         (str "</word/1?format=html>; rel=\"alternate\"; type=\"text/html\", "
                                "</word/1?format=json-ld>; rel=\"alternate\"; type=\"application/ld+json\", "
                                "</word/1?format=ttl>; rel=\"alternate\"; type=\"text/turtle\"")}
           headers))
    (testing "the page lists the media types, not the languages"
      (is (str/includes? body "<a href=\"/word/1?format=ttl\">text/turtle</a>"))
      (is (not (str/includes? body "hreflang"))))))

(deftest vary-header
  (is (= "Origin, Accept, Cookie" (conneg/vary-header "Origin" ["Accept" "origin" "Cookie"])))
  (is (= "Accept" (conneg/vary-header " , " ["Accept"])))
  (is (= "*" (conneg/vary-header "Accept" ["*"])))
  (is (nil? (conneg/vary-header []))))
