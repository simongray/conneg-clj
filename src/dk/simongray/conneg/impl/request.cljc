(ns ^:no-doc dk.simongray.conneg.impl.request
  "What a negotiation reads from a Ring request besides the Accept fields:
  query parameters and cookies."
  (:require [clojure.string :as str]
            [dk.simongray.whatwg-url.params :as params]
            [dk.simongray.whatwg-url.percent :as percent]))

;; URL Standard, section 5.1, rather than the :query-params of a
;; middleware, whose decoding differs between Ring and Pedestal
(defn query-param
  "The value of the first query parameter `name` of the Ring `request`, or
  nil."
  [{:keys [query-string] :as request} name]
  (some-> query-string params/parse (params/get name)))

(defn- unquote-value
  "The cookie value `s` without the double quotes around it, if any."
  [s]
  (if (and (< 1 (count s))
           (str/starts-with? s "\"")
           (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

;; RFC 6265, section 5.4, with the value decoded as Ring's cookies
;; middleware decodes it: + as a space, and then percent-decoded
(defn header-cookie
  "The value of the cookie `name` in the Cookie field value `s`, or nil."
  [s name]
  (some (fn [pair]
          (when-let [eq (str/index-of pair "=")]
            (when (= name (str/trim (subs pair 0 eq)))
              (-> (subs pair (inc eq))
                  str/trim
                  unquote-value
                  (str/replace "+" " ")
                  percent/decode))))
        (str/split s #";")))

(defn cookie
  "The value of the cookie `name` of the Ring `request`, from its :cookies
  when a middleware has parsed them, or else from its Cookie field."
  [{:keys [cookies headers] :as request} name]
  (if cookies
    (get-in cookies [name :value])
    (some-> (get headers "cookie") (header-cookie name))))
