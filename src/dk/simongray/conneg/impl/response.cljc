(ns ^:no-doc dk.simongray.conneg.impl.response
  "The headers and responses that follow from a negotiation: Vary, Link
  and 406 Not Acceptable."
  (:require [clojure.string :as str]
            [dk.simongray.conneg.impl.syntax :as syntax]
            [dk.simongray.whatwg-mime :as mime]))

;; RFC 9110, section 12.5.5: field names ignore case, and * stands for
;; everything
(defn merge-fields
  "The field `names` without blanks and repeats, which ignore case, or
  only * when one of them is *."
  [names]
  (let [names (remove str/blank? (map str/trim names))]
    (if (some #{"*"} names)
      ["*"]
      (first (reduce (fn [[kept seen] name]
                       (let [k (syntax/lower name)]
                         (if (seen k)
                           [kept seen]
                           [(conj kept name) (conj seen k)])))
                     [[] #{}]
                     names)))))

(defn vary-header
  "The Vary value `s` with the field `names` added, or nil when it has
  none."
  [s names]
  (some->> (concat (when s (syntax/elements s)) names)
           merge-fields
           not-empty
           (str/join ", ")))

;; RFC 8288, section 3, where the type must be quoted (appendix C) and
;; holds no parameters (section 3.4.1)
(defn link
  "The link-value of the `alternate`, or nil when it has no :href."
  [{:keys [href media-type language] :as alternate}]
  (when href
    (str "<" href ">; rel=\"alternate\""
         (when media-type (str "; type=\"" (mime/essence media-type) "\""))
         (when language (str "; hreflang=" language)))))

(defn link-header
  "The Link value of the `alternates` that have an :href, or nil when none
  has."
  [alternates]
  (some->> (keep link alternates)
           not-empty
           (str/join ", ")))

(defn- escape
  "The text `s` with the characters that HTML reserves escaped."
  [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- list-item
  "The HTML list item of the `alternate`, a link when it has an :href."
  [{:keys [href media-type language] :as alternate}]
  (let [label (escape (or media-type language))]
    (str "<li>"
         (if href
           (str "<a href=\"" (escape href) "\""
                (when language (str " hreflang=\"" (escape language) "\""))
                ">" label "</a>")
           label)
         "\n")))

;; RFC 9110, section 15.5.7: a list of the representations and their URLs
(defn not-acceptable-page
  "An HTML page that lists the `alternates`."
  [alternates]
  (str "<!DOCTYPE html>\n"
       "<html lang=\"en\">\n"
       "<meta charset=\"utf-8\">\n"
       "<title>Not Acceptable</title>\n"
       "<h1>Not Acceptable</h1>\n"
       "<p>None of the representations of this resource matches the request."
       " These are available:\n"
       "<ul>\n"
       (apply str (map list-item alternates))
       "</ul>\n"))

(defn not-acceptable
  "The 406 response to a request whose `decision` has nothing acceptable,
  which lists the `alternates` in its body and its Link field.

  Only the alternates of the dimensions with nothing acceptable count,
  since the others keep the query parameter that failed."
  [{:keys [vary not-acceptable] :as decision} alternates]
  (let [failed?    #(some not-acceptable (keys %))
        alternates (cond->> alternates (seq not-acceptable) (filter failed?))
        vary       (vary-header nil vary)
        link       (link-header alternates)]
    {:status  406
     :headers (cond-> {"Content-Type" "text/html;charset=utf-8"}
                vary (assoc "Vary" vary)
                link (assoc "Link" link))
     :body    (not-acceptable-page alternates)}))
