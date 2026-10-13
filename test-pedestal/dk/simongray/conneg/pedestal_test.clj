(ns dk.simongray.conneg.pedestal-test
  "The interceptor in the interceptor chain of Pedestal 0.8, run with the
  alias :pedestal."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.pedestal :as conneg.pedestal]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]))

;; as io.pedestal.http.response/terminate-when-response, which Pedestal's
;; servlet and http-kit adapters add to the chain
(defn- response?
  "Whether the context `ctx` has a response."
  [{:keys [response] :as ctx}]
  (and (map? response) (pos-int? (:status response))))

(def handler
  "An interceptor that answers with the decision in the request."
  {:name  ::handler
   :enter (fn [{:keys [request] :as ctx}]
            (assoc ctx :response {:status  200
                                  :headers {"Vary" "Origin"}
                                  :body    (::conneg/decision request)}))})

(defn- run
  "The response of the chain of `interceptors` to the `request`."
  [request & interceptors]
  (-> {:request request}
      (chain/terminate-when response?)
      (chain/enqueue (map interceptor/interceptor interceptors))
      chain/execute
      :response))

(def negotiation
  "A negotiation of media types."
  {:media-type {:offers ["text/html" "text/turtle"] :query "format"}})

(deftest chain
  (is (= {:status  200
          :headers {"Vary" "Origin, Accept"}
          :body    {:media-type "text/turtle" :vary ["Accept"]}}
         (run {:headers {"accept" "text/turtle"}}
              (conneg.pedestal/negotiate negotiation)
              handler)))
  (testing "a 406 ends the chain before the handler"
    (let [{:keys [status headers]} (run {:uri "/" :query-string "format=xml"}
                                        (conneg.pedestal/negotiate negotiation)
                                        handler)]
      (is (= 406 status))
      (is (= (str "</?format=text%2Fhtml>; rel=\"alternate\"; type=\"text/html\", "
                  "</?format=text%2Fturtle>; rel=\"alternate\"; type=\"text/turtle\"")
             (get headers "Link")))
      (is (nil? (get headers "Vary"))))))
