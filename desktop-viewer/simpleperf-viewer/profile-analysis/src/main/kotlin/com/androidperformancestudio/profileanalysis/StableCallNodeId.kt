package com.androidperformancestudio.profileanalysis

import java.lang.Long.rotateLeft

internal object StableCallNodeId {
    fun primaryHashStep(
        hash: Long,
        functionId: FlameFunctionId,
    ): Long = hashFunctionId(hash, functionId.value, PRIMARY_HASH_PRIME)

    fun secondaryHashStep(
        parentHash: Long?,
        functionId: FlameFunctionId,
    ): Long =
        hashFunctionId(
            parentHash ?: SECONDARY_HASH_OFFSET,
            functionId.value xor SECONDARY_HASH_DOMAIN,
            SECONDARY_HASH_PRIME,
        )

    fun derive(
        primaryHash: Long,
        secondaryHash: Long,
    ): Long {
        var mixed = primaryHash xor rotateLeft(secondaryHash, SECONDARY_ROTATION) xor STABLE_ID_DOMAIN
        mixed = (mixed xor (mixed ushr FIRST_MIX_SHIFT)) * MIX_CONSTANT_ONE
        mixed = (mixed xor (mixed ushr SECOND_MIX_SHIFT)) * MIX_CONSTANT_TWO
        return mixed xor (mixed ushr FINAL_MIX_SHIFT)
    }

    fun saturatingNonNegativeAdd(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun hashFunctionId(
        initialHash: Long,
        functionValue: Long,
        prime: Long,
    ): Long {
        var hash = initialHash
        var value = functionValue
        repeat(Long.SIZE_BYTES) {
            hash = (hash xor (value and BYTE_MASK)) * prime
            value = value ushr Byte.SIZE_BITS
        }
        return hash
    }
}

private const val PRIMARY_HASH_PRIME = 1099511628211L
private const val SECONDARY_HASH_OFFSET = 0x6a09e667f3bcc909L
private const val SECONDARY_HASH_PRIME = 0x100000001b3L
private const val SECONDARY_HASH_DOMAIN = -7046029254386353131L
private const val STABLE_ID_DOMAIN = -4417276706812531889L
private const val MIX_CONSTANT_ONE = -4658895280553007687L
private const val MIX_CONSTANT_TWO = -7723592293110705685L
private const val BYTE_MASK = 0xffL
private const val SECONDARY_ROTATION = 29
private const val FIRST_MIX_SHIFT = 30
private const val SECOND_MIX_SHIFT = 27
private const val FINAL_MIX_SHIFT = 31
