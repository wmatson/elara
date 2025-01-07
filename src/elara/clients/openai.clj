(ns elara.clients.openai
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [medley.core :as medley]
            [taoensso.telemere :as telemere]
            [taoensso.tufte :as tufte :refer [defnp]]))

(defn- parse-body [content response-format]
  (let [json-parsed (cheshire/decode content keyword)
        schema-frame (:schema (:json_schema response-format))
        lift-body? (and (= "object" (:type json-parsed))
                        (or (= (:name json-parsed) (:name schema-frame))
                            (not (get-in schema-frame [:properties :type])))
                        (:properties json-parsed))]
    (telemere/log! {:level :debug
                    :data {:lift-body? lift-body?
                           :json-parsed-keys (keys json-parsed)
                           :response-format response-format}}
                   "parsing body")
    ;; 2024-08-27 Sometimes ChatGPT returns the structured data including the schema frame
    (if lift-body?
      (:properties json-parsed)
      json-parsed)))

(def ^:private groq-models #{"llama-3.3-70b-versatile"})

(defnp llm-request [system-context {:keys [model messages max-tokens
                                           response-format include-full-resp]
                                    :or {model "gpt-4o-2024-08-06"
                                         max-tokens 100}}]
  (let [base-url (if (groq-models model)
                   "https://api.groq.com/openai/v1/chat/completions"
                   "https://api.openai.com/v1/chat/completions")
        api-key (if (groq-models model)
                  (:GROQ_API_KEY system-context)
                  (:OPENAI_API_KEY system-context))
        resp (telemere/trace!
              {:id ::request
               :telemere/sensitive-data #{api-key}}
              (:body
               (http/request
                {:url base-url
                 :method :post
                 :content-type :json
                 :as :json
                 :headers {"Authorization" (str "Bearer " api-key)}
                 :body (cheshire/encode (medley/assoc-some
                                         {:model model
                                          :messages messages
                                          :max_completion_tokens max-tokens}
                                         :response_format response-format))})))]
    (medley/assoc-some
     {:parsed-body (cond-> (get-in resp [:choices 0 :message :content])
                     response-format (parse-body response-format))}
     :full-resp (when include-full-resp
                  resp))))

(comment
  (llm-request (#'user/current-system-context)
               {:messages [{:role :user :content "What is the capital of France?"}]
                :model "llama-3.3-70b-versatile"})

  (llm-request (#'user/current-system-context)
               {:messages [{:role :user :content "What is the capital of France?"}]
                :model "gpt-4o-mini"
                :max-tokens 15}))