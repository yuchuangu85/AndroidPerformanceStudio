# APS patches for AOSP UI Inspector

Apply these patches in lexical order to the unmodified snapshot in
`../aosp-ui-inspector` before building the packaged agent artifacts.

`0001-require-session-token.patch` passes a random per-session token through the
native agent, service and payload, then requires the first framed host message
to match it in constant time. The token is never written to an archive or log.

Check against the pinned snapshot:

```sh
(cd third_party/aosp-ui-inspector && patch --dry-run -p1 < ../aosp-ui-inspector-patches/0001-require-session-token.patch)
```
