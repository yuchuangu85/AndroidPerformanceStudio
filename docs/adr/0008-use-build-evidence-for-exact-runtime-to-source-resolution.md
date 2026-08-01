# Use build evidence for exact runtime-to-source resolution

Exact resolution may use a read-only build evidence bundle containing application and system build identity, obfuscation mappings, native symbols, ELF Build IDs, and revision metadata. Source-only analysis remains available, but missing evidence lowers resolution confidence and prevents exact navigation where runtime identities cannot be verified. This adds artifact discovery and lifecycle work but is necessary for reproducible obfuscated and native source locations.
