/**
 * Port of StableCallNodeId.kt. Kotlin relies on 64-bit wrapping arithmetic, so
 * every step is explicitly narrowed back to 64 bits here.
 */
export const PRIMARY_HASH_OFFSET = -3750763034362895579n; // 0xCBF29CE484222325
const PRIMARY_HASH_PRIME = 1099511628211n;
const SECONDARY_HASH_OFFSET = 0x6a09e667f3bcc909n;
const SECONDARY_HASH_PRIME = 0x100000001b3n;
const SECONDARY_HASH_DOMAIN = -7046029254386353131n; // 0x9E3779B97F4A7C15
const STABLE_ID_DOMAIN = -4417276706812531889n; // 0xC2B2AE3D27D4EB4F
const MIX_CONSTANT_ONE = -4658895280553007687n; // 0xBF58476D1CE4E5B9
const MIX_CONSTANT_TWO = -7723592293110705685n; // 0x94D049BB133111EB
const SECONDARY_ROTATION = 29n;
const FIRST_MIX_SHIFT = 30n;
const SECOND_MIX_SHIFT = 27n;
const FINAL_MIX_SHIFT = 31n;
const BYTE_MASK = 0xffn;

function i64(value: bigint): bigint {
  return BigInt.asIntN(64, value);
}

function u64(value: bigint): bigint {
  return BigInt.asUintN(64, value);
}

export function rotateLeft64(value: bigint, bits: bigint): bigint {
  const unsigned = u64(value);
  return i64((unsigned << bits) | (unsigned >> (64n - bits)));
}

function hashFunctionId(initialHash: bigint, functionValue: bigint, prime: bigint): bigint {
  let hash = initialHash;
  let value = functionValue;
  for (let index = 0; index < 8; index += 1) {
    hash = i64((hash ^ (value & BYTE_MASK)) * prime);
    value = u64(value) >> 8n;
  }
  return hash;
}

export function primaryHashStep(hash: bigint, functionId: bigint): bigint {
  return hashFunctionId(hash, functionId, PRIMARY_HASH_PRIME);
}

export function secondaryHashStep(parentHash: bigint | undefined, functionId: bigint): bigint {
  return hashFunctionId(
    parentHash ?? SECONDARY_HASH_OFFSET,
    functionId ^ SECONDARY_HASH_DOMAIN,
    SECONDARY_HASH_PRIME,
  );
}

export function deriveStableId(primaryHash: bigint, secondaryHash: bigint): bigint {
  let mixed = primaryHash ^ rotateLeft64(secondaryHash, SECONDARY_ROTATION) ^ STABLE_ID_DOMAIN;
  mixed = i64((mixed ^ (u64(mixed) >> FIRST_MIX_SHIFT)) * MIX_CONSTANT_ONE);
  mixed = i64((mixed ^ (u64(mixed) >> SECOND_MIX_SHIFT)) * MIX_CONSTANT_TWO);
  return i64(mixed ^ (u64(mixed) >> FINAL_MIX_SHIFT));
}

/** Kotlin saturates on overflow instead of throwing; so does this. */
export function saturatingNonNegativeAdd(left: bigint, right: bigint): bigint {
  const sum = left + right;
  if (sum > 9223372036854775807n || sum < -9223372036854775808n) return 9223372036854775807n;
  return sum;
}

/** FNV-1a over a string, matching stableResourceHash in CallStackTransformer. */
export function stableStringHash(value: string): bigint {
  let hash = PRIMARY_HASH_OFFSET;
  for (const byte of new TextEncoder().encode(value)) {
    hash = i64((hash ^ BigInt(byte)) * 1099511628211n);
  }
  return hash;
}
