# Separate analysis confidence from source resolution confidence

Analysis findings carry confidence in the performance conclusion, while source candidates carry an independent deterministic resolution confidence. AI output references only candidate IDs created by the resolver and cannot create or promote source locations; only a unique exact candidate may navigate directly, probable candidates require user selection, and weak or missing candidates do not expose a direct jump. This prevents persuasive model output from being mistaken for location evidence.
