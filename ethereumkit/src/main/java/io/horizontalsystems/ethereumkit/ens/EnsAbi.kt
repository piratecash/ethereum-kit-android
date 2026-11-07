package io.horizontalsystems.ethereumkit.ens

import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import io.horizontalsystems.ethereumkit.models.Address
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer

// Own ABI code: web3j abi calls BigInteger constructors that Android has only from API 33.
internal object EnsAbi {

    private const val WORD = 32
    private const val MAX_LABEL_BYTES = 255

    // Hardcoded: keccak works only after EthereumKit.init(), so class init must not need it.
    const val RESOLVE_SELECTOR = "0x9061b923"
    const val ADDR_SELECTOR = "0x3b3b57de"
    const val OFFCHAIN_LOOKUP_SELECTOR = "0x556f1830"
    const val BATCH_QUERY_SELECTOR = "0xa780bab6"
    const val HTTP_ERROR_SELECTOR = "0x01800152"
    const val ERROR_SELECTOR = "0x08c379a0"

    /** Universal Resolver `resolve(dnsEncode(name), addr(namehash(name)))` for a normalized [name]. */
    fun resolveCall(name: String): ByteArray {
        val addrCall = ADDR_SELECTOR.hexStringToByteArray() + namehash(name)
        return RESOLVE_SELECTOR.hexStringToByteArray() + encodeBytes(dnsEncode(name), addrCall)
    }

    fun namehash(name: String): ByteArray {
        if (name.isEmpty()) return ByteArray(WORD)
        return name.split('.').foldRight(ByteArray(WORD)) { label, node ->
            CryptoUtils.sha3(node + CryptoUtils.sha3(label.toByteArray(Charsets.UTF_8)))
        }
    }

    fun dnsEncode(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_LABEL_BYTES) throw EnsResolutionError.InvalidName()
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        return output.toByteArray()
    }

    /** Canonical `abi.encode(bytes, bytes, …)`. */
    fun encodeBytes(vararg values: ByteArray): ByteArray = tuple(values.map(::dynamicBytes))

    /** Batch gateway `query` result: `abi.encode(bool[] failures, bytes[] responses)`. */
    fun encodeBatchResult(failures: List<Boolean>, responses: List<ByteArray>): ByteArray = tuple(
        listOf(array(failures.map { StaticWord(uint256(if (it) 1 else 0)) }), array(responses.map(::dynamicBytes)))
    )

    /** `HttpError(uint16 status, string message)` revert data. */
    fun httpError(status: Int, message: String): ByteArray =
        HTTP_ERROR_SELECTOR.hexStringToByteArray() + tuple(listOf(StaticWord(uint256(status)), dynamicString(message)))

    /** `Error(string)` revert data. */
    fun error(message: String): ByteArray = ERROR_SELECTOR.hexStringToByteArray() + tuple(listOf(dynamicString(message)))

    private sealed class AbiValue(val encoded: ByteArray)
    private class StaticWord(encoded: ByteArray) : AbiValue(encoded)
    private class Dynamic(encoded: ByteArray) : AbiValue(encoded)

    private fun dynamicBytes(value: ByteArray) = Dynamic(uint256(value.size) + value + ByteArray(padding(value.size)))

    private fun dynamicString(value: String) = dynamicBytes(value.toByteArray(Charsets.UTF_8))

    private fun array(elements: List<AbiValue>) = Dynamic(uint256(elements.size) + tuple(elements))

    // Static values are single words here, so every head is one word long.
    private fun tuple(values: List<AbiValue>): ByteArray {
        var offset = WORD * values.size
        val tails = values.filterIsInstance<Dynamic>().map { it.encoded }
        val heads = values.map { value ->
            if (value is Dynamic) uint256(offset).also { offset += value.encoded.size } else value.encoded
        }
        val output = ByteArrayOutputStream()
        (heads + tails).forEach(output::write)
        return output.toByteArray()
    }

    private fun uint256(value: Int): ByteArray = ByteArray(WORD - 4) + ByteBuffer.allocate(4).putInt(value).array()

    private fun padding(size: Int): Int = (WORD - size % WORD) % WORD
}

/**
 * Bounds-checked ABI decoder; any malformed input fails with [EnsResolutionError.InvalidResponse].
 * Views share one budget, so aliased offsets cannot decode more data than the input holds.
 */
internal class AbiReader private constructor(
    private val data: ByteArray,
    private val start: Int,
    private val budget: Budget
) {
    constructor(data: ByteArray) : this(data, 0, Budget(data.size))

    private class Budget(var remaining: Int)

    private val size: Int
        get() = data.size - start

    fun address(position: Int): Address {
        val word = word(position)
        if (word.take(12).any { it != 0.toByte() }) throw EnsResolutionError.InvalidResponse()
        return Address(word.copyOfRange(12, 32))
    }

    fun bytes4(position: Int): ByteArray {
        val word = word(position)
        if (word.drop(4).any { it != 0.toByte() }) throw EnsResolutionError.InvalidResponse()
        return word.copyOfRange(0, 4)
    }

    fun bytes(position: Int): ByteArray = tail(uint(position))

    fun stringArray(position: Int): List<String> = array(position) { it.tail(0).toString(Charsets.UTF_8) }

    /** Array of dynamic elements; [element] reads each one from a reader that starts at that element. */
    fun <T> array(position: Int, element: (AbiReader) -> T): List<T> {
        val array = at(uint(position))
        val count = array.uint(0)
        val elements = array.at(32)
        if (count > elements.size / 32) throw EnsResolutionError.InvalidResponse()
        return List(count) { element(elements.at(elements.uint(it * 32))) }
    }

    private fun at(offset: Int): AbiReader {
        if (offset < 0 || offset > size) throw EnsResolutionError.InvalidResponse()
        return AbiReader(data, start + offset, budget)
    }

    private fun tail(offset: Int): ByteArray {
        val length = uint(offset)
        // The length word is charged too, so aliased empty tails are not free; canonical tails are disjoint and fit.
        budget.remaining -= 32 + length
        if (budget.remaining < 0) throw EnsResolutionError.InvalidResponse()
        return range(offset + 32, length)
    }

    // No valid offset, length or count exceeds the data, which also keeps the arithmetic above in Int range.
    private fun uint(position: Int): Int {
        val value = BigInteger(1, word(position))
        if (value > BigInteger.valueOf(size.toLong())) throw EnsResolutionError.InvalidResponse()
        return value.toInt()
    }

    private fun word(position: Int): ByteArray = range(position, 32)

    private fun range(from: Int, length: Int): ByteArray {
        if (from < 0 || length < 0 || from > size - length) throw EnsResolutionError.InvalidResponse()
        return data.copyOfRange(start + from, start + from + length)
    }
}
