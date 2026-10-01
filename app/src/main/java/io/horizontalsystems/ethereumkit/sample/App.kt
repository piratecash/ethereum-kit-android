package io.horizontalsystems.ethereumkit.sample

import android.app.Application
import co.touchlab.kermit.Logger
import com.facebook.stetho.Stetho
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.reactivex.plugins.RxJavaPlugins

class App : Application() {

    private val logger = Logger.withTag("Sample")

    override fun onCreate() {
        super.onCreate()
        instance = this

        RxJavaPlugins.setErrorHandler { e: Throwable? ->
            logger.w { "RxJava ErrorHandler: ${e?.message}" }
        }

        // Enable debug bridge
        Stetho.initializeWithDefaults(this)
        EthereumKit.init()
    }

    companion object {
        lateinit var instance: App
            private set
    }

}
