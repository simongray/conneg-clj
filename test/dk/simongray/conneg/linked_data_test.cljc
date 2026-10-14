(ns dk.simongray.conneg.linked-data-test
  "The patterns of Cool URIs for the Semantic Web (W3C Interest Group Note,
  3 December 2008, with its errata): 303 See Other, Content-Location and
  Link with rel=\"alternate\"."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.pedestal :as conneg.pedestal]
            [dk.simongray.conneg.ring :as conneg.ring]))

(def documents
  "The media types of the documents about Alice."
  {:media-type {:offers  ["text/html" "application/rdf+xml"]
                :query   "format"
                :aliases {"html" "text/html" "rdf" "application/rdf+xml"}}})

(defn page
  "A Ring handler that answers with a page in the negotiated media type."
  [{::conneg/keys [decision]}]
  {:status  200
   :headers {"Content-Type" (:media-type decision)}
   :body    "..."})

;; Cool URIs, section 4.3: the thing redirects to a different document for
;; each media type
(deftest see-other-by-media-type
  (let [thing (conneg.ring/wrap-negotiate
               (fn [{::conneg/keys [decision]}]
                 (conneg/see-other (if (= "text/html" (:media-type decision))
                                     "http://www.example.com/people/alice"
                                     "http://www.example.com/data/alice")
                                   decision))
               documents)]
    (doseq [[accept location] [["application/rdf+xml" "http://www.example.com/data/alice"]
                               ["text/html" "http://www.example.com/people/alice"]
                               ;; section 4.7 lets the server prefer RDF here,
                               ;; which needs a server-side quality
                               ["application/rdf+xml;q=0.7, text/html"
                                "http://www.example.com/people/alice"]
                               ["*/*" "http://www.example.com/people/alice"]]]
      (let [{:keys [status headers]} (thing {:uri     "/id/alice"
                                             :headers {"accept" accept}})]
        (is (= 303 status) accept)
        (is (= location (get headers "Location")) accept)
        (is (= "Accept" (get headers "Vary")) accept)))))

;; Cool URIs, section 4.2: the thing redirects to one document, which
;; negotiates and names the representation in Content-Location
(deftest see-other-and-content-location
  (is (= {:status  303
          :headers {"Location"     "http://www.example.com/doc/alice"
                    "Content-Type" "text/html;charset=utf-8"}
          :body    (str "<!DOCTYPE html>\n<html lang=\"en\">\n<meta charset=\"utf-8\">\n"
                        "<title>See Other</title>\n"
                        "<p>See <a href=\"http://www.example.com/doc/alice\">"
                        "http://www.example.com/doc/alice</a>.\n")}
         (conneg/see-other "http://www.example.com/doc/alice" {:vary []})))
  (let [document (conneg.ring/wrap-negotiate page documents {:content-location? true})]
    (is (= {"Content-Type"     "application/rdf+xml"
            "Vary"             "Accept"
            "Content-Location" "/doc/alice?format=rdf"}
           (:headers (document {:request-method :get
                                :uri            "/doc/alice"
                                :headers        {"accept" "application/rdf+xml"}}))))))

;; Cool URIs, section 2.1, with the Vary of the errata
(deftest content-location-of-both-dimensions
  (let [negotiation (assoc documents :language {:offers ["en" "de"]
                                                :query  "lang"})
        resource    (conneg.ring/wrap-negotiate page negotiation {:content-location? true})]
    (is (= {"Content-Type"     "text/html"
            "Vary"             "Accept, Accept-Language"
            "Content-Location" "/people/alice?format=html&lang=en"}
           (:headers (resource {:request-method :get
                                :uri            "/people/alice"
                                :headers        {"accept"          "text/html, application/xhtml+xml"
                                                 "accept-language" "en, de"}}))))
    (testing "only when a query parameter pins down each dimension"
      (is (nil? (conneg/content-location (assoc-in negotiation [:language :query] nil)
                                         {:uri "/people/alice"}
                                         {:media-type "text/html" :language "en"}))))
    (testing "not when nothing is acceptable"
      (is (nil? (conneg/content-location negotiation
                                         {:uri "/people/alice"}
                                         {:language "en" :not-acceptable #{:media-type}}))))
    (testing "not over a Content-Location of the handler"
      (let [resource (conneg.ring/wrap-negotiate
                      #(assoc-in (page %) [:headers "content-location"] "/alice.html")
                      negotiation
                      {:content-location? true})]
        (is (= "/alice.html" (get-in (resource {:request-method :get :uri "/people/alice"})
                                     [:headers "content-location"])))
        (is (nil? (get-in (resource {:request-method :get :uri "/people/alice"})
                          [:headers "Content-Location"])))))))

;; Cool URIs, section 4.6, as a Link header (RFC 8288)
(deftest links-to-alternates
  (let [document (conneg.ring/wrap-negotiate page documents {:link? true})]
    (testing "the other representations"
      (is (= "</doc/alice?format=rdf>; rel=\"alternate\"; type=\"application/rdf+xml\""
             (get-in (document {:request-method :get
                                :uri            "/doc/alice"
                                :headers        {"accept" "text/html"}})
                     [:headers "Link"]))))
    (testing "after a Link of the handler"
      (doseq [[link expected] [["</>; rel=\"start\"" "</>; rel=\"start\", "]
                               ["" ""]]]
        (let [document (conneg.ring/wrap-negotiate
                        #(assoc-in (page %) [:headers "Link"] link)
                        documents
                        {:link? true})]
          (is (= (str expected
                      "</doc/alice?format=rdf>; rel=\"alternate\"; type=\"application/rdf+xml\"")
                 (get-in (document {:request-method :get :uri "/doc/alice"})
                         [:headers "Link"]))
              link))))
    (testing "only for a success"
      (let [missing (conneg.ring/wrap-negotiate (constantly {:status 404})
                                                documents
                                                {:link? true :content-location? true})]
        (is (= {"Vary" "Accept"} (:headers (missing {:request-method :get
                                                     :uri            "/doc/bob"}))))))
    (testing "only for GET and HEAD"
      (is (some? (get-in (document {:request-method :head :uri "/doc/alice"})
                         [:headers "Link"])))
      (let [create (conneg.ring/wrap-negotiate (constantly {:status 201 :body "Created"})
                                               documents
                                               {:link? true :content-location? true})]
        (is (= {"Vary" "Accept"} (:headers (create {:request-method :post
                                                    :uri            "/doc/alice"}))))))))

(deftest pedestal-options
  (let [{:keys [enter leave]} (conneg.pedestal/negotiate documents {:link?             true
                                                                    :content-location? true})
        ctx   (enter {:request {:request-method :get
                                :uri            "/doc/alice"
                                :headers        {"accept" "application/rdf+xml"}}})
        ctx   (leave (assoc ctx :response (page (:request ctx))))]
    (is (= {"Content-Type"     "application/rdf+xml"
            "Vary"             "Accept"
            "Link"             "</doc/alice?format=html>; rel=\"alternate\"; type=\"text/html\""
            "Content-Location" "/doc/alice?format=rdf"}
           (get-in ctx [:response :headers])))))

(deftest see-other-escapes-the-location
  (is (str/includes? (:body (conneg/see-other "/a?b=1&c=\"2\"" {}))
                     "<a href=\"/a?b=1&amp;c=&quot;2&quot;\">")))

(def extensions
  "The file extension of each media type of the documents."
  {"text/html" "html" "application/rdf+xml" "rdf"})

(defn file-href
  "The URL of the file of the representation of the Ring `request` with
  the `offers`, as Cool URIs names them, e.g. alice.en.html."
  [{:keys [uri] :as request} {:keys [media-type language] :as offers}]
  (str "http://www.example.com" uri
       (when language (str "." language))
       (when media-type (str "." (extensions media-type)))))

(def files
  "The documents about Alice in two media types and two languages, as files."
  (assoc documents
    :language {:offers ["en" "de"]}
    :href     file-href))

;; Cool URIs, section 2.1, with the Content-Location and Vary of the errata
(deftest content-location-of-a-file
  (let [resource (conneg.ring/wrap-negotiate page files {:content-location? true})]
    (is (= {"Content-Type"     "text/html"
            "Vary"             "Accept, Accept-Language"
            "Content-Location" "http://www.example.com/people/alice.en.html"}
           (:headers (resource {:request-method :get
                                :uri            "/people/alice"
                                :headers        {"accept"          "text/html, application/xhtml+xml"
                                                 "accept-language" "en, de"}}))))))

;; Cool URIs, sections 4.2 and 4.6
(deftest links-to-files
  (is (= [{:media-type "text/html" :href "http://www.example.com/doc/alice.html"}
          {:media-type "application/rdf+xml" :href "http://www.example.com/doc/alice.rdf"}
          {:language "en" :href "http://www.example.com/doc/alice.en"}
          {:language "de" :href "http://www.example.com/doc/alice.de"}]
         (conneg/alternates files {:uri "/doc/alice"})))
  (testing "an :href of nil means no URL"
    (let [files (assoc files :href (fn [request {:keys [language] :as offers}]
                                     (when-not language (file-href request offers))))]
      (is (= [{:language "en"} {:language "de"}]
             (filter :language (conneg/alternates files {:uri "/doc/alice"}))))
      (is (nil? (conneg/content-location files {:uri "/doc/alice"}
                                         {:media-type "text/html" :language "en"}))))))
