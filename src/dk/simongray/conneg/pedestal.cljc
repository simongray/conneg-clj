(ns dk.simongray.conneg.pedestal
  "A Pedestal interceptor that negotiates each request.

  The interceptor is a map, so the library doesn't depend on Pedestal. A
  route takes it as it is. Elsewhere, e.g. among the interceptors of a
  service, pass it to io.pedestal.interceptor/interceptor first."
  (:require [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.response :as response]
            [dk.simongray.conneg.ring :as ring]))

(defn negotiate
  "A Pedestal interceptor that negotiates the request by the `negotiation`,
  with the `opts` below.

  On enter, it puts the decision of conneg/negotiate in the request, under
  :dk.simongray.conneg/decision. When nothing is acceptable, it sets the
  response to a 406, which ends the chain. On leave, it adds the fields
  that the decision looked at to the Vary header of the response.

  The `opts` are:

  - :not-acceptable, a function of the request and the decision that
    returns the response when nothing is acceptable, e.g. a 406 of your
    own. By default it's conneg/not-acceptable with the alternates of the
    request."
  ([negotiation]
   (negotiate negotiation {}))
  ([negotiation opts]
   (let [refuse (or (:not-acceptable opts)
                    (partial ring/not-acceptable negotiation))]
     {:name  ::negotiate
      :enter (fn [{:keys [request] :as ctx}]
               (let [decision (conneg/negotiate negotiation request)
                     request  (assoc request ::conneg/decision decision)]
                 (cond-> (assoc ctx :request request)
                   (:not-acceptable decision)
                   (assoc :response (refuse request decision)))))
      :leave (fn [{:keys [request response] :as ctx}]
               (let [vary (get-in request [::conneg/decision :vary])]
                 (cond-> ctx
                   response (assoc :response (response/with-vary response vary)))))})))
