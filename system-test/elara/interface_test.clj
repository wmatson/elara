(ns elara.interface-test
  (:require
   [clojure.test :as test :refer [deftest is]]
   [elara.interface :as llm-agent-framework]
   [elara.tools.toy :as toy]
   [elara.tools.core :as tools]
   [malli.core :as malli]
   [user]))

(deftest ^:system-test final-output-schema-test
  (when-not (user/current-system-context)
    (throw (ex-info
            "No system context found, make sure to refresh-and-restart your system before running these tests"
            {})))
  (let [final-output-schema [:map
                             [:name [:string]]
                             [:age [:int]]
                             [:motivation [:string]]]
        {:keys [action data]} (llm-agent-framework/agent-loop
                               (user/current-system-context)
                               {:role "Creative Writer"
                                :goal "Create a simple character"
                                :task "Create a character with a name, age, and 10-word motivation"
                                :max-steps 5
                                :final-output-schema final-output-schema
                                :llm-model "gpt-4o-mini"})]
    (is (= action :final-answer))
    (is (malli/validate final-output-schema data))))

(deftest ^:system-test tool-use-test
  (let [call-count (atom 0)
        wrapped-id-generator (-> toy/id-generator
                                 (update :fn
                                         (fn [f]
                                           (fn wrapped-id-gen [_system-context inputs]
                                             (swap! call-count inc)
                                             (f _system-context inputs))))
                                 (assoc :name :wrapped-test/id-generator))
        agent {:role "Creative Writer"
               :goal "Create a simple character"
               :task "Create a character with a name, age, generated id, and 10-word motivation"
               :tools (tools/->tools wrapped-id-generator)
               :max-steps 5
               :final-output-schema [:map [:name string?]
                                     [:age int?]
                                     [:motivation string?]
                                     [:id string?]]
               :llm-model "gpt-4o-mini"}
        {:keys [action]} (llm-agent-framework/agent-loop
                          (user/current-system-context)
                          agent)]
    (is (= action :final-answer))
    (is (pos? @call-count))))
