(ns elara.agent
  (:require
   [clojure.edn :as edn]
   [elara.tools.core :as tools]
   [elara.clients.openai :as openai]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.generator]
   [medley.core :as medley]
   [taoensso.telemere :as telemere]
   [taoensso.tufte :as tufte]))

(def ^:dynamic *llm-request* openai/llm-request)

(defn- agent-system-prompt [{:keys [backstory role goal tools final-output-schema]}]
  (str "You are an autonomous agent that interacts with a situated program to complete a task.
        The situated program will provide you with information and respond to your messages, but it requires a certain format to work.

        # Action Format
        This format is an EDN map with the following keys:
        :action - one of :final-answer, :use-tool, :continue-thinking
        :data - a map of any data you need to use to complete your task
        
        ## Final Answer
        Your task should have a final output, when you have it, set :action to :final-answer and set :data to your final answer.
        Otherwise, set :action to :continue-thinking and leave :data empty."
       (if final-output-schema
         (str "\n\nYour final answer must match the following malli schema:\n\n" (pr-str final-output-schema)
              "\n\nFor example, it could look like this (data generated from the schema, missing semantic meanings):\n\n"
              (malli.generator/generate final-output-schema))
         "If your final answer is a string, it should be under the :result key of the data map.")
       "\n\n## Thinking
        When you need to think, set :action to :continue-thinking and include your current thoughts in the :data map under the :thoughts key. 
        
        ## Tool Usage
        If you need to use a tool to complete your task, set :action to :use-tool and set :data to a map with the following keys:
        :tool-name - the name of the tool you need to use
        :parameters - an EDN map of parameters to pass to the tool\n\n"
       (tools/->prompt tools)
       "\n\n# Personality
        In order to complete your task, you should act in a certain way. 
        To that end, you are given a role, a goal, and a backstory. 
        These can inform your thoughts and actions, but remember that you are still interacting through a situated program.
        \n\n## Role\n" role
       "\n\n## Goal\n" goal
       "\n\n## Backstory\n" backstory))

(defn- agent-step-message [tool-user parsed-response]
  (condp = (:action parsed-response)
    :continue-thinking
    {:role "user" :content "Continue"}
    :use-tool
    (let [{:keys [tool-name parameters]} (:data parsed-response)]
      {:role "user"
       :content (tool-user tool-name parameters)})
    {:role "user" :content "You did not use a valid action in the prescribed edn format. Try again."}))

(defn- llm-clean [system-context llm-response]
  (telemere/log! {:level :debug
                  :data {:llm-response llm-response}}
                 "Cleaning LLM response")
  (:parsed-body
   (*llm-request* system-context
                  {:messages [{:role "system"
                               :content "You are a system that cleans up EDN maps that failed to parse. Your response should be a naked EDN map with no additional formatting.
                                         Your response should be a naked edn map
                                        
                                         Common mistakes include:

                                         *output surrounded with tick marks or other formatting.
                                         Example Response: {:a 1 :b 2 :c \"a dog with human teeth\"}
                                         NOT THIS: ```edn\n{:a 1, :b 2, :c \"a dog with human teeth\"}\n```

                                         *unmatched delimiters
                                         Example Response: {:a 1 :b 2 :c [1 2 3]}
                                         NOT THIS: {:a 1, :b 2, :c [1, 2, 3]]}

                                         Combinations of common mistakes may occur"}
                              {:role "user"
                               :content (str "The following is an EDN map that failed to parse:\n\n" (:parsed-body llm-response))}]
                   :max-tokens (get-in llm-response [:full-resp :usage :completion-tokens] 1000)
                   :model "gpt-4o-mini"})))

(def ^:dynamic *auto-clean-llm-response* true)

(defn- try-parse [system-context llm-response & [already-retried]]
  (try (edn/read-string (:parsed-body llm-response))
       (catch Exception e
         (if (or (not *auto-clean-llm-response*) already-retried)
           (do
             (telemere/log! {:level :info
                             :data {:response llm-response}}
                            "Error parsing agent response")
             {:action ::failed-parse
              :data {:response llm-response
                     :parse-error (ex-message e)}})
           (let [cleaned-response (llm-clean system-context llm-response)]
             (try-parse system-context
                        (assoc llm-response :parsed-body cleaned-response)
                        true))))))

(defn agent-loop
  [system-context {:keys [backstory role task tools max-steps final-output-schema llm-model max-tokens]
                   :or {max-tokens 1000}
                   :as agent-config}]
  (telemere/with-ctx+ {:agent-role role :llm-model llm-model :max-tokens max-tokens}
    (let [tool-user (tools/->tool-user system-context tools)
          agent-prompt (agent-system-prompt agent-config)]
      (loop [max-remaining-steps (or max-steps 25)
             chat-history [{:role "system" :content agent-prompt}
                           {:role "user" :content "Confirm you understand the edn format by responding with the final answer that you understand"}
                           {:role "assistant" :content "{:action :final-answer :data {:result \"I understand the edn format.\"}}"}
                           {:role "user" :content task}]]
        (let [response (*llm-request* system-context
                                      (medley/assoc-some
                                       {:messages chat-history
                                        :max-tokens max-tokens
                                        :include-full-resp true}
                                       :model llm-model))
              response-body (:parsed-body response)
              parsed-response (try-parse system-context response)
              llm-response-message {:role "assistant" :content (str response-body)}]
          (when (zero? max-remaining-steps)
            (throw (ex-info "Max remaining steps reached" {:last-response parsed-response
                                                           :chat-history chat-history})))
          (telemere/log! {:level :debug
                          :data {:parsed-response parsed-response}}
                         "Agent step")
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
                (telemere/log! {:level :debug
                                :data {:parsed-response parsed-response}}
                               "Agent final answer")
                (assoc parsed-response
                       :chat-history
                       (concat chat-history
                               [llm-response-message]))))))))))

(comment
  (require '[elara.tools.toy :refer [id-generator wait]])

  (let [tools (tools/->tools id-generator wait)]
    (tufte/profile
     {}
     (agent-loop (#'user/current-system-context)
                 {:tools tools
                  #_#_:llm-model "llama-3.3-70b-versatile"
                  :role "Award-Winning Creative Writer"
                  :goal "Create compelling stories"
                  :backstory "You are a creative writer known for extremely detailed settings and deep character development."
                  :task "Write a short story with three scenes all 80-200 words."
                  :final-output-schema [:map [:result [:seqable [:map [:id :string] [:scene :string]]]]]})))

  (try-parse (#'user/current-system-context)
             ;; Has ticks and an umnatched delimiter
             {:parsed-body "```edn\n{:action :final-answer,\n :data {:prompts [\"A neon-pink elephant gracefully gliding on a skateboard across a vividly colored detention hall, its trunk rhythmically moving cleaning supplies around. The vibrant surroundings feature bold, abstract shapes and asymmetrical furniture. The camera captures the movement with a smooth dolly, immersing the viewer in this whimsical rebellion. The lighting is bright and electric, with pinks and blues dominating the color palette to enhance the surreal atmosphere.\"\n   \n   \"A polka-dotted giraffe, standing on towering, geometric shelves, dusts oversized, pastel clocks with feather dusters attached to its horns. The setting is filled with colorful, mismatched furniture and giant decor, embodying the Memphis Group style. A wide-angle lens captures the precarious yet comedic scene, giving a sense of height and whimsicality. The colors are soft pastels contrasted with bold patterns, lit in a gentle glow that highlights each quirky detail.\"\n   \n   \"A group of funky raccoons energetically rifling through a stationary cabinet, tossing paper airplanes around the hall. The space is an explosion of vivid patterns and eclectic designs, with contrasting shapes and textures. The camera swiftly pans and tilts to follow the raccoons' antics, injecting energy and chaos into the scene. Warm yellows and oranges wash over the scene, blending with the playful chaos.\"\n   \n   \"The climax shows the animals coming together in the center of the hall beneath a twirling rainbow disco ball. The setting is saturated with color, and the dance floor is alive with pulsating blues and purples that sync with the rhythmic music, creating an aura of excitement and release. The camera circles the group to capture the unity and exhaustion, highlighting their satisfied expressions and hinting at dreams beyond their colorful, structured world.\"\n   \n   \"A final wide shot of the detention hall as the animals rest, with the entire space accentuated by vibrant Memphis Group motifs – bold, abstract forms in an array of colors. The camera slowly pulls back, showing the full breadth of this whimsical universe as it fades into a blend of colorful lights, encapsulating the video’s theme of structured rebellion. The lighting dims slightly, casting relaxing hues as the animals wind down from their lively escapade.\"]]}}\n```"})

  (let [tools (tools/->tools id-generator wait)]
    (tufte/profile
     {}
     (agent-loop (#'user/current-system-context)
                 {:tools tools
                  #_#_:llm-model "llama-3.3-70b-versatile"
                  :role "Creative Writer"
                  :goal "Create compelling stories"
                  :backstory "You are a creative writer known for extremely detailed settings and deep character development."
                  :task "Think at most two times, then write a short story with three scenes all less than 200 words."}))))
