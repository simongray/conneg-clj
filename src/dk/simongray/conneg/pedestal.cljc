(ns dk.simongray.conneg.pedestal
  "A Pedestal interceptor that negotiates each request.

  The interceptor is a map, so the library doesn't depend on Pedestal. A
  route takes it as it is. Elsewhere, e.g. among the interceptors of a
  service, pass it to io.pedestal.interceptor/interceptor first."
  (:require [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.adapter :as adapter]))

(defn negotiate
  "A Pedestal interceptor that negotiates the request by the `negotiation`,
  with the `opts` below.

  On enter, it puts the decision of conneg/negotiate in the request, under
  :dk.simongray.conneg/decision. When nothing is acceptable, it sets the
  response to a 406, which ends the chain. On leave, it adds the fields
  that the decision looked at to the Vary header of the response.

  The `opts` are those of dk.simongray.conneg.ring/wrap-negotiate:
  :not-acceptable, :link? and :content-location?."
  ([negotiation]
   (negotiate negotiation {}))
  ([negotiation opts]
   (let [refuse (adapter/refusal negotiation opts)]
     {:name  ::negotiate
      :enter (fn [{:keys [request] :as ctx}]
               (let [decision (conneg/negotiate negotiation request)
                     request  (assoc request ::conneg/decision decision)]
                 (cond-> (assoc ctx :request request)
                   (:not-acceptable decision)
                   (assoc :response (refuse request decision)))))
      :leave (fn [{:keys [request response] :as ctx}]
               (cond-> ctx
                 response
                 (assoc :response (adapter/finish negotiation
                                                  opts
                                                  request
                                                  (::conneg/decision request)
                                                  response))))})))
