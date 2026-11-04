#!/usr/bin/env bash
# Never enable tracing: the selected runtime alone consumes the inherited credential.
set +x
set -euo pipefail
umask 077

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

mode="${1:-run}"
[[ $# -le 1 && ( "$mode" == run || "$mode" == --check ) ]] || fail 'Usage: ai-provider-acceptance.sh [--check]'
[[ "${APS_RUN_LIVE_PROVIDER:-}" == true ]] || fail 'E_LIVE_OPT_IN: APS_RUN_LIVE_PROVIDER=true is required'
case "${APS_LIVE_PROVIDER:-}" in
    openai|codex)
        [[ -n "${OPENAI_API_KEY:-}" ]] || fail 'E_LIVE_AUTH: OPENAI_API_KEY is required'
        ;;
    claude)
        [[ -n "${ANTHROPIC_API_KEY:-}" ]] || fail 'E_LIVE_AUTH: ANTHROPIC_API_KEY is required'
        ;;
    *) fail 'E_LIVE_PROVIDER: explicitly select openai, codex or claude' ;;
esac
[[ "${APS_LIVE_MODEL:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]] || fail 'E_LIVE_MODEL: an explicit model ID is required'
if [[ "$APS_LIVE_PROVIDER" == codex ]]; then
    [[ "$APS_LIVE_MODEL" =~ ^([A-Za-z0-9][A-Za-z0-9._:-]*-)?codex([-.:][A-Za-z0-9._:-]+)?$ ]] || fail 'E_LIVE_MODEL: codex requires an explicit Codex-named model'
fi
if [[ "$APS_LIVE_PROVIDER" == claude ]]; then
    runtime="$(command -v claude || true)"
    if [[ -z "$runtime" ]]; then
        for candidate in /opt/homebrew/bin/claude /usr/local/bin/claude "${HOME:?}/.local/bin/claude"; do
            if [[ -f "$candidate" && -x "$candidate" ]]; then
                runtime="$candidate"
                break
            fi
        done
    fi
    [[ -n "$runtime" && -f "$runtime" && -x "$runtime" ]] || fail 'E_LIVE_RUNTIME: Claude executable is unavailable'
fi

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
[[ -x "$root/desktop-viewer/gradlew" ]] || fail 'E_LIVE_RUNTIME: Gradle wrapper is unavailable'
if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" ]] || fail 'E_LIVE_RUNTIME: JAVA_HOME has no Java executable'
else
    command -v java >/dev/null 2>&1 || fail 'E_LIVE_RUNTIME: Java is unavailable'
fi
if [[ "$mode" == --check ]]; then
    printf '%s\n' 'PREREQUISITES_PRESENT: no Gradle, discovery, authentication check or provider request was executed'
    exit 0
fi

temporary_receipt=''
if [[ -z "${APS_LIVE_RECEIPT:-}" ]]; then
    temporary_receipt="$(mktemp -d "${TMPDIR:-/tmp}/aps-provider-acceptance.XXXXXX")"
    temporary_receipt="$(cd -- "$temporary_receipt" && pwd -P)"
    export APS_LIVE_RECEIPT="$temporary_receipt/receipt.json"
fi
cleanup() {
    if [[ -n "$temporary_receipt" ]]; then
        rm -f -- "$temporary_receipt/receipt.json"
        rmdir -- "$temporary_receipt"
    fi
}
trap cleanup EXIT
[[ "$APS_LIVE_RECEIPT" == /* && ! -e "$APS_LIVE_RECEIPT" && ! -L "$APS_LIVE_RECEIPT" ]] || fail 'E_LIVE_RECEIPT: use a new absolute file path'
export APS_RUN_LIVE_PROVIDER APS_LIVE_PROVIDER APS_LIVE_MODEL
# Prevent inherited JVM diagnostic flags from dumping headers, requests or environment values.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS GRADLE_OPTS
status=0
"$root/desktop-viewer/gradlew" -p "$root/desktop-viewer" \
    :ai-platform:ai-platform-app:test \
    --tests com.androidperformancestudio.aiplatform.app.LiveProviderAcceptanceTest.liveProviderAcceptance \
    --no-daemon --no-configuration-cache --no-build-cache --no-scan --rerun-tasks \
    --max-workers=1 --console=plain --quiet >/dev/null 2>&1 || status=$?
if [[ -f "$APS_LIVE_RECEIPT" && ! -L "$APS_LIVE_RECEIPT" ]]; then
    # Only the test's allowlisted JSON metadata, never Gradle/CLI stdout or stderr.
    cat -- "$APS_LIVE_RECEIPT"
else
    printf '%s\n' 'E_LIVE_RECEIPT: no acceptance receipt was produced (build or preflight failure)' >&2
    [[ "$status" -ne 0 ]] || status=1
fi
[[ "$status" -eq 0 ]] || fail 'E_LIVE_ACCEPTANCE_FAILED: no retry was attempted; do not automatically rerun'
