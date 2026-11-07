package io.horizontalsystems.uniswapkit.v3.router

import io.horizontalsystems.ethereumkit.contracts.BytesArray
import io.horizontalsystems.ethereumkit.contracts.ContractMethod
import io.horizontalsystems.ethereumkit.contracts.ContractMethodFactories
import io.horizontalsystems.ethereumkit.contracts.ContractMethodFactory
import io.horizontalsystems.ethereumkit.contracts.ContractMethodHelper
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function

class MulticallMethod(val methods: List<ContractMethod>) : ContractMethod() {
    override val methodSignature = Companion.methodSignature

    override fun encodedABI(): ByteArray {
        val function = Function(
            "multicall",
            listOf(
                DynamicArray(methods.map { DynamicBytes(it.encodedABI()) })
            ),
            listOf()
        )

        return FunctionEncoder.encode(function).hexStringToByteArray()
    }

    companion object {
        private const val methodSignature = "multicall(bytes[])"
    }

    class Factory(private val factories: ContractMethodFactories) : ContractMethodFactory {
        override val methodId = ContractMethodHelper.getMethodId(methodSignature)

        override fun createMethod(inputArguments: ByteArray): MulticallMethod {
            val elements = ContractMethodHelper.decodeABI(inputArguments, listOf(BytesArray::class))[0] as BytesArray

            // Elements are dispatched through the registry this factory belongs to: nested multicalls would recurse.
            val methods = elements.array
                .filterNot { it.copyOf(4).contentEquals(methodId) }
                .mapNotNull { factories.createMethodFromInput(it) }

            return MulticallMethod(methods)
        }
    }
}
