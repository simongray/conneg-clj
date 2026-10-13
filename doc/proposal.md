conneg-clj: a proposal
======================

A Clojure(Script) library for proactive content negotiation by
[RFC 9110, section 12](https://www.rfc-editor.org/rfc/rfc9110#section-12).
It picks the media type and language of a response from the Accept fields
of a request. It also works out the Vary header for that
choice. When nothing is acceptable, it answers 406 with a list of the
alternatives. It follows langtag-clj and whatwg-mime and builds on both: one
cljc codebase, plain data in and out, the sections of the specs cited in the
code, and no exception on any header that a client can send.

The examples use the namespace `dk.simongray.conneg` with the alias
`conneg`. The decisions that followed the proposal are at the end.

These findings shape the design:

- **Most of the parsing exists already.** The langtag-clj library parses
  Accept-Language and implements the matching schemes of RFC 4647. The
  parser in whatwg-mime already reads a media range such as `text/*;q=0.5`,
  since `*` is a token character. The new library adds HTTP's matching
  rules, Vary, overrides and the responses.
- **No RFC defines the language matching that HTTP servers need.** RFC 9110
  leaves the scheme open. Basic filtering, HTTP's old scheme, doesn't match
  da-DK to da. Lookup does, but it skips `*` and never matches en to en-GB.
  The library combines the two, as Apache and jshttp/negotiator do in
  their own ways. Safari always sends a single language, so a lone
  regional tag such as da-DK is common.
- **Every Clojure library gets basic cases wrong, and none runs in
  ClojureScript.** juxt/pick comes closest, but it throws on `da;q=abc` and
  on empty list elements. muuntaja and Pedestal throw on other headers that
  a client can send.
- **Pedestal 0.7.2 and 0.8.2 negotiate the same way.** Their
  content_negotiation.clj files are identical, so the upgrade in progress
  doesn't touch this.
- **The W3C never settled 303 or 200 for things that aren't documents.**
  The httpRange-14 resolution of 2005 still stands. The proposals to let a
  200 do the job stopped at an unfinished draft in 2013. So the library
  offers a helper for 303 but doesn't push anyone towards it. DanNet
  answers its synset IRIs with 200 today.


What the specs say
------------------

These are the rules the library implements, from RFC 9110 unless another
spec is named:

- **No field means no preference** on that dimension (12.4.1).
- **Weights** run from 0 to 1 with at most three decimals. A weight of 0
  means "not acceptable", and the default is 1 (12.4.2). A recipient
  should take any parameter named q as the weight, wherever it is
  (12.5.1). The accept-ext grammar of RFC 7231 is gone.
- **Without a wildcard, anything not named is unacceptable** (12.4.3).
- **The most specific range that matches a media type sets its weight**
  (12.5.1). So `text/*, text/html;q=0` rules out HTML but accepts other
  text.
- **Accept-Language takes RFC 4647 ranges**, and the matching scheme is
  the implementation's choice (12.5.4). RFC 4647, section 3, then requires
  the implementation to say which scheme it uses.
- **Accept-Charset is deprecated** (12.5.2).
- **When nothing matches, a server either sends 406 or ignores the
  field**, and it can choose differently for each field (12.4.1). A 406
  should list the alternatives with their URLs (15.5.7). No format is
  defined, but RFC 9110 mentions Link with rel="alternate" for such a list
  (15.4.1).
- **Vary names the request fields that might have influenced the
  choice**, including fields that were absent (12.5.5). A cache reuses a
  response only for a request with the same values in those fields, and
  an absent field only matches an absent field (RFC 9111, 4.1). A 304
  must repeat the Vary of the 200 (15.4.5).
- **Recipients must ignore empty list elements** (5.6.1.2). Field names,
  parameter names, media types and language tags all ignore case.
- **Content-Location on a negotiated 200 names that representation**, and
  a GET on it would return the same representation (8.7).
- **A 303 to a GET says that the server has no representation of the
  thing**, and the Location is a document about it. The body ought to be
  a short note with a link (15.4.4).
- **A media type in a Link header must be quoted**, since it isn't a token
  (RFC 8288, appendix C).


Other Clojure libraries
-----------------------

I ran each library on the same headers. The offers are `["text/html"
"application/json" "text/turtle"]` for Accept and `["da" "en"]` for
Accept-Language. Results that are wrong are in bold:

| Header | Expected | juxt/pick | liberator | ring-middleware-accept | muuntaja | Pedestal |
|---|---|---|---|---|---|---|
| `application/json;q=0.2, text/*;q=0.9` | text/html | text/html | text/html | **text/turtle** | **json** | **json** |
| `text/html;q=0` | nothing | nothing | **text/html** | nothing | **text/html** | **text/html** |
| `text/*, text/html;q=0` | text/turtle | text/turtle | **text/html** | text/turtle | **text/html** | **text/html** |
| `TEXT/HTML` | text/html | text/html | **406** | **nothing** | text/html (default) | **406** |
| `*/*` | text/html | text/html | text/html | **text/turtle** | text/html (default) | text/html |
| `application/json;q=abc` | no error | nothing | json | json | json | **throws** |
| `@@@,,,;;;` | no error | text/html | 406 | nothing | **throws** | **throws** |
| Accept-Language `da-DK` | da | **nothing** | **nothing** | da | n/a | n/a |
| `*;q=0.5, da;q=0` | en | **da** | **nothing** | en | n/a | n/a |
| `da;q=abc` | no error | **throws** | da | da | n/a | n/a |

I ran muuntaja with text/html as its default format. Without one, `*/*`
and `TEXT/HTML` throw too.

In short:

- **juxt/pick** is the most complete. It handles media ranges and their
  precedence correctly and computes Vary from the representations. But its
  parser, reap, throws on a partial parse, so `da;q=abc`, a trailing space
  and empty list elements (which RFC 9110 requires recipients to accept)
  all raise an exception. A `*` in Accept-Language revives languages ruled
  out with q=0. It's git-only and has had no commits since January 2024.
- **liberator** ignores q=0 for media types and has no notion of
  specificity. Ties go to the last element of the header.
- **ring-middleware-accept** scores well (weight, then specificity), but
  ties go to the last offer, so `*/*` picks the server's last choice. It
  compares media types case-sensitively.
- **muuntaja** only matches exact types: no wildcards, no q=0. A header of
  `;` throws a NullPointerException through its middleware.
- **Pedestal** adds q to a score of 110 for a matching type and subtype,
  so specificity swamps q and q=0 never excludes anything. It throws on
  `q=abc` and on a parameter without `=`. Its interceptor sets no Vary.

None of them runs in ClojureScript. Only pick and liberator set Vary, and
liberator's leaves out Accept-Language when the request has none. The
harnesses are in the scratch directory, one per library.


Other ecosystems
----------------

- **jshttp/negotiator** (JavaScript, MIT) is the closest model. Each
  offer takes the weight of the most specific range that matches it. It
  ranks offers by weight, then by the specificity of that range, then by
  the client's order and finally by the server's. Its 253 tests extract to 377
  data tuples. They port to EDN with a few flips where its choices differ:
  it keeps `q=1.5`, accepts `*/html`, compares every parameter value
  without case and allows identity only when Accept-Encoding is missing.
- **Go's language.Matcher** uses CLDR data to rank en-GB near en-AU and
  to match nb to da. That needs tables the library won't carry. Its test
  data depends on them, so it doesn't port.
- **Apache's mod_negotiation** has server-side quality (qs) and gives a
  regional range one level of fallback (en-US to en) at q=0.001. It
  computes Vary from the dimensions in which the variants differ, and its
  406 lists the variants as links.
- **werkzeug and Django** show what goes wrong. In werkzeug, the fallback
  passes ignore q=0, so `en-US;q=0, en` still picks en-US. Django drops
  q=0 ranges before matching, so `text/*, text/plain;q=0` accepts
  text/plain. Its locale middleware lets a cookie override Accept-Language
  but doesn't add Cookie to Vary.
- **Rails** ignores any Accept that lists `*/*` next to other types,
  treating it as a browser's. That heuristic stays out.


Scope
-----

**In:**

- Accept and Accept-Language, each with a function that picks one offer
  from a field value
- `negotiate`, which takes what you offer and a Ring request and returns
  the choice in each dimension with the fields for Vary
- overrides by a query parameter (e.g. ?format=turtle) and by a cookie,
  with aliases, checked against the offers
- responses: 406 with the alternatives, 303 See Other, Link with
  rel="alternate", and merging a Vary value
- Ring middleware and a Pedestal interceptor, in cljc. Neither needs Ring
  or Pedestal as a dependency, since middleware is a function and an
  interceptor is a map.

Pedestal's requests are Ring requests, so `negotiate` serves both.

**Left out:**

- Accept-Encoding, for now, since a reverse proxy usually takes care of
  compression. RFC 9110's rules for identity (12.5.3) would make a
  picker of it worth having later.
- Accept-Charset, which RFC 9110 deprecates and browsers no longer send.
- Variants that combine dimensions, as in Apache's type maps, and
  server-side quality (qs). Offers could later become maps with a :qs
  without breaking offers given as strings.
- Regional siblings, e.g. en-GB for a client that asks for en-US. They
  need CLDR data to do well.
- W3C Content Negotiation by Profile (a draft whose header hasn't reached
  the IETF), the transparent negotiation of RFC 2295, and 300 Multiple
  Choices.
- ETags for each variant. A strong ETag must differ between variants, so
  the README will say so, but the ETag is up to you.
- Choosing which RDF literal to show (DanNet's `select-label`).

**Readings where the specs leave a choice:**

- **Languages match by basic filtering, with lookup's fallback.** A tag
  takes the weight of the most specific range that matches it, as in
  RFC 2616, 14.4. The range `*` matches only tags that no other range
  matches. A tag that no range matches takes the weight of a range that
  falls back to it by lookup (RFC 4647, 3.4), e.g. da from da-DK. A
  fallback loses to a range that names the tag, beats `*`, and never
  comes from a range of weight 0.
- **Ties go to the more specific range, then the client's order, then
  yours.** For media types that is negotiator's order, so `text/*,
  text/html` picks HTML and `*/*` picks your first offer. For languages,
  `*` ranks below any named range. Among offers that the same range
  matches, the tag itself beats a longer tag, which beats a fallback.
- **Media type parameters must all be in the offer.** Their names ignore
  case, and so do charset values. Other values compare exactly. A range
  without parameters matches every offer of its type, as in RFC 7231's
  table.
- **A malformed element is skipped**, e.g. `q=abc`, `q=1.5`, `q=.2` or
  `*/html`. A field in which nothing parses counts as absent. An empty
  field counts as a list of nothing, so nothing is acceptable. This
  agrees with negotiator and Django, and with RFC 9110's explicit rule for
  an empty Accept-Encoding. Older JDKs sent `*; q=.2, */*; q=.2` by
  default, which a strict reading drops. JDK 19 fixed that, with
  backports to 11 and 17.
- **An absent field picks your first offer.**
- **When nothing is acceptable, the decision says so**, unless the
  dimension has a default, e.g. en for languages.
- **Vary names the fields that the decision looked at, including absent
  ones.** A query parameter is part of the URL, so it never goes in Vary.
  When a cookie decides, the field it overrides isn't consulted and isn't
  listed.
- **An unknown override depends on where it comes from.** A query
  parameter is an explicit request, so an unknown value means nothing is
  acceptable, e.g. ?format=xml gives a 406. A cookie holds a saved
  preference, so an unknown value is skipped and the field decides.

Here is the language rule on DanNet's cases. All 65 of negotiator's
Accept-Language tuples agree with it too (checked with a prototype):

| Accept-Language | Offers `["da" "en"]` | Why |
|---|---|---|
| `da-DK` | da | fallback |
| `da-DK, en;q=0.8` | da | the fallback has weight 1 |
| `EN` | en | tags ignore case |
| `da;q=0` | nothing | |
| `*;q=0.5, da;q=0` | en | `*` covers the others |
| `da-DK, da;q=0` | nothing | a named range beats a fallback |
| `da-DK;q=0.2, *;q=0.9` | en | a fallback beats `*` |
| `da-DK, en` | da | the client's order |
| `en, da-DK` | en | the client's order |


API
---

### One field, one choice

```clojure
(require '[dk.simongray.conneg :as conneg])

(conneg/media-type "application/json;q=0.2, text/*;q=0.9"
                   ["text/html" "application/json" "text/turtle"])
;; => "text/html"

(conneg/language "da-DK, en;q=0.8" ["da" "en"])
;; => "da"
```

Each function returns the offer as you gave it, or nil when nothing is
acceptable. The offers are in your order of preference. As in
langtag-clj, a function that returns the media type or tag of each item
lets you pick items rather than strings, e.g. `(conneg/media-type accept
:type renderers)`. In a browser, `(conneg/language (vec
js/navigator.languages) ["da" "en"])` works too, since a coll of ranges is
a priority list.

To see the preferences as data, use `conneg/media-ranges`, or langtag's
`priority-list` for languages:

```clojure
(conneg/media-ranges "text/*;q=0.3, text/plain;format=flowed")
;; => [{:type "text" :subtype "plain" :parameters {"format" "flowed"}
;;      :weight 1.0}
;;     {:type "text" :subtype "*" :weight 0.3}]
```

A media range is a map as `mime/parse` returns it, plus :weight, in order
of weight.

### A request

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
                  {:uri     "/synset/1"
                   :headers {"accept"          "text/turtle, */*;q=0.1"
                             "accept-language" "da-DK"}})
;; => {:media-type "text/turtle"
;;     :language   "da"
;;     :vary       ["Accept" "Cookie" "Accept-Language"]}

(conneg/negotiate negotiation
                  {:uri          "/synset/1"
                   :query-string "format=xml"
                   :headers      {"cookie" "lang=en"}})
;; => {:language       "en"
;;     :vary           ["Cookie"]
;;     :not-acceptable #{:media-type}}
```

The second request asks for a format that isn't offered, so Accept isn't
consulted. The cookie decides the language, so Accept-Language isn't
either.

Each dimension (:media-type and :language) takes:

- :offers, in your order of preference
- :default, the offer to use when nothing is acceptable. Without one, the
  decision lists the dimension under :not-acceptable.
- :query, a query parameter that overrides the field
- :cookie, a cookie that overrides the field, or a map of its :name and a
  :read function for a value in a format of your own
- :aliases, short names for offers in the query parameter or the cookie

The top level takes :vary, the other request fields that your response
depends on, e.g. Origin for CORS headers.

A query parameter or cookie takes what the field takes (a media type or a
language priority list) or an alias. The `negotiate` function reads the
query parameters from :query-string, so it doesn't depend on another
middleware's decoding. It reads cookies from Ring's :cookies when a
middleware has parsed them, or else from the Cookie header.

### Responses

```clojure
(def alternates
  (conneg/alternates negotiation request))
;; => [{:media-type "text/html"           :href "/synset/1?format=html"}
;;     {:media-type "application/ld+json" :href "/synset/1?format=json-ld"}
;;     {:media-type "text/turtle"         :href "/synset/1?format=turtle"}
;;     {:language "da" :href "/synset/1?lang=da"}
;;     {:language "en" :href "/synset/1?lang=en"}]

(conneg/link-header alternates)
;; => "</synset/1?format=html>; rel=\"alternate\"; type=\"text/html\", ..."

(conneg/not-acceptable decision alternates)
;; => {:status  406
;;     :headers {"Content-Type" "text/html;charset=utf-8"
;;               "Vary"         "Cookie"
;;               "Link"         "</synset/1?format=html>; ..."}
;;     :body    "<!DOCTYPE html>..."}

(conneg/see-other "/doc/alice" decision)
;; => {:status  303
;;     :headers {"Location" "/doc/alice" "Vary" "Accept"}
;;     :body    "<!DOCTYPE html>..."}

(conneg/vary-header "Origin" ["Accept" "Cookie"])
;; => "Origin, Accept, Cookie"
```

The alternates come from the dimensions that have a query parameter: the
URL of the request with that parameter set to an alias of each offer, or
to the offer itself. Other query parameters stay. The 406 lists them as
links in a small HTML page and in a Link header. The 303 body is the short
note with a link that RFC 9110 asks for. The `vary-header` function merges
field names without case, and returns `*` alone when either side has it.

### Ring and Pedestal

```clojure
(require '[dk.simongray.conneg.ring :as conneg.ring])

(def app
  (conneg.ring/wrap-negotiate handler negotiation))
```

The middleware puts the decision in the request under
`::conneg/decision`. When nothing is acceptable, it answers 406 itself.
It adds Vary to every response, merged with any Vary that the handler
sets. It takes synchronous and asynchronous handlers.

```clojure
(require '[dk.simongray.conneg.pedestal :as conneg.pedestal])

(def negotiate-ic
  (conneg.pedestal/negotiate negotiation))
```

The interceptor does the same. On :enter, it puts the decision in the
request, or sets a 406 response, which ends the chain as it does for
Pedestal's own negotiate-content. On :leave, it adds Vary.

Both take these options:

- :not-acceptable, a function of the request and the decision that
  returns the response, for a 406 of your own
- :link?, to add the alternates in a Link header to successful responses
- :content-location?, to add the URL of the chosen representation, when
  every negotiated dimension has a query parameter so that the URL pins
  it down

The adapters leave Content-Type and Content-Language to the handler,
which writes the body.


Errors
------

No function throws on a field, query parameter or cookie. A malformed
element is skipped, and nil means that nothing is acceptable. An offer
that doesn't parse matches nothing.

The decision says which dimensions have nothing acceptable, rather than
failing.


Tests
-----

- **The RFCs:** the examples of RFC 9110, 12.5.1 (the table as corrected
  by erratum 7138), 12.5.3 and 12.5.4, and RFC 7231's table with the level
  parameter
- **negotiator's suite** as EDN, with its MIT notice and a comment at each
  case that the library answers differently
- **Cool URIs for the Semantic Web:** the HTTP exchanges of sections 2.1,
  4.2 and 4.3, with the errata, for the alternates, Content-Location and
  303
- **DanNet's bugs** and the table of other Clojure libraries above
- **Generative tests:** no field value throws, the result is always one of
  the offers or nil, an offer of weight 0 never wins, and whitespace and
  case never change a result. The Vary of a decision names each field it
  consulted.

All of them run on the JVM and in Node.


Performance
-----------

A request parses two short fields, so a negotiation should take a few
microseconds. A bench like langtag-clj's times typical browser headers
against DanNet's seven media types. muuntaja memoizes by the raw field
value. That stays out unless the bench shows a need.


DanNet
------

This is how the negotiation in resources.clj could be replaced once the
Pedestal upgrade has landed. The fix for `?lang` is on master already
(ab732a7).

```clojure
(def negotiation
  {:media-type {:offers  ["text/html"
                          "text/turtle"
                          "application/edn"
                          "application/ld+json"
                          "application/sparql-results+json"
                          "application/json"
                          "application/transit+json"]
                :query   "format"
                :aliases {"html"    "text/html"
                          "turtle"  "text/turtle"
                          "ttl"     "text/turtle"
                          "edn"     "application/edn"
                          "json-ld" "application/ld+json"
                          "json"    "application/json"
                          "transit" "application/transit+json"}}
   :language   {:offers  i18n/supported-languages
                :default "en"
                :query   "lang"
                :aliases {"danish" "da" "english" "en"}
                :cookie  {:name "languages"
                          :read (comp first edn/read-string)}}
   ;; pages read other cookies too, and CORS in development reads Origin
   :vary       (cond-> ["Cookie"] shared/development? (conj "Origin"))})

(def negotiate-ic
  (conneg.pedestal/negotiate negotiation))
```

- **The interceptor replaces** content-negotiation-ic,
  language-negotiation-ic and explicit-params-ic in each route, and the
  Vary line of response-body-ic. The autocomplete route gets a negotiation
  of its own, with media types only.
- **The offers keep today's order**, which comes from the keys of
  `content-type->body-fn`. A vector settles the TODO about that order in
  response.clj.
- **The code that reads the choice changes in a few places.** For
  example, `(get-in request [:accept :field])` becomes `(get-in request
  [::conneg/decision :media-type])`, and `request->languages` calls
  `i18n/lang-prefs` on the decision's language. The cookie holds the same
  vector that `lang-prefs` returns, so nothing changes for users there.
- **rate_limit.clj and `error-content-type`** call `conneg/media-type` on
  the Accept header instead of looking for "text/html" in it. So
  `text/html;q=0` no longer counts as a request for HTML.
- **Error responses get no Vary from the interceptor**, since Pedestal
  skips its :leave after an exception. `error-ic` can add one with
  `conneg/vary-header` from the decision in the request.
- **The redirect for the single-page app stays.** `redirect-ic` reads
  the media type from the decision. It could use `conneg/see-other` for
  its 303s, but the Transit response stays a 200 with an X-Redirect
  header.

What changes for users:

- `?format=xml` returns a 406 that lists the formats, instead of a 500.
- Accept-Language works for da-DK, EN and q=0.
- Vary leaves out Accept when ?format= decides, and Accept-Language when
  ?lang= or the cookie decides. That is what a cache should see.
- A request without any Accept gets the first offer, HTML. Today it gets
  JSON, since Pedestal sets no :accept and response-body-ic falls back to
  JSON. A request with `Accept: */*`, as curl sends, gets HTML both
  before and after.
- `?lang=` with a language other than da or en gives English. Today any
  well-formed range passes through, e.g. ?lang=de asks for German labels
  where the data has them.


Changes to langtag-clj and whatwg-mime
--------------------------------------

- **langtag-clj:** make `fallbacks` public. It's the shortening step of
  lookup (RFC 4647, 3.4), and the language rule needs it for each range.
  Everything else it needs is public already: `priority-list` and
  `basic-match?`.
- **whatwg-mime:** make `split-values` public. It is Fetch's "get, decode,
  and split", which splits a field value at commas outside quoted strings.
  Accept needs it, since a parameter can hold a quoted comma. Each range
  then goes to `mime/parse`.


Size
----

About 600 lines of source, a little less than langtag-clj. Most of it is
the matching of the two fields and `negotiate`. The responses and the two
adapters are short.


Decisions
---------

After the proposal, on 13 October 2026:

- **The name is conneg-clj**, as `dk.simongray/conneg-clj` with the
  namespace `dk.simongray.conneg`.
- **Accept-Encoding is out for now.**
- **The Linked Data responses are in the first version,** as the last
  step of the build: alternates, Link, 303 and Content-Location.
- **langtag-clj and whatwg-mime change upstream.** Until `fallbacks` and
  `split-values` are public in a release, conneg-clj works around them,
  with a TODO at each place.
- **DanNet's two changes for users wait for the integration:** HTML for a
  request without Accept, and ?lang= with other languages than da and en.
