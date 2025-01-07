(ns elara.tools.toy
  (:require [taoensso.tufte :as tufte :refer [fnp]]))

(def id-generator
  {:name :toy/id-generator
   :fn (fnp ->id [_system-context {:keys [id-type]}]
            (str id-type "-" (random-uuid)))
   :schema [:map [:id-type string?]]})

(def wait
  {:name :wait
   :fn (fnp wait [_system-context {:keys [millis]}]
            (Thread/sleep millis))
   :schema [:map [:millis int?]]})

(defn ->always-fail [message data]
  {:name :toy/always-fail
   :fn (fn always-fail [_system-context _parameters]
         (throw (ex-info message data)))
   :schema [:map]})

(comment
  )
