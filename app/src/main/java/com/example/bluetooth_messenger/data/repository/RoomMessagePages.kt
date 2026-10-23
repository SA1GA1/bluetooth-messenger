package com.example.bluetooth_messenger.data.repository

import androidx.paging.*
import com.example.bluetooth_messenger.core.paging.MessagePages
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.domain.model.ChatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMessagePages @Inject constructor(private val database: DatabaseSession) : MessagePages {
    override fun observe(conversation: String): Flow<PagingData<ChatMessage>> = database.database.flatMapLatest { open ->
        if (open == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) {
            open.messages().page(conversation)
        }.flow.map { page -> page.map { it.toDomain() } }
    }
}
