import { loadComposeAgentBundle } from './compose-agent-bundle.js';
import { deployComposeAgent, type ComposeDeploymentAdb } from './compose-agent-deployment.js';
import { ComposeInspectorArtifactResolver, type ComposeInspectorArtifactResolverOptions } from './compose-inspector-artifact.js';
import { captureFromRunningComposeAgent, type ComposeAgentCapture, type ComposeInspectionAdb } from './compose-inspection-service.js';

const TIMEOUT_MS = 30_000;

export interface VerifiedComposeCaptureAdb extends ComposeDeploymentAdb, ComposeInspectionAdb {}
export interface VerifiedComposeCaptureOptions {
  readonly bundleRoot: string;
  readonly artifactResolver: Omit<ComposeInspectorArtifactResolverOptions, 'cacheDirectory'> & { readonly cacheDirectory: string };
}

/** Full guarded Kotlin-equivalent lifecycle: verify → deploy → authenticate → resolve exact inspector → stable capture → cleanup. */
export async function captureWithVerifiedComposeAgent(
  adb: VerifiedComposeCaptureAdb,
  packageName: string,
  now: () => number,
  options: VerifiedComposeCaptureOptions,
): Promise<ComposeAgentCapture> {
  const abi = (await adb.shell(['getprop', 'ro.product.cpu.abi'], { timeoutMs: TIMEOUT_MS })).stdout.trim();
  const bundle = await loadComposeAgentBundle(options.bundleRoot, abi);
  const deployment = await deployComposeAgent(adb, packageName, bundle);
  try {
    const resolver = new ComposeInspectorArtifactResolver(options.artifactResolver);
    return await captureFromRunningComposeAgent(adb, packageName, now, {
      sessionToken: deployment.sessionToken,
      viewInspectorPath: deployment.viewInspectorPath,
      resolveComposeInspectorPath: async (composeVersion) => {
        const inspector = await resolver.resolve(composeVersion);
        return await deployment.deployComposeInspector(inspector.jarPath, inspector.identity.sha256);
      },
    });
  } finally {
    await deployment.cleanup();
  }
}
