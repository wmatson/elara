(ns elara.interface
  (:require [elara.agent :as agent]
            [elara.tools.core :as tools]))

(def tool-schema tools/tool-schema)

(defn ->tools
  "Create a set of tools from varargs of tool definitions, each matching `tool-schema`.
   Each tool definition should be a map with keys `:name`, `:fn`, and `:schema`."
  [& tool-defs]
  (apply tools/->tools tool-defs))

(defn agent-loop
  "Run an agent loop with the given system context and agent configuration.
   `tools` should be created with ->tools.
   `backstory` should be the backstory of the agent, such as 'you are a concept artist', it's okay for this to be large.
   `role` is the role of the agent, such as 'Creative Writer'.
   `goal` is the goal of the agent, such as 'create compelling stories'.
   `task` is the task that the agent will perform, such as 'generate a concept art for a game'.
   `max-steps` is the maximum number of steps the agent will take, defaults to 25.
   `final-output-schema` (optional) is the malli schema that the agent's final answer must match.
   `llm-model` (optional) is the LLM model to use, should be a valid OpenAI model name."
  [system-context {:keys [backstory role goal task tools max-steps final-output-schema max-tokens llm-model] :as agent-config}]
  (agent/agent-loop system-context agent-config))
