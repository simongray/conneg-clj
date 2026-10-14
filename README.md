conneg-clj
==========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/conneg-clj.svg)](https://clojars.org/dk.simongray/conneg-clj)

This library implements proactive content negotiation
([RFC 9110](https://www.rfc-editor.org/rfc/rfc9110#section-12)) in
Clojure and ClojureScript. Use it to pick a response's media type and
language from a request's Accept and Accept-Language headers, and to fill
in the matching Vary header.

- **Language fallback.** A browser that asks for da-DK gets your Danish
  page, even if you only have da. Safari sends a single language, so a
  lone da-DK is common.
- **Weights as RFC 9110 defines them.** The most specific matching range
  sets an offer's weight, so `text/*, text/html;q=0` rules out HTML but
  accepts other text. A weight of 0 always rules an offer out.
- **A precise Vary header.** A query parameter such as ?format=turtle or
  a cookie can override a header, and Vary names exactly the headers that
  counted.
- **Linked Data conventions.** Answer 303 See Other for things that
  aren't documents, and name a document's representations in Link and
  Content-Location headers, as in
  [Cool URIs for the Semantic Web](https://www.w3.org/TR/cooluris/).
- **No header breaks it.** A malformed element is left out, and no
  function throws on anything a client sends.
- **The same results everywhere.** One codebase runs on the JVM, in Node
  and in browsers. It passes
  [jshttp/negotiator](https://github.com/jshttp/negotiator)'s test suite
  for Accept and Accept-Language.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.11+ and Java 11+. Add the latest release from
[Clojars](https://clojars.org/dk.simongray/conneg-clj) to the `:deps` in
your `deps.edn`:

```clojure
dk.simongray/conneg-clj {:mvn/version "0.1.0"}
```

For unreleased changes, use the SHA of the latest commit on `master`
instead. In shadow-cljs, a Git dependency only works through `deps.edn`,
so set `:deps true` in your `shadow-cljs.edn` too.

Media types
-----------

To pick a response's media type, pass the Accept header and your offers,
in order of preference, to `conneg/media-type`:

```clojure
(require '[dk.simongray.conneg :as conneg])

(conneg/media-type "application/json;q=0.2, text/*;q=0.9"
                   ["text/html" "application/json" "text/turtle"])
;; => "text/html"

(conneg/media-type "text/*, text/html;q=0" ["text/html" "text/turtle"])
;; => "text/turtle"
```

When nothing is acceptable, e.g. for `image/*`, it returns nil, and you
can answer 406 or fall back to a default of your own. Without an Accept
header, the client has no preference, so your first offer wins.

At the same weight, the more specific range wins, then the range that the
client lists first, then the offer that comes first. So `*/*` gets your
first offer. A range with parameters only matches offers with the same
parameter values, e.g. `text/html;level=1` doesn't match `text/html`.

To pick something other than a string, pass a function that returns the
media type of each item:

```clojure
(conneg/media-type "text/turtle, */*;q=0.1"
                   :type
                   [{:type "text/html" :name :page}
                    {:type "text/turtle" :name :rdf}])
;; => {:type "text/turtle" :name :rdf}
```

To parse an Accept header into media ranges, use `conneg/media-ranges`:

```clojure
(conneg/media-ranges "text/*;q=0.3, text/plain;format=flowed")
;; => [{:type "text" :subtype "plain" :parameters {"format" "flowed"}
;;      :weight 1.0}
;;     {:type "text" :subtype "*" :weight 0.3}]
```

Languages
---------

Languages work the same way, with `conneg/language`:

```clojure
(conneg/language "da-DK, en;q=0.8" ["da" "en"])
;; => "da"

(conneg/language "*;q=0.5, da;q=0" ["da" "en"])
;; => "en"
```

A range matches every tag that starts with it, so en matches en-GB. A tag
that no range matches can still match a longer range by dropping the
range's last subtags, as RFC 4647's lookup does: da-DK falls back to da,
and zh-Hant-TW to zh-Hant and then zh. The range `*` only covers tags
that no other range matches.

The function also takes a coll of ranges, e.g. `js/navigator.languages`
in a browser. For other ways to match language tags, see
[langtag-clj](https://github.com/simongray/langtag-clj).

Negotiating a request
---------------------

To negotiate a whole Ring request, pass `conneg/negotiate` what you offer
in each dimension (media type and language) and the request. Pedestal
requests are Ring requests, so they work too:

```clojure
(def negotiation
  {:media-type {:offers  ["text/html" "application/ld+json" "text/turtle"]
                :query   "format"
                :aliases {"html"    "text/html"
                          "json-ld" "application/ld+json"
                          "turtle"  "text/turtle"}}
   :language   {:offers  ["da" "en"]
                :default "en"
                :query   "lang"
                :cookie  "lang"}})

(conneg/negotiate negotiation
                  {:uri     "/word/1"
                   :headers {"accept"          "text/turtle, */*;q=0.1"
                             "accept-language" "da-DK"}})
;; => {:media-type "text/turtle"
;;     :language   "da"
;;     :vary       ["Accept" "Cookie" "Accept-Language"]}
```

A query parameter such as ?format=turtle or ?lang=da-DK overrides the
header, and so does a cookie when there's no query parameter. Each can
hold an alias or the same kind of value as the header, e.g. turtle or
text/turtle. For a cookie in a format of your own, make :cookie a map of
its :name and a :read function that parses the value.

The decision's :vary lists the headers that the choice depended on, even
missing ones, so put it in your response's Vary header. A query parameter
is part of the URL, so it never goes in Vary. When a cookie decides, the
header that it overrides doesn't go in either. To add headers of your
own, e.g. Origin, give the negotiation a :vary. To merge Vary values, use
`conneg/vary-header`.

When nothing is acceptable in a dimension without a :default, the
decision lists that dimension under :not-acceptable. A query parameter
that matches no offer, e.g. ?format=xml, counts as nothing acceptable,
but a cookie that matches no offer counts as missing. Answer with
`conneg/not-acceptable`, which lists the alternatives:

```clojure
(let [request  {:uri "/word/1" :query-string "format=xml"}
      decision (conneg/negotiate negotiation request)]
  (conneg/not-acceptable decision (conneg/alternates negotiation request)))
;; => {:status  406
;;     :headers {"Content-Type" "text/html;charset=utf-8"
;;               "Vary"         "Cookie, Accept-Language"
;;               "Link"         "</word/1?format=html>; rel=\"alternate\"; ..."}
;;     :body    "<!DOCTYPE html>..."}
```

The alternates are your offers, each with a URL when its dimension has a
query parameter. Here the page and the Link header only list media types,
since the language was acceptable.

Ring and Pedestal
-----------------

The Ring middleware negotiates each request before your handler gets it:

```clojure
(require '[dk.simongray.conneg.ring :as conneg.ring])

(defn handler
  "Answer with the negotiated language, in the negotiated media type."
  [{::conneg/keys [decision]}]
  {:status  200
   :headers {"Content-Type" (:media-type decision)}
   :body    (str "Language: " (:language decision))})

(def app
  (conneg.ring/wrap-negotiate handler negotiation))

(app {:uri     "/word/1"
      :headers {"accept" "text/turtle" "accept-language" "da"}})
;; => {:status  200
;;     :headers {"Content-Type" "text/turtle"
;;               "Vary"         "Accept, Cookie, Accept-Language"}
;;     :body    "Language: da"}
```

The decision is in the request, under :dk.simongray.conneg/decision.
When nothing is acceptable, the middleware returns a 406 without calling
your handler. It adds the decision's :vary to the Vary header of every
response, keeping any that your handler sets. It works with both
synchronous and asynchronous handlers.

For Pedestal, put the interceptor in a route:

```clojure
(require '[dk.simongray.conneg.pedestal :as conneg.pedestal])

(def routes
  #{["/word/:id" :get [(conneg.pedestal/negotiate negotiation) word-handler]
     :route-name ::word]})
```

On enter, the interceptor puts the decision in the request, or sets a 406
response that ends the chain. On leave, it adds to the Vary header. The
interceptor is a map, so the library doesn't depend on Pedestal. Outside
a route, pass it to `io.pedestal.interceptor/interceptor` first.

Both take a :not-acceptable option: a function of the request and the
decision that returns your own response when nothing is acceptable.

Linked Data
-----------

In [Cool URIs for the Semantic Web](https://www.w3.org/TR/cooluris/), a
request for a thing that isn't a document, e.g. a person, gets a 303 See
Other to a document about it. To answer that way, use `conneg/see-other`,
which adds the decision's Vary, since the location may depend on it:

```clojure
(def thing
  (conneg.ring/wrap-negotiate
   (fn [{::conneg/keys [decision]}]
     (conneg/see-other (if (= "text/html" (:media-type decision))
                         "/people/alice"
                         "/data/alice")
                       decision))
   {:media-type {:offers ["text/html" "application/rdf+xml"]}}))

(thing {:uri "/id/alice" :headers {"accept" "application/rdf+xml"}})
;; => {:status  303
;;     :headers {"Location"     "/data/alice"
;;               "Content-Type" "text/html;charset=utf-8"
;;               "Vary"         "Accept"}
;;     :body    "<!DOCTYPE html>..."}
```

The W3C's rule behind this, the httpRange-14 resolution of 2005, still
stands, though many publishers answer such URIs with a 200 instead.

For the documents themselves, the middleware and the interceptor take
two more options:

- :link?, for a Link header to each other representation, as Cool URIs
  advises in section 4.6
- :content-location?, for the URL of the chosen representation, as in
  section 4.2. The URL has to pin down each dimension, since a GET on it
  must return the same representation.

```clojure
(def app
  (conneg.ring/wrap-negotiate handler negotiation {:link?             true
                                                   :content-location? true}))

(:headers (app {:request-method :get
                :uri            "/word/1"
                :headers        {"accept" "text/turtle" "accept-language" "da"}}))
;; => {"Content-Type"     "text/turtle"
;;     "Vary"             "Accept, Cookie, Accept-Language"
;;     "Link"             "</word/1?format=html>; rel=\"alternate\"; ..."
;;     "Content-Location" "/word/1?format=turtle&lang=da"}
```

Only successful responses to GET and HEAD get these headers. To build
them yourself, use `conneg/alternates`, `conneg/link-header` and
`conneg/content-location`.

By default, the library builds these URLs by setting query parameters on
the request's URL, so each dimension needs a :query. For URLs of your
own, e.g. a file for each representation as in Cool URIs, give the
negotiation an :href: a function of the request and a map of dimension to
offer that returns the URL of that representation, or nil. For an
alternate, the map holds one dimension, and for Content-Location it holds
all of them:

```clojure
(def extensions
  {"text/html" "html" "application/rdf+xml" "rdf"})

(defn file-href
  "The URL of the file for the `request` with the `offers`, e.g.
  /doc/alice.en.html."
  [{:keys [uri] :as request} {:keys [media-type language] :as offers}]
  (str uri
       (when language (str "." language))
       (when media-type (str "." (extensions media-type)))))

(conneg/alternates {:media-type {:offers ["text/html" "application/rdf+xml"]}
                    :href       file-href}
                   {:uri "/doc/alice"})
;; => [{:media-type "text/html" :href "/doc/alice.html"}
;;     {:media-type "application/rdf+xml" :href "/doc/alice.rdf"}]
```

To change only some of the default URLs, call `conneg/query-href` from
your :href, and return nil for a representation that you don't want to
list. The middleware and the interceptor pass :href the request with the
decision in it, e.g. to leave out the language for RDF, which is the same
in every language.

Choices that the standard leaves open
-------------------------------------

- **Languages fall back.** HTTP originally matched languages by basic
  filtering
  ([RFC 4647, section 3.3.1](https://www.rfc-editor.org/rfc/rfc4647#section-3.3.1)),
  which never matches da-DK to da. RFC 9110 suggests that browsers help
  their users add da, but Safari sends only one language, so the library
  also falls back as lookup does (section 3.4). A fallback counts for less
  than a range that names the tag, but more than `*`.
- **Ties.** RFC 9110 calls offers of the same weight equally preferred.
  The library breaks the tie as jshttp/negotiator does: by the more
  specific range, then the client's order, then yours.
- **Malformed headers.** The library skips malformed elements, e.g.
  `text/html;q=abc` or `text/html;q=1.5`. When nothing in a header
  parses, the header counts as missing. An empty header accepts nothing.

Other Clojure libraries
-----------------------

Here is what each library picks from `["text/html" "application/json"
"text/turtle"]` for Accept and from `["da" "en"]` for Accept-Language
(with juxt/pick at commit dabc8c4, liberator 0.15.3,
ring-middleware-accept 2.0.3 and Pedestal 0.8.2). Wrong results are in
bold:

| Header | conneg-clj | juxt/pick | liberator | ring-middleware-accept | Pedestal |
|---|---|---|---|---|---|
| `application/json;q=0.2, text/*;q=0.9` | text/html | text/html | text/html | text/turtle | **application/json** |
| `text/html;q=0` | nothing | nothing | **text/html** | nothing | **text/html** |
| `text/*, text/html;q=0` | text/turtle | text/turtle | **text/html** | text/turtle | **text/html** |
| `TEXT/HTML` | text/html | text/html | **nothing** | **nothing** | **nothing** |
| `*/*` | text/html | text/html | text/html | text/turtle | text/html |
| `@@@,,,;;;` | text/html | text/html | nothing | nothing | **throws** |
| Accept-Language `da-DK` | da | **nothing** | **nothing** | da | n/a |
| `*;q=0.5, da;q=0` | en | **da** | **nothing** | en | n/a |
| `da;q=abc` | da | **throws** | da | da | n/a |

Where offers tie, e.g. for `*/*`, ring-middleware-accept doesn't follow
your order of preference. RFC 9110 allows that, so it isn't in bold.

Pedestal only negotiates media types, and muuntaja only matches exact
types. None of these libraries runs in ClojureScript.

Not yet
-------

- Accept-Encoding and Accept-Charset. RFC 9110 deprecates Accept-Charset,
  and a reverse proxy usually takes care of compression.
- Regional siblings, e.g. en-GB for a client that asks for en-US. These
  need CLDR's language data.
- A server-side quality for each offer, like Apache's qs.

Principles
----------

- **Standards first.** The code follows RFC 9110 and RFC 4647 and cites
  their sections. The tests include RFC 9110's examples and
  jshttp/negotiator's test suite.
- **Plain data.** Offers are strings, or items of your own with a
  function that returns the media type or tag of each. Media ranges are
  maps.
- **One codebase.** The library is written in `.cljc` and depends on
  [langtag-clj](https://github.com/simongray/langtag-clj) for language
  ranges, [whatwg-mime](https://github.com/simongray/whatwg-mime) for
  media types and [whatwg-url](https://github.com/simongray/whatwg-url)
  for query strings.

Development
-----------

```bash
clojure -X:test                                       # the tests on the JVM
clojure -X:test:pedestal                              # also in Pedestal's chain
npm install                                           # once, for the Node tests
clojure -M:cljs compile test                          # the tests in Node
clojure -M:bench -m dk.simongray.conneg.bench         # benchmarks on the JVM
clojure -M:cljs release bench && node target/bench.js # benchmarks in Node
dev/negotiator.sh                                     # records negotiator's tests again
```

The :pedestal alias also runs the interceptor in Pedestal 0.8's chain,
which needs Java 17. The jshttp/negotiator cases come from its test suite
at a pinned version, which `dev/negotiator.sh` runs and records. They
keep negotiator's MIT notice.

The public namespaces are the root, `ring` and `pedestal`. The internal
ones are under `impl`:

- `impl.syntax`, RFC 9110's lists and weights
- `impl.media`, media ranges and how they match
- `impl.language`, how language ranges match tags
- `impl.ranking`, how the acceptable offers rank
- `impl.request`, a request's query parameters and cookies
- `impl.negotiation`, the decision for a request and its alternates
- `impl.response`, Vary, Link and the 303 and 406 responses
- `impl.adapter`, what the middleware and the interceptor share

License
-------

The conneg-clj project is licensed under the [MIT licence](LICENSE).
