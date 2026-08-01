# Keep the AI boundary provider-neutral while supporting OpenAI first

Profiler features depend on a provider-neutral AI analysis contract, while the first supported adapter uses the OpenAI Responses API already present in `ai-core`. Endpoint and model configuration remain explicit extension points, but compatibility with other services is not claimed until a dedicated adapter and contract tests exist. Credentials are stored in the operating system credential store and are excluded from settings files, archives, prompts, and logs.
