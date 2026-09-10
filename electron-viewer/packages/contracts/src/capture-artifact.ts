/**
 * Port of com.androidperformancestudio.contracts.CaptureArtifact (contract v1).
 *
 * Serialization mirrors CaptureArtifactJson in Kotlin:
 *   encodeDefaults = true, explicitNulls = false, ignoreUnknownKeys = true,
 *   classDiscriminator = "producerType".
 *
 * Value classes (ArtifactId, Sha256, ClockDomain, CapabilityId, ...) serialize
 * inline as their underlying string, so the wire shape uses plain strings.
 */

import { z } from 'zod';

export const CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION = 1 as const;

const SHA_256 = /^[0-9a-f]{64}$/;
const LOWERCASE_ID = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
const CAPABILITY_ID = /^[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+$/;
const CLOCK_DOMAIN = /^[A-Za-z][A-Za-z0-9._-]*$/;

const nonBlank = z.string().refine((value) => value.trim().length > 0, {
  message: 'must not be blank',
});
const optionalNonBlank = nonBlank.optional();

// Kotlin Long fields (nanosecond/millisecond timestamps) exceed JavaScript's
// safe integer range, so they are represented as bigint and (de)serialized
// with exact source-literal recovery.
const nonNegativeLong = z
  .union([z.bigint(), z.number().int()])
  .transform((value) => BigInt(value))
  .refine((value) => value >= 0n, { message: 'must not be negative' });

export const artifactIdSchema = nonBlank;
export const artifactKindSchema = z
  .string()
  .regex(LOWERCASE_ID, 'artifact kind must be a lowercase stable id');
export const artifactLocationSchema = nonBlank;
export const sha256Schema = z
  .string()
  .regex(SHA_256, 'sha256 must contain 64 lowercase hexadecimal characters');

export const artifactFormatSchema = z.object({
  name: nonBlank,
  version: optionalNonBlank,
});

export const knownArtifactProducerSchema = z.object({
  producerType: z.literal('known'),
  name: nonBlank,
  version: optionalNonBlank,
  sha256: sha256Schema.optional(),
});

export const unknownArtifactProducerSchema = z.object({
  producerType: z.literal('unknown'),
});

export const artifactProducerSchema = z.discriminatedUnion('producerType', [
  knownArtifactProducerSchema,
  unknownArtifactProducerSchema,
]);

export const artifactAcquisitionKindSchema = z.enum(['CAPTURE', 'IMPORT']);

export const artifactAcquisitionSchema = z.object({
  kind: artifactAcquisitionKindSchema,
  application: nonBlank,
  applicationVersion: optionalNonBlank,
  performedAtEpochMillis: nonNegativeLong,
});

export const artifactProvenanceSchema = z.object({
  producer: artifactProducerSchema.default({ producerType: 'unknown' }),
  acquisition: artifactAcquisitionSchema,
  processors: z.array(knownArtifactProducerSchema).default([]),
});

export const clockDomainSchema = z
  .string()
  .regex(CLOCK_DOMAIN, 'clock domain must be a stable identifier');

export const artifactTimePointSchema = z.object({
  clockDomain: clockDomainSchema,
  timestampNanos: nonNegativeLong,
});

export const clockMappingSchema = z
  .object({
    source: clockDomainSchema,
    target: clockDomainSchema,
    sourceReferenceNanos: nonNegativeLong,
    targetReferenceNanos: nonNegativeLong,
    errorBoundNanos: nonNegativeLong,
    validFromSourceNanos: nonNegativeLong.optional(),
    validToSourceNanos: nonNegativeLong.optional(),
  })
  .superRefine((mapping, ctx) => {
    if (mapping.source === mapping.target) {
      ctx.addIssue({ code: 'custom', path: ['target'], message: 'clock mapping must connect different domains' });
    }
    const from = mapping.validFromSourceNanos;
    const to = mapping.validToSourceNanos;
    if (from !== undefined && to !== undefined && from > to) {
      ctx.addIssue({ code: 'custom', path: ['validToSourceNanos'], message: 'clock mapping validity start must not follow its end' });
    }
    if (from !== undefined && mapping.sourceReferenceNanos < from) {
      ctx.addIssue({ code: 'custom', path: ['sourceReferenceNanos'], message: 'clock mapping reference must not precede its validity interval' });
    }
    if (to !== undefined && mapping.sourceReferenceNanos > to) {
      ctx.addIssue({ code: 'custom', path: ['sourceReferenceNanos'], message: 'clock mapping reference must not follow its validity interval' });
    }
  });

export const deviceLocalIdSchema = z
  .string()
  .regex(SHA_256, 'device local id must be a salted SHA-256 value');

export const deviceTargetIdentitySchema = z.object({
  localId: deviceLocalIdSchema,
  manufacturer: optionalNonBlank,
  model: optionalNonBlank,
  buildFingerprint: optionalNonBlank,
  rawSerial: optionalNonBlank,
});

export const processIdentityStrengthSchema = z.enum(['STRONG', 'WEAK']);

export type ProcessIdentityStrength = z.infer<typeof processIdentityStrengthSchema>;

function expectedProcessIdentityStrength(
  deviceLocalId: string | undefined,
  startedAt: unknown,
): ProcessIdentityStrength {
  return deviceLocalId === undefined || startedAt === undefined ? 'WEAK' : 'STRONG';
}

export const processIdentitySchema = z
  .object({
    pid: z.number().int().positive(),
    deviceLocalId: deviceLocalIdSchema.optional(),
    processName: optionalNonBlank,
    packageName: optionalNonBlank,
    startedAt: artifactTimePointSchema.optional(),
    strength: processIdentityStrengthSchema.optional(),
  })
  .superRefine((identity, ctx) => {
    const expected = expectedProcessIdentityStrength(identity.deviceLocalId, identity.startedAt);
    if (identity.strength !== undefined && identity.strength !== expected) {
      ctx.addIssue({ code: 'custom', path: ['strength'], message: 'process identity strength must match its device and start marker' });
    }
  })
  .transform((identity) => ({
    ...identity,
    strength: identity.strength ?? expectedProcessIdentityStrength(identity.deviceLocalId, identity.startedAt),
  }));

export const capabilityIdSchema = z
  .string()
  .regex(CAPABILITY_ID, 'capability id must be a lowercase namespaced id');

export const artifactCompletenessSchema = z.enum(['COMPLETE', 'PARTIAL', 'UNKNOWN']);

export const artifactLimitationSchema = z.object({
  capability: capabilityIdSchema.optional(),
  code: nonBlank,
  message: nonBlank,
});

export const privacyRedactionSchema = z.enum(['DEVICE_SERIAL']);

export type PrivacyRedaction = z.infer<typeof privacyRedactionSchema>;

const DEFAULT_PRIVACY_REDACTIONS: PrivacyRedaction[] = ['DEVICE_SERIAL'];
const DEFAULT_PRIVACY = {
  containsSensitiveIdentity: false,
  redactions: DEFAULT_PRIVACY_REDACTIONS,
};

export const artifactPrivacySchema = z.object({
  containsSensitiveIdentity: z.boolean().default(false),
  redactions: z
    .array(privacyRedactionSchema)
    .default(DEFAULT_PRIVACY_REDACTIONS)
    .transform((values) => Array.from(new Set(values))),
});

const dedupe = <T>(values: T[]): T[] => Array.from(new Set(values));

export const captureArtifactSchema = z
  .object({
    contractVersion: z
      .literal(CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION)
      .default(CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION),
    id: artifactIdSchema,
    kind: artifactKindSchema,
    location: artifactLocationSchema,
    sha256: sha256Schema,
    format: artifactFormatSchema.nullable().default(null),
    provenance: artifactProvenanceSchema,
    capturedAt: artifactTimePointSchema.nullable().default(null),
    device: deviceTargetIdentitySchema.nullable().default(null),
    process: processIdentitySchema.nullable().default(null),
    clockDomains: z.array(clockDomainSchema).default([]).transform(dedupe),
    clockMappings: z.array(clockMappingSchema).default([]),
    requestedCapabilities: z.array(capabilityIdSchema).nullable().default(null),
    availableCapabilities: z.array(capabilityIdSchema).default([]).transform(dedupe),
    completeness: artifactCompletenessSchema.default('UNKNOWN'),
    limitations: z.array(artifactLimitationSchema).default([]),
    warnings: z.array(nonBlank).default([]),
    privacy: artifactPrivacySchema.default(() => DEFAULT_PRIVACY),
  })
  .superRefine((artifact, ctx) => {
    const clockDomains = new Set(artifact.clockDomains);

    if (artifact.capturedAt !== null && !clockDomains.has(artifact.capturedAt.clockDomain)) {
      ctx.addIssue({ code: 'custom', path: ['capturedAt'], message: 'capture timestamp clock domain must be declared by the artifact' });
    }
    const startedAt = artifact.process?.startedAt;
    if (startedAt !== undefined && !clockDomains.has(startedAt.clockDomain)) {
      ctx.addIssue({ code: 'custom', path: ['process', 'startedAt'], message: 'process start clock domain must be declared by the artifact' });
    }
    const processDeviceLocalId = artifact.process?.deviceLocalId;
    if (processDeviceLocalId !== undefined && processDeviceLocalId !== artifact.device?.localId) {
      ctx.addIssue({ code: 'custom', path: ['process', 'deviceLocalId'], message: 'process identity must reference the artifact device' });
    }
    if (artifact.device?.rawSerial !== undefined && !artifact.privacy.containsSensitiveIdentity) {
      ctx.addIssue({ code: 'custom', path: ['device', 'rawSerial'], message: 'raw device serial requires explicit sensitive identity preservation' });
    }
    if (artifact.device?.rawSerial !== undefined && artifact.privacy.redactions.includes('DEVICE_SERIAL')) {
      ctx.addIssue({ code: 'custom', path: ['device', 'rawSerial'], message: 'raw device serial cannot be present when device serial is redacted' });
    }
    for (const mapping of artifact.clockMappings) {
      if (!clockDomains.has(mapping.source) || !clockDomains.has(mapping.target)) {
        ctx.addIssue({ code: 'custom', path: ['clockMappings'], message: 'clock mappings must only reference declared clock domains' });
        break;
      }
    }

    const requested = artifact.requestedCapabilities;
    const available = new Set(artifact.availableCapabilities);
    if (artifact.completeness === 'COMPLETE') {
      if (requested === null || !requested.every((capability) => available.has(capability))) {
        ctx.addIssue({ code: 'custom', path: ['completeness'], message: 'complete artifacts must provide every requested capability' });
      }
    } else if (artifact.completeness === 'PARTIAL') {
      if (requested === null) {
        ctx.addIssue({ code: 'custom', path: ['completeness'], message: 'partial artifacts must declare requested capabilities' });
      } else {
        const missing = requested.filter((capability) => !available.has(capability));
        if (missing.length === 0) {
          ctx.addIssue({ code: 'custom', path: ['completeness'], message: 'partial artifacts must be missing a requested capability' });
        }
        for (const capability of missing) {
          if (!artifact.limitations.some((limitation) => limitation.capability === capability)) {
            ctx.addIssue({ code: 'custom', path: ['limitations'], message: 'partial artifacts must explain every missing capability' });
          }
        }
      }
    } else if (requested !== null) {
      ctx.addIssue({ code: 'custom', path: ['completeness'], message: 'unknown completeness is only valid when requested capabilities are unknown' });
    }
  });

export type CaptureArtifact = z.infer<typeof captureArtifactSchema>;
export type ArtifactProducer = z.infer<typeof artifactProducerSchema>;
export type ArtifactKnownProducer = z.infer<typeof knownArtifactProducerSchema>;
export type ArtifactPrivacy = z.infer<typeof artifactPrivacySchema>;
export type ProcessIdentity = z.infer<typeof processIdentitySchema>;

const LONG_FIELDS = new Set([
  'performedAtEpochMillis',
  'timestampNanos',
  'sourceReferenceNanos',
  'targetReferenceNanos',
  'errorBoundNanos',
  'validFromSourceNanos',
  'validToSourceNanos',
]);

interface JsonParseContext {
  readonly source?: string;
}

/**
 * Reviver that recovers exact 64-bit integers from their JSON source literal.
 * Kotlin kotlinx.serialization writes Long values as bare JSON numbers, which
 * JSON.parse would otherwise round to the nearest double.
 */
function reviveLongFields(key: string, value: unknown, context?: JsonParseContext): unknown {
  if (LONG_FIELDS.has(key) && typeof value === 'number' && Number.isInteger(value)) {
    const source = context?.source ?? String(value);
    if (/^-?\d+$/.test(source)) {
      return BigInt(source);
    }
  }
  return value;
}

/** Serializes with Kotlin explicitNulls = false semantics and unquoted bigints. */
function stringifyJson(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) {
    return '[' + value.map((entry) => stringifyJson(entry)).join(',') + ']';
  }
  if (typeof value === 'object') {
    const properties: string[] = [];
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      if (entry === null || entry === undefined) continue;
      properties.push(JSON.stringify(key) + ':' + stringifyJson(entry));
    }
    return '{' + properties.join(',') + '}';
  }
  return 'null';
}

/** Validates and encodes to the Kotlin-compatible JSON wire form. */
export function encodeCaptureArtifact(artifact: unknown): string {
  return stringifyJson(captureArtifactSchema.parse(artifact));
}

/** Decodes and validates a CaptureArtifact, ignoring unknown keys. */
export function decodeCaptureArtifact(value: string): CaptureArtifact {
  const reviver = reviveLongFields as unknown as (key: string, value: unknown) => unknown;
  return captureArtifactSchema.parse(JSON.parse(value, reviver));
}
