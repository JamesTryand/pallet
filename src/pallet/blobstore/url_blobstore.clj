(ns pallet.blobstore.url-blobstore
  "A url based blobstore implementation."
  (:require
   [pallet.blobstore :as blobstore]))

(defrecord UrlBlobstore
    [base-url]
  pallet.blobstore/Blobstore
  (sign-blob-request
   [blobstore container path request-map]
   {:endpoint (format "%s/%s/%s" base-url container path)
    :headers nil}))

(defmethod blobstore/service :url-blobstore
  [provider & {:keys [base-url]
               :or {base-url "http://localhost"}}]
  (UrlBlobstore. base-url))
