package io.github.aliankarali.imageshrinker

import android.app.Application
import android.content.Context
import io.github.aliankarali.imageshrinker.data.SettingsRepository
import io.github.aliankarali.imageshrinker.engine.InputResolver
import io.github.aliankarali.imageshrinker.engine.ShrinkEngine

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
