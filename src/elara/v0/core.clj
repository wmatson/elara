(ns elara.v0.core
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string]
   [elara.tools.core :as tools]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.generator :as mg]
   [taoensso.telemere :as telemere]))

(defn base-system-prompt-slice [{:keys [final-output-schema tools]}]
  (str "You are an autonomous agent that interacts with a situated program to complete a task.
        The situated program will provide you with information and respond to your messages, but it requires a certain format to work.
       
        # Action Format
        This format is an EDN map with the following keys:
        :action - one of :final-answer, :use-tool, :continue-thinking
        :data - a map of any data you need to use to complete your task
               
        ## Final Answer
        Your task should have a final output, when you have it, set :action to :final-answer and set :data to your final answer.
        Otherwise, set :action to :continue-thinking and leave :data empty.\n"
       (if final-output-schema
         (str "\n\nYour final answer must match the following malli schema:\n\n" (pr-str final-output-schema)
              "\n\nFor example, it could look like this (data generated from the schema, missing semantic meanings):\n\n"
              (mg/generate final-output-schema))
         "If your final answer is a string, it should be under the :result key of the data map.")
       "\n\n## Thinking
       When you need to think, set :action to :continue-thinking and include your current thoughts in the :data map under the :thoughts key. 
               
       ## Tool Usage
       If you need to use a tool to complete your task, set :action to :use-tool and set :data to a map with the following keys:
       :tool-name - the name of the tool you need to use
       :parameters - an EDN map of parameters to pass to the tool"
       (tools/->prompt tools)))

(defn roal-goal-backstory->prompt-slice [{:keys [role goal backstory]}]
  (str "\n\n# Personality
        In order to complete your task, you should act in a certain way. 
        To that end, you are given a role, a goal, and a backstory. 
        These can inform your thoughts and actions, but remember that you are still interacting through a situated program.
        \n\n## Role\n" role
       "\n\n## Goal\n" goal
       "\n\n## Backstory\n" backstory))

(defn slices->prompt [slices]
  (clojure.string/join "\n\n----\n\n" slices))

(defn ->ollama-llm-request [{:keys [base-url model max-tokens]
                             :or {base-url "http://localhost:11434"
                                  model "gemma3:12b"
                                  max-tokens -1}}]
  (fn ollama-llm-request! [{:keys [messages]}]
    (-> (http/request {:url (str base-url "/api/chat")
                       :method :post
                       :as :json
                       :accept :json
                       :body (cheshire/encode {:messages messages
                                               :stream false
                                               :model model
                                               :options {:num_predict max-tokens}})})
        :body
        :message)))

(defn- try-parse [llm-response]
  (try (edn/read-string llm-response)
       (catch Exception e
         {:action ::failed-parse
          :data {:response llm-response
                 :parse-error (ex-message e)}})))

(defn- agent-step-message [tool-user parsed-response]
  (condp = (:action parsed-response)
    :continue-thinking
    {:role "user" :content "Continue"}
    :use-tool
    (let [{:keys [tool-name parameters]} (:data parsed-response)]
      {:role "user"
       :content (tool-user tool-name parameters)})
    {:role "user" :content (str "You did not use a valid action in the prescribed edn format. Try again. Here was the error:\n"
                                (:parse-error parsed-response))}))

(defn agent-loop [{:keys [elara/llm-request!] :as system-context} {:keys [system-prompt tools max-steps task final-output-schema] :as agent-config}]
  (let [tool-user (tools/->tool-user system-context tools)]
    (loop [max-remaining-steps (or max-steps 25)
           chat-history [{:role "system" :content system-prompt}
                         {:role "user" :content "Confirm you understand the edn format by responding with the final answer that you understand"}
                         {:role "assistant" :content "{:action :final-answer :data {:result \"I understand the edn format.\"}}"}
                         {:role "user" :content task}]]
      (when (zero? max-remaining-steps)
        (throw (ex-info "Max remaining steps reached" {:chat-history chat-history})))
      (let [response (llm-request! {:messages chat-history})
            response-body (:content response)
            parsed-response (try-parse response-body)
            llm-response-message {:role "assistant" :content (str response-body)}]
        (telemere/event! :elara/agent-step
                         {:level :debug
                          :data {:response response
                                 :parsed-response parsed-response}})
        (if-not (= :final-answer (:action parsed-response))
          (let [new-chat-history (concat chat-history
                                         [llm-response-message
                                          (agent-step-message tool-user parsed-response)])]
            (recur (dec max-remaining-steps) new-chat-history))
          (if (and final-output-schema (not (malli/validate final-output-schema (:data parsed-response))))
            (let [malli-error-msg (malli-error/humanize (malli/explain final-output-schema (:data parsed-response)))]
              (recur (dec max-remaining-steps) (concat chat-history
                                                       [llm-response-message
                                                        {:role "user"
                                                         :content (str "Your final answer did not match the expected schema: "
                                                                       malli-error-msg)}])))
            (do
              (telemere/event! :elara/agent-final-answer
                               {:level :debug
                                :data {:parsed-response parsed-response}})
              (assoc parsed-response
                     :chat-history
                     (concat chat-history
                             [llm-response-message])))))))))

(comment

  (telemere/with-min-level :debug
    (let [llm-request! (->ollama-llm-request {:model "gemma3:12b"})
          final-output-schema [:map
                               [:title :string]
                               [:scenes [:vector [:map
                                                  [:title :string]
                                                  [:body :string]]]]
                               [:notable-characters [:vector :string]]]
          system-prompt (slices->prompt [(base-system-prompt-slice {:final-output-schema final-output-schema})
                                         (roal-goal-backstory->prompt-slice {:role "Expert Storyteller"
                                                                             :goal "Tell emotionally-powerful stories"
                                                                             :backstory "You're a sage storyteller that hails from the days before complex visual technology. You paint pictures with words."})])]
      (agent-loop {:elara/llm-request! llm-request!}
                  {:system-prompt system-prompt
                   :max-steps 10
                   :task "Tell a simple three-scene story with 50 words per scene"
                   :final-output-schema final-output-schema}))))