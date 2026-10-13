(ns dk.simongray.conneg.ring
  "Ring middleware that negotiates each request before the handler gets
  it."
  (:require [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.response :as response]))

(defn not-acceptable
  "The 406 response to the Ring `request` of the `decision` by the
  `negotiation`, which lists the alternates of the request."
  [negotiation request decision]
  (conneg/not-acceptable decision (conneg/alternates negotiation request)))

(defn wrap-negotiate
  "Ring middleware that negotiates each request by the `negotiation` before
  the `handler` gets it, with the `opts` below.

  The handler finds the decision of conneg/negotiate in the request, under
  :dk.simongray.conneg/decision. When nothing is acceptable, the
  middleware answers 406 itself. It adds the fields that the decision
  looked at to the Vary header of every response.

  The `opts` are:

  - :not-acceptable, a function of the request and the decision that
    returns the response when nothing is acceptable, e.g. a 406 of your
    own. By default it's conneg/not-acceptable with the alternates of the
    request.

  It takes a synchronous or an asynchronous handler."
  ([handler negotiation]
   (wrap-negotiate handler negotiation {}))
  ([handler negotiation opts]
   (let [refuse    (or (:not-acceptable opts)
                       (partial not-acceptable negotiation))
         negotiate (fn [request]
                     (let [decision (conneg/negotiate negotiation request)]
                       [(assoc request ::conneg/decision decision) decision]))]
     (fn
       ([request]
        (let [[request decision] (negotiate request)]
          (response/with-vary (if (:not-acceptable decision)
                                (refuse request decision)
                                (handler request))
                              (:vary decision))))
       ([request respond raise]
        (let [[request {:keys [vary] :as decision}] (negotiate request)
              respond #(respond (response/with-vary % vary))]
          (if (:not-acceptable decision)
            (respond (refuse request decision))
            (handler request respond raise))))))))
