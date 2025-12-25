package com.alexbomber12.memtag.domain

import com.alexbomber12.memtag.data.repository.MementoRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

sealed class SyncProgressEvent {
    data class Progress(
        val progress: SyncProgress,
    ) : SyncProgressEvent()

    data class Finished(
        val result: SyncResult,
    ) : SyncProgressEvent()
}

class SyncMementoLibraryUseCase(
    private val repository: MementoRepository,
) {
    fun execute(libraryId: String): Flow<SyncProgressEvent> =
        channelFlow {
            val progressChannel = Channel<SyncProgress>(Channel.BUFFERED)
            val job =
                launch {
                    for (progress in progressChannel) {
                        send(SyncProgressEvent.Progress(progress))
                    }
                }
            val result =
                repository.syncLibrary(libraryId) { progress ->
                    progressChannel.trySend(progress)
                }
            progressChannel.close()
            job.join()
            send(SyncProgressEvent.Finished(result))
        }
}
