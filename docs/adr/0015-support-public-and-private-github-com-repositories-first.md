# Support public and private GitHub.com repositories first

The first GitHub source provider supports public and private GitHub.com repositories with repository-read credentials stored only in the operating system credential store. Provider interfaces retain configurable host and authentication boundaries for a later GitHub Enterprise Server adapter, but enterprise certificates and authentication flows are outside the first release. Cached immutable snapshots remain usable when authentication is temporarily unavailable.
