# Default network evidence to irreversible minimization

Network capture, persistence, and export produce Minimized Network Evidence by default: credentials, user identifiers, unrestricted path and header values, and bodies are removed or irreversibly replaced before they can be persisted. This sacrifices some endpoint detail to prevent routine profiling artifacts from becoming full-fidelity traffic records; any future full-fidelity mode must require explicit authorization, use isolated storage, and remain visibly marked as sensitive rather than weakening the default policy.
