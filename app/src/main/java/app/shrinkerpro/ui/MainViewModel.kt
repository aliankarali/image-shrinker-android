package app.shrinkerpro.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shrinkerpro.R
import app.shrinkerpro.ShrinkerApplication
import app.shrinkerpro.core.CropField
import app.shrinkerpro.core.CropTarget
import app.shrinkerpro.core.DestinationKey
import app.shrinkerpro.core.FileNames
import app.shrinkerpro.core.MaxSizeField
import app.shrinkerpro.core.OverwriteAnswer
import app.shrinkerpro.core.OverwriteCategory
import app.shrinkerpro.core.OverwritePlanner
import app.shrinkerpro.core.QualityLevel
import app.shrinkerpro.core.SessionFormat
import app.shrinkerpro.core.SessionState
import app.shrinkerpro.data.AppSettings
import app.shrinkerpro.data.SettingsRepository
import app.shrinkerpro.engine.Destination
import app.shrinkerpro.engine.FolderCache
import app.shrinkerpro.engine.InputSource
import app.shrinkerpro.engine.JobSettings
import app.shrinkerpro.engine.OutputFolder
import app.shrinkerpro.engine.ShrinkException
import app.shrinkerpro.engine.ShrinkPlan
import app.shrinkerpro.engine.ShrinkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ResultRow(
    val id: Long,
    val name: String,
    val uri: Uri,
    val mimeType: String,
    val originalBytes: Long,
    val shrunkBytes: Long,
    val savedPercent: Int,
    val keptOriginal: Boolean,
)

/** A batch in progress. [total] is 0 while inputs are still being gathered. */
data class Progress(val done: Int, val total: Int)

data class OverwritePrompt(
    val category: OverwriteCategory,
    val names: List<String>,
    /** Set when every affected file is in one folder. */
    val folderName: String?,
    val unaffectedCount: Int,
)

sealed interface Problem {
    data class Shrink(val error: ShrinkException) : Problem
    data object CropIncomplete : Problem
    data object NoImages : Problem
    data class Unexpected(val fileName: String, val message: String) : Problem
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShrinkerApplication
    val repository: SettingsRepository = app.settings

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ---- results -----------------------------------------------------------------------

    var rows by mutableStateOf(emptyList<ResultRow>()); private set
    var fileCount by mutableIntStateOf(0); private set
    var bytesSaved by mutableLongStateOf(0L); private set
    var progress by mutableStateOf<Progress?>(null); private set
    var problems by mutableStateOf(emptyList<Problem>()); private set
    var overwritePrompt by mutableStateOf<OverwritePrompt?>(null); private set

    private var overwriteAnswer: CompletableDeferred<OverwriteAnswer>? = null
    private val batches = Mutex()
    private var nextRowId = 0L

    private val _requestNotificationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestNotificationPermission: SharedFlow<Unit> = _requestNotificationPermission

    // ---- session settings: typed in the window, never written to disk ---------------------

    var sessionFormat by mutableStateOf<SessionFormat?>(null)
    var sessionQuality by mutableStateOf<QualityLevel?>(null)
    var cropMode by mutableStateOf(CropTarget.Mode.RATIO)
    var isSessionPanelOpen by mutableStateOf(false); private set

    var maxSizeText by mutableStateOf(""); private set
    var cropWidthText by mutableStateOf(""); private set
    var cropHeightText by mutableStateOf(""); private set

    fun updateMaxSize(text: String) { maxSizeText = MaxSizeField.filter(text) }
    fun updateCropWidth(text: String) { cropWidthText = CropField.filter(text) }
    fun updateCropHeight(text: String) { cropHeightText = CropField.filter(text) }

    val maxDimension: Int? get() = MaxSizeField.dimension(maxSizeText)
    val cropTarget: CropTarget? get() = CropField.target(cropWidthText, cropHeightText, cropMode)
    val cropIsIncomplete: Boolean get() = CropField.isHalfFilled(cropWidthText, cropHeightText)

    fun isSessionModified(stored: QualityLevel): Boolean =
        SessionState.isModified(sessionFormat, sessionQuality, stored, maxDimension, cropTarget)

    /**
     * Opens or closes the session panel. Closing is refused while the crop has
     * only one side: nothing can be cropped from one number, and closing would
     * hide that the next batch is about to ignore it.
     */
    fun setSessionPanel(open: Boolean): Boolean {
        if (!open) {
            if (cropIsIncomplete) return false
            maxSizeText = MaxSizeField.committed(maxSizeText)
            cropWidthText = CropField.committed(cropWidthText)
            cropHeightText = CropField.committed(cropHeightText)
        }
        isSessionPanelOpen = open
        return true
    }

    fun resetSession() {
        sessionFormat = null
        sessionQuality = null
        maxSizeText = ""
        cropWidthText = ""
        cropHeightText = ""
        cropMode = CropTarget.Mode.RATIO
    }

    // ---- actions ------------------------------------------------------------------------

    fun updateSettings(change: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.change() }
    }

    fun clearHistory() {
        rows = emptyList()
        fileCount = 0
        bytesSaved = 0
    }

    fun dismissProblems() { problems = emptyList() }

    fun answerOverwrite(answer: OverwriteAnswer) {
        val pending = overwriteAnswer
        overwriteAnswer = null
        overwritePrompt = null
        pending?.complete(answer)
    }

    fun handle(sources: List<InputSource>) {
        if (sources.isEmpty()) return
        if (cropIsIncomplete) {
            isSessionPanelOpen = true
            problems = problems + Problem.CropIncomplete
            return
        }
        if (isSessionPanelOpen) setSessionPanel(false)
        // Snapshot the session now: what's on screen when the files arrive is
        // what they're shrunk with, even if the panel changes mid-batch.
        val session = SessionSnapshot(sessionFormat, sessionQuality, maxDimension, cropTarget)
        viewModelScope.launch { batches.withLock { process(sources, session) } }
    }

    private data class SessionSnapshot(
        val format: SessionFormat?,
        val quality: QualityLevel?,
        val maxDimension: Int?,
        val crop: CropTarget?,
    )

    private suspend fun process(sources: List<InputSource>, session: SessionSnapshot) {
        val settings = repository.current()
        maybeAskForNotifications(settings)
        if (settings.clearList) clearHistory()
        progress = Progress(0, 0)
        try {
            val files = withContext(Dispatchers.IO) {
                runCatching { app.inputs.resolve(sources) }.getOrElse { emptyList() }
            }
            if (files.isEmpty()) {
                problems = problems + Problem.NoImages
                return
            }

            val job = JobSettings(
                location = settings.location,
                customFolder = settings.customFolder,
                useSubfolder = settings.useSubfolder,
                keepOriginal = settings.keepOriginal,
                rules = settings.rules,
                metadata = settings.metadata,
                quality = (session.quality ?: settings.quality).settings,
                sessionFormat = session.format,
                maxDimension = session.maxDimension,
                crop = session.crop,
            )
            val folders = FolderCache(app)
            val failures = mutableListOf<Problem>()
            var plans = withContext(Dispatchers.IO) {
                files.mapNotNull { file ->
                    try {
                        app.engine.plan(file, job, folders)
                    } catch (e: ShrinkException) {
                        failures += Problem.Shrink(e)
                        null
                    }
                }
            }
            if (settings.warnBeforeOverwrite) plans = confirmOverwrites(plans, folders)

            val succeeded = mutableListOf<ShrinkResult>()
            for ((index, plan) in plans.withIndex()) {
                progress = Progress(index + 1, plans.size)
                try {
                    val result = withContext(Dispatchers.IO) { app.engine.shrink(plan, job) }
                    record(result)
                    succeeded += result
                } catch (e: ShrinkException) {
                    failures += Problem.Shrink(e)
                } catch (e: Exception) {
                    failures += Problem.Unexpected(plan.input.name, e.message ?: e.javaClass.simpleName)
                }
            }
            if (failures.isNotEmpty()) problems = problems + failures
            if (settings.notification && succeeded.isNotEmpty()) notify(succeeded)
        } finally {
            progress = null
        }
    }

    private fun record(result: ShrinkResult) {
        rows = listOf(
            ResultRow(
                id = nextRowId++,
                name = result.outputName,
                uri = result.outputUri,
                mimeType = result.mimeType,
                originalBytes = result.originalBytes,
                shrunkBytes = result.shrunkBytes,
                savedPercent = result.savedPercent,
                keptOriginal = result.keptOriginal,
            ),
        ) + rows
        fileCount += 1
        bytesSaved += result.originalBytes - result.shrunkBytes
    }

    /**
     * Asks once about originals that would be overwritten, then once about other
     * existing files — the first is the destructive question and deserves its
     * own answer.
     */
    private suspend fun confirmOverwrites(initial: List<ShrinkPlan>, folders: FolderCache): List<ShrinkPlan> {
        var plans = initial
        data class Facts(val exists: Boolean, val original: Boolean)
        val facts = withContext(Dispatchers.IO) {
            plans.associateWith { Facts(app.engine.existing(it.destination) != null, app.engine.overwritesInput(it)) }
        }
        val (originals, existing) = OverwritePlanner.classify(
            plans, existsOnDisk = { facts.getValue(it).exists }, isOriginal = { facts.getValue(it).original },
        )
        val unaffected = plans.size - originals.size - existing.size
        val claims = plans.groupingBy { it.destination.key }.eachCount().toMutableMap()
        val folderByKey: Map<String, OutputFolder> =
            plans.mapNotNull { p -> p.destination.folder?.let { it.key to it } }.toMap()

        for ((category, group) in listOf(OverwriteCategory.ORIGINAL to originals, OverwriteCategory.EXISTING_FILE to existing)) {
            if (group.isEmpty()) continue
            val sharedFolder = group.map { it.destination.folder }.distinct().singleOrNull()
            val folderName = sharedFolder?.let { withContext(Dispatchers.IO) { runCatching { it.displayName }.getOrNull() } }
            val answer = ask(OverwritePrompt(category, group.map { it.destination.name }, folderName, unaffected))
            var asked: List<ShrinkPlan> = group

            plans = withContext(Dispatchers.IO) {
                if (answer == OverwriteAnswer.KEEP_BOTH) {
                    // A file replaced in place has no folder to put a twin in;
                    // its copy goes to Pictures/Shrinker Pro instead.
                    val moved = asked.filter { it.destination.inPlace != null }.associateWith { plan ->
                        val pictures = folders.pictures(null)
                        val name = FileNames.unique(plan.destination.name) { pictures.find(it) != null }
                        claims[plan.destination.key] = (claims[plan.destination.key] ?: 1) - 1
                        plan.writing(Destination(pictures, name, null)).also {
                            claims[it.destination.key] = (claims[it.destination.key] ?: 0) + 1
                        }
                    }
                    plans = plans.map { moved[it] ?: it }
                    asked = asked.filter { it !in moved }
                }
                OverwritePlanner.apply(
                    answer = answer,
                    plans = plans,
                    group = asked,
                    keyOf = { it.destination.key },
                    existsOnDisk = { key: DestinationKey -> folderByKey[key.folder]?.find(key.name) != null },
                    claims = claims,
                ) { plan, key -> plan.writing(plan.destination.copy(name = key.name)) }
            }
        }
        return plans
    }

    private suspend fun ask(prompt: OverwritePrompt): OverwriteAnswer {
        overwriteAnswer?.complete(OverwriteAnswer.SKIP)
        val deferred = CompletableDeferred<OverwriteAnswer>()
        overwriteAnswer = deferred
        overwritePrompt = prompt
        return deferred.await()
    }

    private suspend fun maybeAskForNotifications(settings: AppSettings) {
        if (!settings.notification || settings.askedNotificationPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        repository.setAskedNotificationPermission()
        _requestNotificationPermission.tryEmit(Unit)
    }

    private fun notify(results: List<ShrinkResult>) {
        val res = app.resources
        val title = res.getQuantityString(R.plurals.notification_title, results.size, results.size)
        val body = if (results.size == 1) {
            results.single().outputName
        } else {
            val saved = results.sumOf { it.originalBytes - it.shrunkBytes }.coerceAtLeast(0)
            res.getString(R.string.notification_body_saved, Formatting.bytes(res, saved))
        }
        app.notifier.notify(title, body)
    }

    override fun onCleared() {
        overwriteAnswer?.complete(OverwriteAnswer.SKIP)
    }
}
