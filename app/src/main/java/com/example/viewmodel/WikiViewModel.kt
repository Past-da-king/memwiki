package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface IngestState {
    object Idle : IngestState
    object Loading : IngestState
    data class Success(val plan: WikiUpdatePlan) : IngestState
    data class Error(val message: String) : IngestState
}

sealed interface QueryState {
    object Idle : QueryState
    object Loading : QueryState
    data class Success(val answer: String) : QueryState
    data class Error(val message: String) : QueryState
}

sealed interface LintState {
    object Idle : LintState
    object Loading : LintState
    data class Success(val report: WikiLintReport) : LintState
    data class Error(val message: String) : LintState
}

sealed interface ModelListState {
    object Idle : ModelListState
    object Loading : ModelListState
    data class Success(val models: List<GeminiModelInfo>) : ModelListState
    data class Error(val message: String) : ModelListState
}

// Live snapshot of what the agent is currently doing. The UI subscribes to this to render the
// "Agent working" banner on the home tab and the bottom-sheet stream view.
data class AgentActivity(
    val isActive: Boolean = false,
    val operation: String = "",          // e.g. "Compiling note"
    val startedAt: Long = 0L,
    val thinking: String = "",           // accumulated chain-of-thought text
    val answer: String = "",             // accumulated answer text
    val lastError: String? = null
) {
    val elapsedSec: Long get() = if (isActive && startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0
}

class WikiViewModel(application: Application) : AndroidViewModel(application) {
    private val database = WikiDatabase.getDatabase(application)
    private val repository = WikiRepository(application, database.wikiDao)

    val wikiPages: StateFlow<List<WikiPage>> = repository.allPages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawSources: StateFlow<List<RawSource>> = repository.allSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReminders: StateFlow<List<Reminder>> = repository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeReminders: StateFlow<List<Reminder>> = repository.activeReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ingestState = MutableStateFlow<IngestState>(IngestState.Idle)
    val ingestState = _ingestState.asStateFlow()

    private val _queryState = MutableStateFlow<QueryState>(QueryState.Idle)
    val queryState = _queryState.asStateFlow()

    private val _lintState = MutableStateFlow<LintState>(LintState.Idle)
    val lintState = _lintState.asStateFlow()

    private val _selectedPage = MutableStateFlow<WikiPage?>(null)
    val selectedPage = _selectedPage.asStateFlow()

    // Screen Navigation Tab Index
    private val _currentTab = MutableStateFlow(0) // 0: Wiki Index, 1: Ingest, 2: Query Chat, 3: Diagnostics Lint, 4: History log
    val currentTab = _currentTab.asStateFlow()

    // User Profile settings loaded from SharedPreferences
    private val prefs = application.getSharedPreferences("wiki_settings", Context.MODE_PRIVATE)
    
    private val _userName = MutableStateFlow(prefs.getString("user_name", "John Doe") ?: "John Doe")
    val userName = _userName.asStateFlow()

    private val _userBio = MutableStateFlow(prefs.getString("user_bio", "Curator of Evolving Insights") ?: "Curator of Evolving Insights")
    val userBio = _userBio.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString("gemini_api_key", "") ?: "")
    val apiKey = _apiKey.asStateFlow()

    // User-selected model. Empty by default — the Settings screen prompts the user to fetch
    // the live model list from the Gemini API and pick one. No model name is hardcoded anywhere.
    private val _selectedModel = MutableStateFlow(prefs.getString("gemini_model", "") ?: "")
    val selectedModel = _selectedModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<GeminiModelInfo>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _modelListState = MutableStateFlow<ModelListState>(ModelListState.Idle)
    val modelListState = _modelListState.asStateFlow()

    private val _agentActivity = MutableStateFlow(AgentActivity())
    val agentActivity = _agentActivity.asStateFlow()

    // First-launch onboarding gate. True once the user has supplied an API key and picked a model.
    private val _onboardingComplete = MutableStateFlow(prefs.getBoolean("onboarding_complete", false))
    val onboardingComplete = _onboardingComplete.asStateFlow()

    fun completeOnboarding() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        _onboardingComplete.value = true
    }

    // User-selected theme mode. Default = neutral black/white. Adaptive pulls Material You from
    // the device wallpaper. Editorial is the warm-dark parchment palette.
    private val _appThemeMode = MutableStateFlow(
        runCatching { AppThemeMode.valueOf(prefs.getString("app_theme_mode", "DEFAULT") ?: "DEFAULT") }
            .getOrDefault(AppThemeMode.DEFAULT)
    )
    val appThemeMode = _appThemeMode.asStateFlow()

    fun setAppThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("app_theme_mode", mode.name).apply()
        _appThemeMode.value = mode
    }

    // Persistent banner dismissal — once the user swipes a reminder away it must stay gone
    // until a NEW reminder (different id) appears. Stored in prefs so it survives navigation,
    // backgrounding, and restarts.
    private val _dismissedReminderId = MutableStateFlow(prefs.getInt("dismissed_reminder_id", -1))
    val dismissedReminderId = _dismissedReminderId.asStateFlow()

    fun dismissReminderBanner(reminderId: Int) {
        prefs.edit().putInt("dismissed_reminder_id", reminderId).apply()
        _dismissedReminderId.value = reminderId
    }

    fun updateProfile(name: String, bio: String) {
        prefs.edit().putString("user_name", name).putString("user_bio", bio).apply()
        _userName.value = name
        _userBio.value = bio
        
        viewModelScope.launch {
            database.wikiDao.insertLog(
                ActivityLog(
                    type = "maintenance",
                    summary = "Updated user profile",
                    detail = "User changed name to \"$name\" and tagline to \"$bio\"."
                )
            )
        }
    }

    fun updateApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
        _apiKey.value = key.trim()

        viewModelScope.launch {
            database.wikiDao.insertLog(
                ActivityLog(
                    type = "maintenance",
                    summary = "Updated Gemini API Key",
                    detail = "User customized Gemini client API verification strings."
                )
            )
        }
    }

    fun setSelectedModel(modelId: String) {
        val cleaned = modelId.trim()
        prefs.edit().putString("gemini_model", cleaned).apply()
        _selectedModel.value = cleaned
    }

    fun refreshAvailableModels() {
        viewModelScope.launch {
            _modelListState.value = ModelListState.Loading
            repository.listAvailableModels(_apiKey.value)
                .onSuccess { models ->
                    _availableModels.value = models
                    _modelListState.value = ModelListState.Success(models)
                    // If user hasn't picked one yet and the fetched list isn't empty, leave selection
                    // empty rather than auto-picking — we never preselect on the user's behalf.
                }
                .onFailure { e ->
                    _modelListState.value = ModelListState.Error(e.localizedMessage ?: "Failed to fetch model list")
                }
        }
    }

    fun switchTab(index: Int) {
        _currentTab.value = index
        if (index != 0) {
            _selectedPage.value = null // Reset deep linked page selection when moving away from Wiki tab
        }
    }

    fun selectPage(page: WikiPage?) {
        _selectedPage.value = page
        if (page != null) {
            _currentTab.value = 0 // Auto focus on Wiki Index tab to show page details
        }
    }

    fun selectPageByTitle(title: String) {
        viewModelScope.launch {
            val page = repository.getPage(title)
            if (page != null) {
                _selectedPage.value = page
                _currentTab.value = 0
            }
        }
    }

    fun ingestNote(content: String, audioPath: String? = null, imagePaths: List<String> = emptyList(), onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _ingestState.value = IngestState.Loading
            // Reset live activity; UI banner reads from this StateFlow.
            _agentActivity.value = AgentActivity(
                isActive = true,
                operation = "Compiling note into wiki",
                startedAt = System.currentTimeMillis()
            )
            repository.ingestNote(
                apiKey = _apiKey.value,
                modelId = _selectedModel.value,
                content = content,
                audioPath = audioPath,
                imagePaths = imagePaths,
                onChunk = { chunk ->
                    // Append each streamed chunk to the live activity state. UI will recompose.
                    val current = _agentActivity.value
                    _agentActivity.value = if (chunk.isThinking) {
                        current.copy(thinking = current.thinking + chunk.text)
                    } else {
                        current.copy(answer = current.answer + chunk.text)
                    }
                }
            )
                .onSuccess { plan ->
                    _ingestState.value = IngestState.Success(plan)
                    _agentActivity.value = _agentActivity.value.copy(isActive = false)
                    onComplete()
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Unknown compilation error"
                    _ingestState.value = IngestState.Error(msg)
                    _agentActivity.value = _agentActivity.value.copy(isActive = false, lastError = msg)
                }
        }
    }

    fun queryWiki(question: String) {
        viewModelScope.launch {
            _queryState.value = QueryState.Loading
            repository.queryWiki(_apiKey.value, _selectedModel.value, question)
                .onSuccess { answer ->
                    _queryState.value = QueryState.Success(answer)
                }
                .onFailure { error ->
                    _queryState.value = QueryState.Error(error.localizedMessage ?: "Unknown query retrieval error")
                }
        }
    }

    fun runLint() {
        viewModelScope.launch {
            _lintState.value = LintState.Loading
            _agentActivity.value = AgentActivity(
                isActive = true,
                operation = "Auditing wiki",
                startedAt = System.currentTimeMillis()
            )
            repository.lintWiki(
                apiKey = _apiKey.value,
                modelId = _selectedModel.value,
                onChunk = { chunk ->
                    val current = _agentActivity.value
                    _agentActivity.value = if (chunk.isThinking) {
                        current.copy(thinking = current.thinking + chunk.text)
                    } else {
                        current.copy(answer = current.answer + chunk.text)
                    }
                }
            )
                .onSuccess { report ->
                    _lintState.value = LintState.Success(report)
                    _agentActivity.value = _agentActivity.value.copy(isActive = false)
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Quality assurance diagnostic pass failed"
                    _lintState.value = LintState.Error(msg)
                    _agentActivity.value = _agentActivity.value.copy(isActive = false, lastError = msg)
                }
        }
    }

    // Applies the most recent audit's fixes — only callable if lintState is Success.
    fun applyLintFixes() {
        val report = (_lintState.value as? LintState.Success)?.report ?: return
        viewModelScope.launch {
            _agentActivity.value = AgentActivity(
                isActive = true,
                operation = "Applying audit fixes",
                startedAt = System.currentTimeMillis()
            )
            repository.applyLintFixes(
                apiKey = _apiKey.value,
                modelId = _selectedModel.value,
                report = report,
                onChunk = { chunk ->
                    val current = _agentActivity.value
                    _agentActivity.value = if (chunk.isThinking) {
                        current.copy(thinking = current.thinking + chunk.text)
                    } else {
                        current.copy(answer = current.answer + chunk.text)
                    }
                }
            )
                .onSuccess {
                    _agentActivity.value = _agentActivity.value.copy(isActive = false)
                    // Clear the lint state so the user knows fixes were applied.
                    _lintState.value = LintState.Idle
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Fix pass failed"
                    _agentActivity.value = _agentActivity.value.copy(isActive = false, lastError = msg)
                }
        }
    }

    fun toggleReminder(id: Int, isCompleted: Boolean) {
        viewModelScope.launch {
            repository.toggleReminder(id, isCompleted)
        }
    }

    fun deleteReminder(id: Int) {
        viewModelScope.launch {
            repository.deleteReminder(id)
        }
    }

    // Direct in-place edit of an existing reminder. The DAO's insertReminder uses
    // OnConflictStrategy.REPLACE, so inserting with the same id overwrites that row.
    fun updateReminder(reminder: Reminder) {
        viewModelScope.launch {
            database.wikiDao.insertReminder(reminder)
        }
    }

    fun clearWiki() {
        viewModelScope.launch {
            repository.clearWiki()
            _selectedPage.value = null
        }
    }

    fun resetIngestState() {
        _ingestState.value = IngestState.Idle
    }

    fun resetQueryState() {
        _queryState.value = QueryState.Idle
    }

    fun resetLintState() {
        _lintState.value = LintState.Idle
    }

    // --- ZIP EXPORT & IMPORT ---
    fun exportWikiToZip(context: Context) {
        viewModelScope.launch {
            val dbDao = database.wikiDao
            val pages = dbDao.getAllPages()
            val sources = dbDao.getAllSources()
            val logs = dbDao.getAllLogs()
            val moshi = GeminiClient.getMoshiInstance()
            WikiBackupManager.exportToZipAndShare(context, pages, sources, logs, moshi)
        }
    }

    fun importWikiFromZip(context: Context, uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val dbDao = database.wikiDao
            val moshi = GeminiClient.getMoshiInstance()
            WikiBackupManager.importFromZip(context, uri, moshi, dbDao)
                .onSuccess { message ->
                    onResult(message)
                }
                .onFailure { error ->
                    onResult("Error: ${error.localizedMessage ?: "Failed to import backup"}")
                }
        }
    }

    // --- RAW SOURCE (NOTE) CURATION ---
    fun deleteRawSource(id: Int) {
        viewModelScope.launch {
            database.wikiDao.deleteSourceById(id)
            database.wikiDao.insertLog(
                ActivityLog(
                    type = "maintenance",
                    summary = "Deleted raw note",
                    detail = "Removed raw source note index $id from local database database."
                )
            )
        }
    }

    fun saveRawSource(id: Int, content: String, audioPath: String? = null, imagePaths: List<String> = emptyList()) {
        viewModelScope.launch {
            val source = RawSource(id = id, content = content, timestamp = System.currentTimeMillis(), audioPath = audioPath, imagePaths = RawSource.joinPaths(imagePaths))
            database.wikiDao.insertSource(source)
            database.wikiDao.insertLog(
                ActivityLog(
                    type = "ingest",
                    summary = "Updated note",
                    detail = "Edited raw note outline to keep source ideas synchronized."
                )
            )
        }
    }

    fun createRawSource(content: String, audioPath: String? = null, imagePaths: List<String> = emptyList()) {
        viewModelScope.launch {
            val source = RawSource(content = content, timestamp = System.currentTimeMillis(), audioPath = audioPath, imagePaths = RawSource.joinPaths(imagePaths))
            database.wikiDao.insertSource(source)
            database.wikiDao.insertLog(
                ActivityLog(
                    type = "ingest",
                    summary = "Added new note",
                    detail = "Manually wrote directly to knowledge inbox repository."
                )
                
            )
        }
    }
}

class WikiViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WikiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WikiViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
