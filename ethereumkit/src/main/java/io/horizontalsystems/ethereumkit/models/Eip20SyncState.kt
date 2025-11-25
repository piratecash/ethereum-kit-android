package io.horizontalsystems.ethereumkit.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Eip20SyncState(
    @PrimaryKey
    val contractAddress: String,
    val lastScannedBlock: Long
)
