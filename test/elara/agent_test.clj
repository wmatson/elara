(ns elara.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [elara.agent :as agent]
            [elara.tools.toy :as toy]
            [clojure.string]
            [elara.tools.core :as tools]))

(defn- ->fake-llm [responses]
  (let [remaining-responses (atom responses)]
    (fn fake-llm [_system-context _params]
      (if-let [response (first @remaining-responses)]
        (do
          (swap! remaining-responses rest)
          {:parsed-body (pr-str response)})
        (throw (ex-info "No more responses" {}))))))

(defn- ->save-and-recall
  "Returns a vector of two tools: one that saves key-value-pairs in memory and another that recalls them"
  []
  (let [memory (atom {})]
    [{:name :save
      :fn (fn save-tool [_system-context {:keys [key value]}]
            (println key value)
            (swap! memory assoc key value))
      :schema [:map
               [:key :string]
               [:value :any]]}
     {:name :recall
      :fn (fn recall-tool [_system-context {:keys [key]}]
            (get @memory key))
      :schema [:map [:key string?]]}]))

(def system-messages [:system :format-confirm :confirmation])

(deftest basic-tool-calling
  (with-bindings {#'agent/*auto-clean-llm-response* false}
    (let [[save recall] (->save-and-recall)]
      (with-bindings {#'agent/*llm-request* (->fake-llm [{:action :use-tool
                                                        :data {:tool-name (:name save)
                                                               :parameters {:key "test-key"
                                                                            :value "test-value"}}}
                                                       {:action :final-answer
                                                        :data {:result "Done"}}])}
        (let [result (agent/agent-loop {}
                                       {:backstory "You're an agent being used for tests"
                                        :task "Save a test value and return the final answer 'Done'"
                                        :tools (tools/->tools save)})]
          (is (= "Done" (get-in result [:data :result])))
          (is (= "test-value" ((:fn recall) {} {:key "test-key"})))
          (is (= (count (concat system-messages
                                [:task :save-call :save-report :result]))
                 (count (:chat-history result)))))))
    (testing "Framework tolerates tool failure, reports info to llm"
      (let [always-fail (toy/->always-fail "Failed1234"
                                           {:reason-data-7 "arst"})]
        (with-bindings {#'agent/*llm-request* (->fake-llm [{:action :use-tool
                                                       :data {:tool-name (:name always-fail)
                                                              :parameters {:key "test-key"
                                                                           :value "test-value"}}}
                                                      {:action :continue-thinking
                                                       :data {:thought "Thinking..."}}
                                                      {:action :use-tool
                                                       :data {:tool-name (:name always-fail)
                                                              :parameters {:key "test-key"
                                                                           :value "test-value"}}}
                                                      {:action :final-answer
                                                       :data {:result "Done"}}])}
          (let [result (agent/agent-loop {}
                                         {:backstory "You're an agent being used for tests"
                                          :task "Save a test value and return the final answer 'Done'"
                                          :tools (tools/->tools always-fail)})
                fail-report-index (-> (concat system-messages [:task :fail-call :fail-report])
                                      count
                                      dec)
                fail-report (-> result :chat-history
                                (nth fail-report-index)
                                :content)]
            (is (= "Done" (get-in result [:data :result])))
            (is (= (count (concat system-messages
                                  [:task :fail-call :fail-report :thought :continue :fail-call :fail-report :result]))
                   (count (:chat-history result))))
            (is (clojure.string/includes? fail-report "Failed1234"))
            (is (clojure.string/includes? fail-report "reason-data-7"))
            (is (clojure.string/includes? fail-report "arst"))))))))

(deftest basic-llm-interaction
  (with-bindings {#'agent/*auto-clean-llm-response* false}
    (testing "Framework tolerates misformatting"
      (with-bindings {#'agent/*llm-request* (->fake-llm ["Not a formatted message"
                                                            "["
                                                            "```\nsomeCode = 1234\n```"
                                                            "{:action \"risetn\"
                                                   :data {:result \"MissingCurlies\""
                                                    {:action :continue-thinking
                                                     :data {:thought "Thinking..."}}
                                                    {:action :not-a-valid-action
                                                     :data {:a 1}}
                                                    {:action :continue-thinking
                                                     :data {:thought "Thinking..."}}
                                                    {:action :final-answer
                                                     :data {:result "Done"}}])}
        (let [result (agent/agent-loop {}
                                       {:backstory "You're an agent being used for tests"
                                        :task "Send some invalid messages to the system"
                                        :tools (tools/->tools)})]
          (is (= "Done" (get-in result [:data :result])))
          (is (= (count (concat system-messages
                                [:task
                                 :misformatted :reprimand
                                 :misformatted :reprimand
                                 :misformatted :reprimand
                                 :misformatted :reprimand
                                 :thought :continue :not-a-valid-action :reprimand :thought :continue :result]))
                 (count (:chat-history result))))
          result)))
    (testing "Framework tolerates LLM not completing thoughts"
      (with-bindings {#'agent/*llm-request* (->fake-llm
                                               (map (fn [_x]
                                                      {:action :continue-thinking :data {:thought "Thinking..."}})
                                                    (range 11)))}
        (is (thrown-with-msg?
             Exception #"Max remaining steps reached"
             (agent/agent-loop {}
                               {:max-steps 10
                                :backstory "You're an agent being used for tests"
                                :task "Never say you're done thinking"
                                :tools (tools/->tools)})))))))