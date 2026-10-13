(ns dk.simongray.conneg.negotiator-test
  "The test suite of jshttp/negotiator for Accept and Accept-Language, as
  dev/negotiator.sh records it."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.conneg.impl.language :as language]
            [dk.simongray.conneg.impl.media :as media]
            [dk.simongray.conneg.negotiator-cases :as negotiator]))

(defn- ranked
  "The offers of the negotiator `call` that the library accepts, best
  first."
  [{:keys [field value offers] :as call}]
  (if (= "accept" field)
    (media/ranked value identity offers)
    (language/ranked value identity offers)))

(deftest negotiator-suite
  (doseq [{:keys [value method offers result] :as call} negotiator/cases]
    (let [ranked (ranked call)]
      (is (= result (if (#{"mediaType" "language"} method)
                      (first ranked)
                      ranked))
          (str method " " (pr-str value) " " (pr-str offers))))))
