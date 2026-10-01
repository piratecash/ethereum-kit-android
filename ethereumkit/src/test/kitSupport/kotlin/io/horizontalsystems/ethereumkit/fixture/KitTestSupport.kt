package io.horizontalsystems.ethereumkit.fixture

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.RpcSource
import io.horizontalsystems.ethereumkit.models.TransactionSource
import java.io.File
import java.net.URI

// Shared by the desktop tests of every module and the device tests; no platform dependencies.

internal val databaseKey = ByteArray(32) { it.toByte() }
internal val otherDatabaseKey = ByteArray(32) { (it + 1).toByte() }

/** Copies the plaintext fixture [resourceName] from `src/test/resources/databases` to [target]. */
internal fun copyFixture(resourceName: String, target: File): File {
    val source = checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream("databases/$resourceName")) {
        "Missing fixture $resourceName"
    }
    target.parentFile?.mkdirs()
    source.use { input -> target.outputStream().use(input::copyTo) }
    return target
}

internal fun hasPlaintextSqliteHeader(file: File): Boolean =
    file.inputStream().use { input -> ByteArray(16).also { input.read(it) } }.contentEquals("SQLite format 3\u0000".encodeToByteArray())

/** A watch-only kit that never starts, so it only opens its databases. */
internal suspend fun watchKit(
    context: PlatformContext,
    walletId: String,
    key: ByteArray,
    chain: Chain = Chain.Ethereum,
): EthereumKit = EthereumKit.getInstance(
    application = context,
    address = Address("0x1111111111111111111111111111111111111111"),
    chain = chain,
    rpcSource = RpcSource.Http(listOf(URI("http://127.0.0.1:1")), null),
    transactionSource = TransactionSource.ethereum(listOf("key")),
    walletId = walletId,
    databaseKey = key,
)
