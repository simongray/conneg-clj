(ns dk.simongray.conneg.bench
  "Benchmarks of negotiation with the headers of browsers and API clients,
  on the JVM by criterium and in Node by the clock, and flame graphs of
  the JVM by clj-async-profiler.

      clojure -M:bench -m dk.simongray.conneg.bench
      clojure -M:cljs release bench && node target/bench.js

  In a REPL with the :bench alias, (run) gives the mean of each case in
  microseconds, and (profile :media-type 10) the file of a flame graph."
  (:require #?@(:clj [[clj-async-profiler.core :as prof]
                      [criterium.core :as criterium]])
            [dk.simongray.conneg :as conneg]))

(def accept
  "The Accept of Firefox for a page."
  "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

(def accept-language
  "The Accept-Language of Chrome set to Danish, then English."
  "da-DK,da;q=0.9,en-US;q=0.8,en;q=0.7")

(def media-types
  "The media types that DanNet offers."
  ["text/html" "text/turtle" "application/edn" "application/ld+json"
   "application/sparql-results+json" "application/json"
   "application/transit+json"])

(def cases
  "Each case by name, as a function to time."
  {:media-ranges      #(conneg/media-ranges accept)
   :media-type        #(conneg/media-type accept media-types)
   :media-type-api    #(conneg/media-type "application/ld+json" media-types)
   :media-type-absent #(conneg/media-type nil media-types)
   :language          #(conneg/language accept-language ["da" "en"])
   :language-safari   #(conneg/language "da-DK" ["da" "en"])})

#?(:cljs
   (defn- mean
     "The mean time of a call of `f` in seconds, over a second of calls
     after a second of warming up."
     [f]
     (let [now   #(js/performance.now)
           calls (fn [ms]
                   (let [end (+ (now) ms)]
                     (loop [n 0]
                       (if (< (now) end)
                         (do (dotimes [_ 100] (f)) (recur (+ n 100)))
                         n))))]
       (calls 1000)
       (let [start (now)
             n     (calls 1000)]
         (/ (- (now) start) n 1000)))))

(defn run
  "The mean time of each case, or of those named `ks`, in microseconds."
  ([]
   (run (keys cases)))
  ([ks]
   (into (sorted-map)
         (for [k    ks
               :let [mean #?(:clj  (first (:mean (criterium/quick-benchmark* (cases k) {})))
                             :cljs (mean (cases k)))]]
           [k (/ (Math/round (* mean 1e9)) 1000.0)]))))

#?(:clj
   (defn profile
     "The file of a flame graph of the case `k`, run for `seconds` by
     clj-async-profiler."
     [k seconds]
     (let [f   (cases k)
           end (+ (System/currentTimeMillis) (* 1000 seconds))]
       (prof/profile (while (< (System/currentTimeMillis) end) (f))))))

(defn -main
  "Print the mean time of each case, or of those named in `args`."
  [& args]
  (doseq [[k us] (if (seq args) (run (map keyword args)) (run))]
    (println (str (name k) (apply str (repeat (- 18 (count (name k))) " ")) us " µs")))
  #?(:clj (shutdown-agents)))

(comment
  (run)
  (run [:media-type :language])
  (profile :media-type 10)
  #_.)
