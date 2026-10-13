(ns ^:no-doc dk.simongray.conneg.impl.adapter
  "What the Ring middleware and the Pedestal interceptor share: the answer
  when nothing is acceptable, and the headers of a response."
  (:require [dk.simongray.conneg.impl.negotiation :as negotiation]
            [dk.simongray.conneg.impl.response :as response]))

(defn refusal
  "The function of a request and its decision that answers when nothing is
  acceptable: the :not-acceptable of the `opts`, or else a 406 that lists
  the alternates by the `negotiation`."
  [negotiation opts]
  (or (:not-acceptable opts)
      (fn [request decision]
        (->> (negotiation/alternates negotiation request)
             (response/not-acceptable decision)))))

(defn- success?
  "Whether the Ring `response` is a success, 2xx."
  [{:keys [status] :as response}]
  (and (number? status) (<= 200 status 299)))

;; RFC 8288, section 3, and RFC 9110, section 8.7, which only give Link and
;; Content-Location their meaning here for a success
(defn finish
  "The Ring `response` to the `request` of the `decision` by the
  `negotiation`, with the fields of the decision added to its Vary header,
  and with the Link and Content-Location that the `opts` ask for.

  The options are :link?, for a Link to each other representation, and
  :content-location?, for the URL of this one. Only a success gets them."
  [negotiation opts request decision response]
  (let [ok? (success? response)]
    (cond-> (response/with-vary response (:vary decision))
      (and ok? (:link? opts))
      (response/with-link (-> (negotiation/alternates negotiation request)
                              (negotiation/others decision)
                              response/link-header))

      (and ok? (:content-location? opts))
      (response/with-default "Content-Location"
                             (negotiation/content-location negotiation
                                                           request
                                                           decision)))))
