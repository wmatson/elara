(ns elara.tools.core
  (:require
   [clojure.edn :as edn]
   [malli.core :as malli]
   [malli.edn :as malli-edn]
   [malli.error :as malli-error]
   [medley.core :as medley]
   [taoensso.telemere.timbre :as timbre]))

(def tool-schema
  [:map
   [:name keyword?]
   [:fn [:=> [:cat [:map] [:map]] :any]]
   [:schema [:any {:description "A Malli schema for the parameters of the tool, a map should be the root"}]]])

(defn ->tools
  [& tool-defs]
  (when-not (every? #(malli/validate tool-schema %) tool-defs)
    (throw (ex-info "Invalid tool definitions"
                    {:explanations (map (comp malli-error/humanize (partial malli/explain tool-schema)) tool-defs)})))
  (medley/index-by :name tool-defs))

(defn ->prompt [tools]
  (if tools
    (str "You have access to the following tools (using Malli Schema): "
         (medley/map-vals #(-> %
                               (select-keys [:name :description :schema])
                               (update :schema (comp edn/read-string malli-edn/write-string)))
                          tools))
    (str "You do not have access to any tools.")))


(defn- safe-use-tool
  "Returns the results of using the tool in a vector, or an error message for the LLM to see as a string"
  [system-context tool-def parameters]
  (if (malli/validate (:schema tool-def) parameters)
    (try
      [((:fn tool-def) system-context parameters)]
      (catch Exception e
        (timbre/info "Error using tool"
                     {:tool-def tool-def
                      :parameters parameters
                      :error (.getMessage e)})
        (str "An exception (" (type e) ") occurred while using the tool: "
             (.getMessage e)
             (when-let [data (ex-data e)]
               (str "\nData: " (pr-str data))))))
    (str "The parameters " (pr-str parameters) " do not match the schema for tool " (:name tool-def)
         "\nExplanation: " (malli-error/humanize (malli/explain (:schema tool-def) parameters)))))

(defn- use-tool [system-context tools tool-name parameters]
  (timbre/debug "Using tool"
                {:tool-name tool-name
                 :parameters parameters})
  (if (seq tools)
    (if-let [tool-def (get tools tool-name)]
      (let [result (safe-use-tool system-context tool-def parameters)]
        (if (vector? result)
          (do
            (timbre/debug "Tool use successful"
                          {:tool-name tool-name
                           :parameters parameters
                           :result (first result)})
            (str "You used the tool " tool-name " with the following parameters: " (pr-str parameters)
                 "\n The tool returned the following result: " (pr-str (first result))))
          result))
      (str "You do not have access to the tool " tool-name ". The valid tool names are: " (keys tools)))
    (str "You do not have access to any tools. Please continue thinking.")))


(defn ->tool-user [system-context tools]
  (partial use-tool system-context tools))

(comment

  (malli/schema tool-schema)

  (->tools
   {:name :id-generator
    :fn (fn [_ _] "foo")
    :schema [:map [:id string?]]}))