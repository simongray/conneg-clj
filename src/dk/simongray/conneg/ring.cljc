(ns dk.simongray.conneg.ring
  "Ring middleware that negotiates each request before the handler gets
  it."
  (:require [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.impl.adapter :as adapter]))

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
  - :link?, to add a Link to each other representation to a successful
    response to GET or HEAD, with rel=\"alternate\"
  - :content-location?, to add the URL of the chosen representation to a
    successful response to GET or HEAD, when a query parameter or the
    :href of the negotiation pins down each dimension

  It takes a synchronous or an asynchronous handler."
  ([handler negotiation]
   (wrap-negotiate handler negotiation {}))
  ([handler negotiation opts]
   (let [refuse    (adapter/refusal negotiation opts)
         negotiate (fn [request]
                     (let [decision (conneg/negotiate negotiation request)]
                       [(assoc request ::conneg/decision decision) decision]))
         finish    (partial adapter/finish negotiation opts)]
     (fn
       ([request]
        (let [[request decision] (negotiate request)]
          (finish request decision (if (:not-acceptable decision)
                                     (refuse request decision)
                                     (handler request)))))
       ([request respond raise]
        (let [[request decision] (negotiate request)
              respond #(respond (finish request decision %))]
          (if (:not-acceptable decision)
            (respond (refuse request decision))
            (handler request respond raise))))))))
