(ns user
  (:require [integrant.core :as ig]
            [cheshire.core :as cheshire]))

(defmethod ig/init-key ::api-keys [_ {:keys [api-keys-path]}]
  (-> api-keys-path
      slurp
      (cheshire/decode keyword)))

(def system-config
  {::api-keys {:api-keys-path "../config/api-keys.json"}})

(defonce system-context (atom nil))

(defn restart-context! []
  (when @system-context
    (ig/halt! @system-context))
  (reset! system-context (ig/init system-config)))

(defn current-system-context []
  (let [context @system-context]
    (merge (::api-keys context)
           context)))

(comment
  (do
    (restart-context!)
    nil))
