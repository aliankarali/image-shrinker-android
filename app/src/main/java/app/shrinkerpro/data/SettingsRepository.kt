package app.shrinkerpro.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.shrinkerpro.core.ConversionFormat
import app.shrinkerpro.core.ConversionRules
import app.shrinkerpro.core.ConversionTarget
import app.shrinkerpro.core.MetadataPolicy
import app.shrinkerpro.core.QualityLevel
import app.shrinkerpro.core.SaveLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Everything the Settings screen stores. Defaults mirror the desktop app. */
data class AppSettings(
    val notification: Boolean = true,
    val location: SaveLocation = SaveLocation.PICTURES,
    val customFolder: Uri? = null,
    val clearList: Boolean = false,
    /** Keep the original and write a `.min` copy (or, away from the originals, add `.min`). */
    val keepOriginal: Boolean = true,
    val useSubfolder: Boolean = false,
    val rules: ConversionRules = ConversionRules(),
    val metadata: MetadataPolicy = MetadataPolicy.ALL,
    val quality: QualityLevel = QualityLevel.STANDARD,
    val warnBeforeOverwrite: Boolean = true,
    /** Whether the notification permission prompt has been shown once already. */
    val askedNotificationPermission: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    // Key names follow the desktop app's UserDefaults keys where one exists.
    private object Keys {
        val notification = booleanPreferencesKey("notification")
        val location = stringPreferencesKey("location")
        val customFolder = stringPreferencesKey("savepath")
        val clearList = booleanPreferencesKey("clearlist")
        val keepOriginal = booleanPreferencesKey("suffix")
        val useSubfolder = booleanPreferencesKey("subfolder")
        val conversionPNG = stringPreferencesKey("conversionPNG")
        val conversionJPEG = stringPreferencesKey("conversionJPEG")
        val conversionHEIC = stringPreferencesKey("conversionHEIC")
        val conversionWebP = stringPreferencesKey("conversionWebP")
        val conversionAVIF = stringPreferencesKey("conversionAVIF")
        val metadata = stringPreferencesKey("metadata")
        val quality = stringPreferencesKey("quality")
        val warnBeforeOverwrite = booleanPreferencesKey("warnBeforeOverwrite")
        val askedNotificationPermission = booleanPreferencesKey("askedNotificationPermission")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            notification = p[Keys.notification] ?: d.notification,
            location = SaveLocation.fromKey(p[Keys.location]) ?: d.location,
            customFolder = p[Keys.customFolder]?.let(Uri::parse),
            clearList = p[Keys.clearList] ?: d.clearList,
            keepOriginal = p[Keys.keepOriginal] ?: d.keepOriginal,
            useSubfolder = p[Keys.useSubfolder] ?: d.useSubfolder,
            rules = ConversionRules(
                png = ConversionTarget.fromKey(p[Keys.conversionPNG]) ?: d.rules.png,
                jpeg = ConversionTarget.fromKey(p[Keys.conversionJPEG]) ?: d.rules.jpeg,
                heic = ConversionFormat.fromKey(p[Keys.conversionHEIC]) ?: d.rules.heic,
                webp = ConversionTarget.fromKey(p[Keys.conversionWebP]) ?: d.rules.webp,
                avif = ConversionTarget.fromKey(p[Keys.conversionAVIF]) ?: d.rules.avif,
            ),
            metadata = MetadataPolicy.fromKey(p[Keys.metadata]) ?: d.metadata,
            quality = QualityLevel.fromKey(p[Keys.quality]) ?: d.quality,
            warnBeforeOverwrite = p[Keys.warnBeforeOverwrite] ?: d.warnBeforeOverwrite,
            askedNotificationPermission = p[Keys.askedNotificationPermission] ?: d.askedNotificationPermission,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setNotification(value: Boolean) = put(Keys.notification, value)
    suspend fun setLocation(value: SaveLocation) = put(Keys.location, value.key)
    suspend fun setClearList(value: Boolean) = put(Keys.clearList, value)
    suspend fun setKeepOriginal(value: Boolean) = put(Keys.keepOriginal, value)
    suspend fun setUseSubfolder(value: Boolean) = put(Keys.useSubfolder, value)
    suspend fun setMetadata(value: MetadataPolicy) = put(Keys.metadata, value.key)
    suspend fun setQuality(value: QualityLevel) = put(Keys.quality, value.key)
    suspend fun setWarnBeforeOverwrite(value: Boolean) = put(Keys.warnBeforeOverwrite, value)
    suspend fun setAskedNotificationPermission() = put(Keys.askedNotificationPermission, true)

    suspend fun setCustomFolder(uri: Uri) {
        context.dataStore.edit {
            it[Keys.customFolder] = uri.toString()
            it[Keys.location] = SaveLocation.CUSTOM.key
        }
    }

    suspend fun setPngRule(value: ConversionTarget) = put(Keys.conversionPNG, value.key)
    suspend fun setJpegRule(value: ConversionTarget) = put(Keys.conversionJPEG, value.key)
    suspend fun setHeicRule(value: ConversionFormat) = put(Keys.conversionHEIC, value.key)
    suspend fun setWebpRule(value: ConversionTarget) = put(Keys.conversionWebP, value.key)
    suspend fun setAvifRule(value: ConversionTarget) = put(Keys.conversionAVIF, value.key)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
