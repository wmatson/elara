# elara
Experimental Lightweight Agent Role-based Approach

### Background

The name is a backronym that comes from the most common protagonist name ChatGPT was giving me when I was trying to use it for storywriting.

I started this framework because I wasn't happy with the level of control I had with [CrewAI](https://www.crewai.com/) and wanted to write the various integration and glue code in Clojure.

## Status

This repo is a quick haphazard fork of an internal [polylith](https://polylith.gitbook.io/polylith) component that I use in production. The API will *definitely* change in the future, but I will consider keeping the old interface around for backwards-compatibility reasons.

## Usage

See `interface.clj` and implementation for documentation and details. Anything outside of `interface.clj` should be considered an implementation detail. `system-context` is expected to a map with openai keys (eg `{:OPENAI_API_KEY "sk_your-key" ,,,}`) and any other system information your tools may need in order to run, I personally use [`integrant`](https://github.com/weavejester/integrant) to manage this system context. See the `system-test` directory for a sample usage.

## Development

Start your REPL with the `:system-test` alias engaged or `user.clj` will try to load a missing dependency on integrant. The system context will be loaded expecting a sibling directory to this repo named `config` with `api-keys.json` in it, which should contain a root level object with `OPENAI_API_KEY` mapped to a valid openai api key.