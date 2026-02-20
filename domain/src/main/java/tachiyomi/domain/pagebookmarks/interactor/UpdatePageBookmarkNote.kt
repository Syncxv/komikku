package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class UpdatePageBookmarkNote(
    private val repository: PageBookmarkRepository,
) {

    suspend fun await(id: Long, note: String) {
        repository.updateNote(id, note)
    }
}
