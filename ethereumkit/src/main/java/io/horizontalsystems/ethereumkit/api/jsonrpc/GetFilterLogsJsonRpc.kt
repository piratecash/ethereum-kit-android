package io.horizontalsystems.ethereumkit.api.jsonrpc

import com.google.gson.reflect.TypeToken
import io.horizontalsystems.ethereumkit.models.TransactionLog
import java.lang.reflect.Type

class GetFilterLogsJsonRpc(
    @Transient val filterId: String
) : JsonRpc<ArrayList<TransactionLog>>(
    method = "eth_getFilterLogs",
    params = listOf(filterId)
) {
    @Transient
    override val typeOfResult: Type = object : TypeToken<ArrayList<TransactionLog>>() {}.type
}
