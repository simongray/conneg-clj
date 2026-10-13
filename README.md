conneg-clj
==========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/conneg-clj.svg)](https://clojars.org/dk.simongray/conneg-clj)

This library implements proactive content negotiation by
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110#section-12) in Clojure
and ClojureScript. Use it to pick the media type and the language of a
response from the Accept and Accept-Language headers of a request, with
the Vary header that goes with them.

- **Languages that fall back.** A browser that asks for da-DK gets your
  Danish page, though you only have da. Safari sends a single language,
  so a lone da-DK is common.
- **Weights as RFC 9110 has them.** The most specific range decides, so
  `text/*, text/html;q=0` rules out HTML but accepts other text. A weight
  of 0 always rules an offer out.
- **Vary from what the choice looked at.** A query parameter such as
  ?format=turtle or a cookie can override a header, and the Vary header
  names exactly the headers that counted.
- **The conventions of Linked Data.** Answer 303 See Other for things
  that aren't documents, and name the representations of a document in
  Link and Content-Location headers, as in
  [Cool URIs for the Semantic Web](https://www.w3.org/TR/cooluris/).
- **No header breaks it.** A malformed element is left out, and no
  function throws on anything that a client sends.
- **The same results everywhere.** One codebase runs on the JVM, in Node
  and in browsers. It passes the tests of
  [jshttp/negotiator](https://github.com/jshttp/negotiator) for Accept and
  Accept-Language.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.11+ and Java 11+. Add the latest release from
[Clojars](https://clojars.org/dk.simongray/conneg-clj) to the `:deps` of
your `deps.edn`:

```clojure
dk.simongray/conneg-clj {:mvn/version "0.1.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead. In shadow-cljs a Git dependency only works through
`deps.edn`, so set `:deps true` in your `shadow-cljs.edn` too.

Media types
-----------

Pick the media type of a response with `conneg/media-type`, from the
Accept header and your offers in order of preference:

```clojure
(require '[dk.simongray.conneg :as conneg])

(conneg/media-type "application/json;q=0.2, text/*;q=0.9"
                   ["text/html" "application/json" "text/turtle"])
;; => "text/html"

(conneg/media-type "text/*, text/html;q=0" ["text/html" "text/turtle"])
;; => "text/turtle"
```

When nothing is acceptable, e.g. for `image/*`, it returns nil, and you
choose between a 406 and a default of your own. Without an Accept header,
the client has no preference and your first offer wins.

At the same weight, the more specific range wins, then the range that the
client lists first, then the offer that comes first. So `*/*` gets your
first offer. A range with parameters only matches an offer with the same
values of them, e.g. `text/html;level=1` doesn't match `text/html`.

To pick something other than a string, pass a function that returns the
media type of each item:

```clojure
(conneg/media-type "text/turtle, */*;q=0.1" :type [{:type "text/html" :name :page}
                                                   {:type "text/turtle" :name :rdf}])
;; => {:type "text/turtle" :name :rdf}
```

To see the media ranges of a header as data, use `conneg/media-ranges`:

```clojure
(conneg/media-ranges "text/*;q=0.3, text/plain;format=flowed")
;; => [{:type "text" :subtype "plain" :parameters {"format" "flowed"} :weight 1.0}
;;     {:type "text" :subtype "*" :weight 0.3}]
```

Languages
---------

Pick the language of a response with `conneg/language`, in the same way:

```clojure
(conneg/language "da-DK, en;q=0.8" ["da" "en"])
;; => "da"

(conneg/language "*;q=0.5, da;q=0" ["da" "en"])
;; => "en"
```

A range matches the tags that start with it, so en matches en-GB. A tag
that no range matches can still match by falling back, as in lookup: the
range da-DK reaches da, and zh-Hant-TW reaches zh-Hant and then zh. The
range `*` only covers the tags that no other range matches.

The function also takes a coll of ranges, e.g. `js/navigator.languages`
in a browser. For other ways to match language tags, see
[langtag-clj](https://github.com/simongray/langtag-clj).

Negotiating a request
---------------------

For a whole Ring request, say what you offer in each dimension and pass
the request to `conneg/negotiate`. Pedestal's requests work too, since
they are Ring requests:

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

(conneg/negotiate negotiation {:uri     "/word/1"
                               :headers {"accept"          "text/turtle, */*;q=0.1"
                                         "accept-language" "da-DK"}})
;; => {:media-type "text/turtle"
;;     :language   "da"
;;     :vary       ["Accept" "Cookie" "Accept-Language"]}
```

A query parameter overrides the header, e.g. ?format=turtle or
?lang=da-DK, and a cookie does so when no query parameter is there. Both
hold an alias or what the header would hold. A cookie can be a map of its
:name and a :read function, for a value in a format of your own.

The :vary of the decision lists the headers that the choice looked at,
also when they were missing, so put it in the Vary header of your
response. A query parameter is part of the URL, so it never goes in Vary,
and once the cookie decides, the header that it overrides doesn't either.
The :vary of the negotiation adds headers of your own, e.g. Origin. To
merge a Vary value with others, use `conneg/vary-header`.

When nothing is acceptable in a dimension with no :default, the decision
lists the dimension under :not-acceptable. A query parameter that matches
no offer has that effect too, e.g. ?format=xml, while a cookie that
matches no offer counts as missing. Answer with `conneg/not-acceptable`,
which lists the alternatives:

```clojure
(let [request  {:uri "/word/1" :query-string "format=xml"}
      decision (conneg/negotiate negotiation request)]
  (conneg/not-acceptable decision (conneg/alternates negotiation request)))
;; => {:status  406
;;     :headers {"Content-Type" "text/html;charset=utf-8"
;;               "Vary"         "Cookie, Accept-Language"
;;               "Link"         "</word/1?format=html>; rel=\"alternate\"; type=\"text/html\", ..."}
;;     :body    "<!DOCTYPE html>..."}
```

The alternates are your offers, with the URL of each where its dimension
has a query parameter. The page and the Link header list the media types
here, since the language was fine.

Ring and Pedestal
-----------------

The Ring middleware negotiates each request before your handler gets it:

```clojure
(require '[dk.simongray.conneg.ring :as conneg.ring])

(defn handler
  "Answer with the language that the client gets, in the media type that
  it gets."
  [{::conneg/keys [decision]}]
  {:status  200
   :headers {"Content-Type" (:media-type decision)}
   :body    (str "Language: " (:language decision))})

(def app
  (conneg.ring/wrap-negotiate handler negotiation))

(app {:uri "/word/1" :headers {"accept" "text/turtle" "accept-language" "da"}})
;; => {:status  200
;;     :headers {"Content-Type" "text/turtle"
;;               "Vary"         "Accept, Cookie, Accept-Language"}
;;     :body    "Language: da"}
```

The handler finds the decision in the request, under
:dk.simongray.conneg/decision. When nothing is acceptable, the middleware
answers 406 itself. It adds the headers that the decision looked at to the
Vary header of every response, next to any that the handler sets. It
takes synchronous and asynchronous handlers.

For Pedestal, put the interceptor in a route:

```clojure
(require '[dk.simongray.conneg.pedestal :as conneg.pedestal])

(def routes
  #{["/word/:id" :get [(conneg.pedestal/negotiate negotiation) word-handler]
     :route-name ::word]})
```

On enter, it puts the decision in the request, or it sets a 406 response,
which ends the chain. On leave, it adds to the Vary header. The
interceptor is a map, so the library doesn't depend on Pedestal. Outside
a route, pass it to `io.pedestal.interceptor/interceptor` first.

Both take an option, :not-acceptable: a function of the request and the
decision that returns a response of your own when nothing is acceptable.

Linked Data
-----------

In [Cool URIs for the Semantic Web](https://www.w3.org/TR/cooluris/), a
thing that isn't a document, e.g. a person, has a URI that answers 303 See
Other with the URL of a document about it. To answer that way, use
`conneg/see-other`, which adds the Vary of the decision when the document
depends on it:

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
- :content-location?, for the URL of the representation that the request
  got, as in section 4.2. The URL must pin down each dimension, since a
  GET on it must return the same representation.

```clojure
(def app
  (conneg.ring/wrap-negotiate handler negotiation {:link?             true
                                                   :content-location? true}))

(:headers (app {:uri "/word/1" :headers {"accept" "text/turtle" "accept-language" "da"}}))
;; => {"Content-Type"     "text/turtle"
;;     "Vary"             "Accept, Cookie, Accept-Language"
;;     "Link"             "</word/1?format=html>; rel=\"alternate\"; type=\"text/html\", ..."
;;     "Content-Location" "/word/1?format=turtle&lang=da"}
```

Only a successful response gets them. The functions behind them are
`conneg/alternates`, `conneg/link-header` and `conneg/content-location`.

By default, these URLs set the query parameters of the request, so they
need a query parameter in each dimension. For URLs of your own, e.g. a
file for each representation as in Cool URIs, give the negotiation an
:href. It's a function of the request and a map of dimension to offer,
which returns the URL of that representation or nil. The alternates ask
for one dimension at a time, and Content-Location for all of them:

```clojure
(def extensions
  {"text/html" "html" "application/rdf+xml" "rdf"})

(defn file-href
  "The URL of the file of the representation of the `request` with the
  `offers`, e.g. /doc/alice.en.html."
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

Choices that the standard leaves open
-------------------------------------

- **Languages fall back.** HTTP's own scheme was basic filtering
  ([RFC 4647, section 3.3.1](https://www.rfc-editor.org/rfc/rfc4647#section-3.3.1)),
  which never matches da-DK to da. RFC 9110 suggests that browsers help
  their users add da, but Safari sends only one language. So the library also
  falls back as lookup does (section 3.4). A fallback counts for less
  than a range that names the tag, and more than `*`.
- **Ties.** RFC 9110 calls offers of the same weight equally preferred.
  The library breaks the tie as jshttp/negotiator does: by the more
  specific range, then the client's order, then yours.
- **Malformed headers.** A malformed element is left out, e.g. `q=abc`
  or `q=1.5`. When nothing in a header parses, the header counts as
  missing. An empty header accepts nothing.

Other Clojure libraries
-----------------------

Here is what each library picks from `["text/html" "application/json"
"text/turtle"]` for Accept and from `["da" "en"]` for Accept-Language
(with juxt/pick at commit dabc8c4, liberator 0.15.3,
ring-middleware-accept 2.0.3 and Pedestal 0.8.2). Whatever is wrong is in
bold:

| Header | conneg-clj | juxt/pick | liberator | ring-middleware-accept | Pedestal |
|---|---|---|---|---|---|
| `application/json;q=0.2, text/*;q=0.9` | text/html | text/html | text/html | **text/turtle** | **json** |
| `text/html;q=0` | nothing | nothing | **text/html** | nothing | **text/html** |
| `text/*, text/html;q=0` | text/turtle | text/turtle | **text/html** | text/turtle | **text/html** |
| `TEXT/HTML` | text/html | text/html | **nothing** | **nothing** | **nothing** |
| `*/*` | text/html | text/html | text/html | **text/turtle** | text/html |
| `@@@,,,;;;` | text/html | text/html | nothing | nothing | **throws** |
| Accept-Language `da-DK` | da | **nothing** | **nothing** | da | n/a |
| `*;q=0.5, da;q=0` | en | **da** | **nothing** | en | n/a |
| `da;q=abc` | da | **throws** | da | da | n/a |

Pedestal's negotiation is for media types only, and muuntaja's only
matches types exactly. None of them runs in ClojureScript.

Not yet
-------

- Accept-Encoding and Accept-Charset. RFC 9110 deprecates Accept-Charset,
  and a reverse proxy usually takes care of compression.
- Regional siblings, e.g. en-GB for a client that asks for en-US. These
  need the language data of CLDR.
- A quality of the server's own for each offer, as Apache has it.

Principles
----------

- **Standards first.** The code follows RFC 9110 and RFC 4647 and cites
  their sections. The tests include the examples of both RFCs and the
  test suite of jshttp/negotiator.
- **Plain data.** Offers are strings, or items of your own with a
  function that returns the media type or tag of each. Media ranges are
  maps.
- **One codebase.** The library is written in `.cljc` and depends on
  [langtag-clj](https://github.com/simongray/langtag-clj) for language
  ranges and [whatwg-mime](https://github.com/simongray/whatwg-mime) for
  media types.

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

The alias :pedestal also runs the interceptor in the chain of Pedestal
0.8, which needs Java 17. The cases of jshttp/negotiator come from its
own test suite at a pinned version, which `dev/negotiator.sh` runs and
records. They keep its MIT notice.

The public namespaces are the root, `ring` and `pedestal`. The internal
ones are under `impl`:

- `impl.syntax`, the lists and weights of RFC 9110
- `impl.media`, media ranges and how they match
- `impl.language`, how language ranges match tags
- `impl.ranking`, how the acceptable offers rank
- `impl.request`, the query parameters and cookies of a request
- `impl.negotiation`, the decision for a request and its alternates
- `impl.response`, Vary, Link and the 303 and 406 responses
- `impl.adapter`, what the middleware and the interceptor share

License
-------

The conneg-clj project is licensed under the [MIT licence](LICENSE).
