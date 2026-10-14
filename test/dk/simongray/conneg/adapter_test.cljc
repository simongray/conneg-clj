(ns dk.simongray.conneg.adapter-test
  "The Ring middleware and the Pedestal interceptor, called as Ring and
  Pedestal call them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.pedestal :as conneg.pedestal]
            [dk.simongray.conneg.ring :as conneg.ring]))

(def negotiation
  "A negotiation with overrides in both dimensions."
  {:media-type {:offers  ["text/html" "text/turtle"]
                :query   "format"
                :aliases {"turtle" "text/turtle"}}
   :language   {:offers  ["da" "en"]
                :default "en"
                :cookie  "lang"}})

(defn handler
  "A Ring handler that answers with the decision in the `request`, and
  with a Vary header of its own."
  ([request]
   {:status  200
    :headers {"Content-Type" (get-in request [::conneg/decision :media-type])
              "vary"         ["Origin"]}
    :body    (::conneg/decision request)})
  ([request respond _]
   (respond (handler request))))

(defn- run-async
  "The response of the asynchronous Ring `handler` to the `request`."
  [handler request]
  (let [result (atom nil)]
    (handler request #(reset! result %) #(reset! result {:raised %}))
    @result))

(deftest ring
  (let [app     (conneg.ring/wrap-negotiate handler negotiation)
        request {:uri "/" :headers {"accept" "text/turtle" "accept-language" "da-DK"}}]
    (doseq [response [(app request) (run-async app request)]]
      (is (= {:status  200
              :headers {"Content-Type" "text/turtle"
                        "vary"         "Origin, Accept, Cookie, Accept-Language"}
              :body    {:media-type  "text/turtle"
                        :language    "da"
                        :preferences {:media-type "text/turtle" :language "da-DK"}
                        :vary        ["Accept" "Cookie" "Accept-Language"]}}
             response))))
  (testing "a 406 with the alternates"
    (let [app     (conneg.ring/wrap-negotiate handler negotiation)
          request {:uri "/x" :query-string "format=xml"}]
      (doseq [{:keys [status headers body]} [(app request) (run-async app request)]]
        (is (= 406 status))
        (is (= "Cookie, Accept-Language" (get headers "Vary")))
        (is (str/includes? body "<a href=\"/x?format=turtle\">text/turtle</a>")))))
  (testing "a 406 of your own"
    (let [app (conneg.ring/wrap-negotiate handler negotiation
                                          {:not-acceptable (fn [_ decision]
                                                             {:status 406
                                                              :body   decision})})]
      (is (= {:status  406
              :headers {"Vary" "Cookie, Accept-Language"}
              :body    {:language       "da"
                        :preferences    {:media-type "xml"}
                        :vary           ["Cookie" "Accept-Language"]
                        :not-acceptable #{:media-type}}}
             (app {:uri "/x" :query-string "format=xml"})))))
  (testing "no response stays no response"
    (is (nil? ((conneg.ring/wrap-negotiate (constantly nil) negotiation) {:uri "/"})))))

(defn- run-interceptor
  "The context after the `interceptor` and then the `handler` ran on the
  `request`, as Pedestal runs them: the handler only when the interceptor
  set no response."
  [interceptor handler request]
  (let [ctx ((:enter interceptor) {:request request})
        ctx (if (:response ctx)
              ctx
              (assoc ctx :response (handler (:request ctx))))]
    ((:leave interceptor) ctx)))

(deftest pedestal
  (let [interceptor (conneg.pedestal/negotiate negotiation)]
    (is (= ::conneg.pedestal/negotiate (:name interceptor)))
    (is (= {:status  200
            :headers {"Content-Type" "text/turtle"
                      "vary"         "Origin, Cookie, Accept-Language"}
            :body    {:media-type  "text/turtle"
                      :language    "en"
                      :preferences {:media-type "text/turtle" :language "en"}
                      :vary        ["Cookie" "Accept-Language"]}}
           (:response (run-interceptor interceptor handler
                                       {:uri          "/"
                                        :query-string "format=turtle"
                                        :headers      {"accept-language" "en"}}))))
    (testing "a 406 ends the chain"
      (let [{:keys [status headers]} (:response (run-interceptor
                                                 interceptor
                                                 #(throw (ex-info "Not called" %))
                                                 {:uri "/" :headers {"accept" "image/png"}}))]
        (is (= 406 status))
        (is (= "Accept, Cookie, Accept-Language" (get headers "Vary")))))
    (testing "a 406 of your own"
      (let [interceptor (conneg.pedestal/negotiate negotiation
                                                   {:not-acceptable (constantly {:status 406})})]
        (is (= {:status 406 :headers {"Vary" "Accept, Cookie, Accept-Language"}}
               (:response (run-interceptor interceptor
                                           handler
                                           {:uri "/" :headers {"accept" "image/png"}}))))))
    (testing "a request that lost its decision"
      (let [{:keys [leave]} (conneg.pedestal/negotiate negotiation {:link?             true
                                                                    :content-location? true})]
        (is (= {:status 200}
               (:response (leave {:request  {:request-method :get :uri "/"}
                                  :response {:status 200}}))))))))
