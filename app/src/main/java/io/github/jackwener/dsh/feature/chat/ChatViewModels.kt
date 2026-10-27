package io.github.jackwener.dsh.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.engine.NoApiKeyException
import io.github.jackwener.dsh.core.session.ApprovalOutcome
import io.github.jackwener.dsh.core.session.ConversationState
import io.github.jackwener.dsh.core.session.PendingApproval
import io.github.jackwener.dsh.core.session.PendingQuestion
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(private val store: SessionStore) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> = store.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
}

/** Shared by the full conversation screen and the quick panel. */
@OptIn(ExperimentalCoroutinesApi::class)
open class ConversationController(private val store: SessionStore, private val engine: EngineManager, initialId: String?) : ViewModel() {
    private val sessionId = MutableStateFlow(initialId ?: store.newSessionId())
    val id: String get() = sessionId.value
    val engineState: StateFlow<EngineState> = engine.state
    val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ConversationState> = sessionId.flatMapLatest { id -> flow { emitAll(store.state(id)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationState(sessionId.value))

    fun send(text: String, context: String? = null) = viewModelScope.launch {
        error.value = null
        try {
            store.prompt(sessionId.value, if (context != null) "$context\n\n$text" else text)
        } catch (e: NoApiKeyException) { error.value = "no_key" }
        catch (e: Exception) { error.value = e.message ?: e.javaClass.simpleName }
    }

    fun approve(a: PendingApproval, outcome: ApprovalOutcome) = store.answerApproval(a.requestKey, outcome)
    fun answer(q: PendingQuestion, answers: Map<String, Pair<List<String>, String?>>) = store.answerQuestion(q.requestKey, answers)
    fun stop() = viewModelScope.launch { engine.stop(); store.onEngineLost() }
    fun newSession() { sessionId.value = store.newSessionId() }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(store: SessionStore, engine: EngineManager, saved: SavedStateHandle) :
    ConversationController(store, engine, saved.get<String>("sessionId")?.takeIf { it != "new" })
