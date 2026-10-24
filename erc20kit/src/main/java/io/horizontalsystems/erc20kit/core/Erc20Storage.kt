package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.erc20kit.models.TokenBalance
import java.math.BigInteger

class Erc20Storage(
        database: Erc20KitDatabase
) : ITokenBalanceStorage {

    private val tokenBalanceDao = database.tokenBalanceDao

    override suspend fun getBalance(): BigInteger? {
        return tokenBalanceDao.getBalance()?.value
    }

    override suspend fun save(balance: BigInteger) {
        tokenBalanceDao.insert(TokenBalance(balance))
    }

}
