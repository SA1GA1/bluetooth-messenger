package com.example.bluetooth_messenger.core.paging

import androidx.paging.PagingData
import com.example.bluetooth_messenger.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface MessagePages {
    fun observe(conversation: String): Flow<PagingData<ChatMessage>>
}
