package com.saeed.ikev2vpn.bypass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BypassUiState(
    val config: BypassConfig = BypassConfig(), val suffixText: String = "", val initialized: Boolean = false,
    val busy: Boolean = false, val dirty: Boolean = false, val error: String? = null, val message: String? = null,
)

class BypassViewModel(
    private val repository: BypassRepository,
    private val source: CountryRouteSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BypassUiState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val config = withContext(io) { repository.load() }
                mutableState.value = BypassUiState(config, config.suffixes.sorted().joinToString(" ") { ".$it" }, initialized = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = BypassUiState(initialized = true, error = "Saved bypass rules could not be read. Save new settings to replace them.") }
        }
    }
    fun updateSuffixes(text: String) { if (!state.value.busy && text.length <= 4096) change { copy(suffixText = text) } }
    fun enableDomains(enabled: Boolean) { if (!state.value.busy) change { copy(config = config.copy(domainsEnabled = enabled)) } }
    fun enableCountries(enabled: Boolean) { if (!state.value.busy) change { copy(config = config.copy(countriesEnabled = enabled)) } }
    fun removeCountry(code: String) { if (!state.value.busy) change { copy(config = config.copy(countries = config.countries.filterNot { it.code == code })) } }
    fun downloadCountry(code: String) {
        if (!state.value.initialized || state.value.busy) return
        val previous = state.value.config.countries.firstOrNull { it.code == code }
        if (previous != null && System.currentTimeMillis() - previous.downloadedAt in 0 until 86_400_000) {
            mutableState.update { it.copy(message = "This country's IP list was downloaded today. Check again tomorrow.", error = null) }
            return
        }
        if (previous == null && state.value.config.countries.size >= 16) {
            mutableState.update { it.copy(error = "Select at most 16 countries.") }; return
        }
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val country = withContext(io) { source.download(code) }
                val candidate = state.value.config.copy(countries = state.value.config.countries.filterNot { it.code == code } + country)
                withContext(worker) { validateRoutes(candidate.copy(countriesEnabled = true)) }
                change { copy(config = candidate, busy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableState.update { it.copy(busy = false, error = e.message ?: "The country IP list is invalid.") } }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, error = "Country IP download failed. Check your connection and try again. Your previous list is kept.") } }
        }
    }
    fun save() {
        if (!state.value.initialized || state.value.busy) return
        val draft = state.value
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val config = withContext(worker) {
                    val suffixes = DomainSuffixes.parse(draft.suffixText)
                    require(!draft.config.domainsEnabled || suffixes.isNotEmpty()) { "Enter at least one domain suffix." }
                    require(!draft.config.countriesEnabled || draft.config.countries.isNotEmpty()) { "Add at least one country." }
                    draft.config.copy(suffixes = suffixes).also(::validateRoutes)
                }
                withContext(io) { repository.save(config) }
                mutableState.update { it.copy(config = config, busy = false, dirty = false, message = "Saved. Rules apply to all profiles on the next connection.") }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableState.update { it.copy(busy = false, error = e.message ?: "Invalid bypass settings.") } }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, error = "Bypass rules could not be saved. Try again.") } }
        }
    }
    private fun change(block: BypassUiState.() -> BypassUiState) { mutableState.update { it.block().copy(dirty = true, error = null, message = null) } }
    class Factory(private val repository: BypassRepository, private val source: CountryRouteSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BypassViewModel::class.java))
            return BypassViewModel(repository, source) as T
        }
    }
    companion object {
        fun validateRoutes(config: BypassConfig) {
            require(config.countries.size <= 16 && config.countries.sumOf { it.prefixes.size } <= 50_000) { "Too many country prefixes. Select fewer countries." }
            require(BypassRoutePlanner.normalized(config.countryPrefixes).size <= BypassRoutePlanner.MAX_ROUTES - 16) {
                "These countries exceed the route limit. Select fewer countries."
            }
        }
    }
}
