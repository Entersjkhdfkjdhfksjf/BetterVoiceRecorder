package dev.aarav.clearscribe.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val dao: RecordingDao) {
    fun observeAll(): Flow<List<Recording>> = dao.observeAll()

    suspend fun save(recording: Recording): Long = dao.insert(recording)

    suspend fun delete(id: Long) = dao.delete(id)
}
