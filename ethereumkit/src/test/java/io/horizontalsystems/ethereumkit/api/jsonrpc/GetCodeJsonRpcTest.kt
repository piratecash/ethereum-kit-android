package io.horizontalsystems.ethereumkit.api.jsonrpc

import com.google.gson.JsonPrimitive
import io.horizontalsystems.ethereumkit.api.core.RpcResponse
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GetCodeJsonRpcTest {

    private val account = Address("0xd369b1bdb25a40bfb5877dbefcad29889fd90c1d")
    private val rpc = GetCodeJsonRpc(account, DefaultBlockParameter.Latest)

    @Test
    fun params_accountAndLatest_serializeAsEthGetCode() {
        val json = EthereumKit.gson.toJsonTree(rpc).asJsonObject

        assertEquals("eth_getCode", json["method"].asString)
        assertEquals(listOf(account.hex, "latest"), json["params"].asJsonArray.map { it.asString })
    }

    @Test
    fun parseResponse_emptyCode_returnsEmptyBytes() {
        assertArrayEquals(ByteArray(0), rpc.parseResponse(RpcResponse(1, JsonPrimitive("0x"), null), EthereumKit.gson))
    }

    @Test
    fun parseResponse_delegationDesignator_returnsBytes() {
        val code = "0xef010064fc23203fcc4bcf09fc4b30669131333ba7936b"

        assertArrayEquals(code.hexStringToByteArray(), rpc.parseResponse(RpcResponse(1, JsonPrimitive(code), null), EthereumKit.gson))
    }
}
