package io.horizontalsystems.ethereumkit.api.core

import io.horizontalsystems.ethereumkit.network.ConnectionManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ApiRpcSyncerTest {

    @Test
    fun start_afterStop_reRegistersConnectionListener() {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val syncer = ApiRpcSyncer(mockk(relaxed = true), connectionManager, syncInterval = 15)

        syncer.stop()
        syncer.start()

        verify(exactly = 1) { connectionManager.removeListener(syncer) }
        verify(exactly = 2) { connectionManager.addListener(syncer) }
        syncer.stop()
    }
}
