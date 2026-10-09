package app.shrinkerpro

import android.app.Application
import android.content.Context
import app.shrinkerpro.data.SettingsRepository
import app.shrinkerpro.engine.InputResolver
import app.shrinkerpro.engine.ShrinkEngine

/** Holds the app-wide singletons. */
class ShrinkerApplication : Application() {
    val settings by lazy { SettingsRepository(this) }
    val engine by lazy { ShrinkEngine(this) }
    val inputs by lazy { InputResolver(this) }
    val notifier by lazy { Notifier(this) }
}

object App {
    fun from(context: Context): ShrinkerApplication = context.applicationContext as ShrinkerApplication
}
