package app.mobile.presentation.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.domain.model.MangaSeries
import app.domain.usecase.GetLibraryUseCase
import app.domain.usecase.SortType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val mangaList: List<MangaSeries> = emptyList(),
    val searchQuery: String = "",
    val sortType: SortType = SortType.DATE_DESC,
    val errorMessage: String? = null
)

class HomeViewModel(
    private val getLibraryUseCase: GetLibraryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun loadLibrary(rootUriStr: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val list = getLibraryUseCase(
                    rootUriStr = rootUriStr,
                    query = _uiState.value.searchQuery,
                    sortType = _uiState.value.sortType
                )
                _uiState.value = _uiState.value.copy(isLoading = false, mangaList = list)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message)
            }
        }
    }
}