package com.example.bluetooth_messenger.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.paging.PagingData
import com.example.bluetooth_messenger.core.paging.MessagePages
import com.example.bluetooth_messenger.core.security.BiometricGateway
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.*
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.ui.global.AppViewModel
import com.example.bluetooth_messenger.ui.messenger.*
import com.example.bluetooth_messenger.ui.theme.BluetoothmessengerTheme
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.crypto.Cipher
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MessengerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository = ScreenRepository()
    private val session = ScreenSession()
    private var pageMessages =
        listOf(
            ChatMessage(
                "message-id",
                ScreenRepository.PEER,
                ContentType.Text,
                "Личное сообщение",
                1,
                true,
                DeliveryStatus.Delivered,
                1,
                1,
                0,
                null,
                0,
                null,
                null,
            )
        )
    private val vm by lazy {
        AppViewModel(
            session,
            ScreenSettings(),
            repository,
            object : DiscoveryService {
                override val peers = MutableStateFlow(emptyList<DiscoveredPeer>())

                override suspend fun start(userId: String) = Unit

                override fun stop() = Unit
            },
            object : MessagePages {
                override fun observe(conversation: String) = flowOf(PagingData.from(pageMessages))
            },
            object : BiometricGateway {
                override suspend fun prepare(enrollment: Boolean): Cipher =
                    error("Biometrics are not used by this screen test")

                override suspend fun complete(cipher: Cipher, enrollment: Boolean) = Unit
            },
        )
    }

    @Before
    fun keepScreenAwake() {
        compose.activity.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun composerSendsToRepositoryAndNeverRendersInternalIdentifiers() {
        compose.setContent {
            BluetoothmessengerTheme {
                CompositionLocalProvider(
                    LocalExternalActions provides ExternalActions({ _, _ -> }, {}, {})
                ) {
                    ChatScreen(vm, ScreenRepository.CHAT)
                }
            }
        }
        compose.runOnIdle { vm.open(ScreenRepository.CHAT) }
        compose.onNodeWithText("Личное сообщение").assertIsDisplayed()
        compose.onNodeWithText(ScreenRepository.PEER, substring = true).assertDoesNotExist()
        compose.onNodeWithText(ScreenRepository.CHAT, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Отправить").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Привет рядом")
        compose.onNodeWithText("Отправить").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ScreenRepository.CHAT to "Привет рядом", repository.sent) }
        compose.onNodeWithText("Привет рядом").assertDoesNotExist()
    }

    @Test
    fun photoAttachmentLoadsAndDrawsWithoutRecyclingActiveBitmap() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        repository.photoContent =
            ByteArrayOutputStream().use { output ->
                source.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
        source.recycle()
        pageMessages =
            listOf(
                ChatMessage(
                    "photo-message",
                    ScreenRepository.PEER,
                    ContentType.Photo,
                    "",
                    1,
                    true,
                    DeliveryStatus.Delivered,
                    1,
                    1,
                    0,
                    "Снимок.jpg",
                    repository.photoContent.size.toLong(),
                    "photo-file",
                    null,
                    "image/jpeg",
                )
            )
        compose.setContent {
            BluetoothmessengerTheme {
                CompositionLocalProvider(
                    LocalExternalActions provides ExternalActions({ _, _ -> }, {}, {})
                ) {
                    ChatScreen(vm, ScreenRepository.CHAT)
                }
            }
        }
        compose.runOnIdle { vm.open(ScreenRepository.CHAT) }
        compose.onNodeWithText("Снимок.jpg").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Фото").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Фото").assertIsDisplayed().captureToImage()
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithContentDescription("Фото").assertDoesNotExist()
    }

    @Test
    fun inactiveGroupHidesDeleteAndMembershipActionsButKeepsLocalClear() {
        val group =
            Conversation(
                "group-id",
                "Закрытая группа",
                ConversationType.Group,
                "",
                1,
                0,
                false,
                repository.ownId.value,
            )
        compose.setContent { BluetoothmessengerTheme { Details(vm, group, {}) } }
        compose.waitUntil { vm.contacts.value.isNotEmpty() }
        compose.onNodeWithText("Закрытая группа").assertIsDisplayed()
        compose.onNodeWithText("Удалить чат").assertDoesNotExist()
        compose.onNodeWithText("Закрыть группу").assertDoesNotExist()
        compose.onNodeWithText("Выйти из группы").assertDoesNotExist()
        compose.onNodeWithText("Добавить Алиса").assertDoesNotExist()
        compose.onNodeWithText("Исключить").assertDoesNotExist()
        compose.onNodeWithText("Очистить историю").assertIsDisplayed()
    }

    @Test
    fun pairingRequiresAliasAndExplicitMatchingCodeAndWaitsForOtherDevice() {
        repository.pairings.value =
            listOf(PairingPrompt(ScreenRepository.PEER, "", "123456", false))
        compose.setContent {
            BluetoothmessengerTheme {
                val prompts by repository.pairings.collectAsState()
                PairingDialog(vm, prompts.first())
            }
        }
        compose.onNodeWithText("123456").assertIsDisplayed()
        compose.onNodeWithText(ScreenRepository.PEER, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Подтвердить").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Алиса")
        compose.onNodeWithText("Подтвердить").assertIsNotEnabled()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Подтвердить").assertIsEnabled().performClick()
        compose
            .onNodeWithText("Подтверждено вами. Ожидаем подтверждения второго устройства.")
            .assertIsDisplayed()
        compose.onNodeWithText("Подтвердить").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(ScreenRepository.PEER to "Алиса", repository.confirmation)
        }
    }

    @Test
    fun lockedSessionShowsPinAndHidesPrivateChatContent() {
        session.state.value = SessionState.Locked(UnlockMethod.Pin)
        compose.setContent { BluetoothmessengerTheme { OffGridApp(vm, {}, {}) } }
        compose.onNodeWithText("Введите PIN для входа").assertIsDisplayed()
        compose.onNodeWithText("Личное сообщение").assertDoesNotExist()
        compose.onNodeWithText("Алиса").assertDoesNotExist()
        compose.onNodeWithText("Отправить").assertDoesNotExist()
        compose.onNodeWithText("Чаты").assertDoesNotExist()
    }

    @Test
    fun externalFileOperationWaitsUntilSessionIsReadyAgain() {
        compose.setContent { androidx.compose.material3.Text("Тест отложенной операции") }
        var executed = 0
        compose.runOnIdle {
            session.state.value = SessionState.Locked(UnlockMethod.Pin)
            vm.afterUnlock { executed++ }
            assertEquals(0, executed)
        }
        compose.runOnIdle { session.state.value = SessionState.Ready }
        compose.runOnIdle { assertEquals(1, executed) }
        compose.runOnIdle { session.state.value = SessionState.Ready }
        compose.runOnIdle { assertEquals(1, executed) }
    }
}

private class ScreenSession : SessionController {
    override val state = MutableStateFlow<SessionState>(SessionState.Ready)
    override val error = MutableStateFlow<String?>(null)
    override val biometricEnabled = MutableStateFlow(false)
    override val networkError = MutableStateFlow<String?>(null)

    override suspend fun setup(pin: CharArray) = Unit

    override suspend fun unlock(pin: CharArray) = Unit

    override suspend fun reset() = Unit

    override suspend fun reconnect() = Unit

    override suspend fun disableBiometrics() = Unit

    override fun foreground() = Unit

    override fun background() {
        state.value = SessionState.Locked(UnlockMethod.Pin)
    }

    override fun closeTask() = background()

    override fun interacted() = Unit

    override fun usePin() = Unit

    override fun biometricFailed(enrollment: Boolean) = Unit

    override fun dismissError() = Unit
}

private class ScreenSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings())

    override suspend fun setTimeout(minutes: Int) = Unit

    override suspend fun setReadReceipts(enabled: Boolean) = Unit

    override suspend fun setPublicMode(enabled: Boolean) = Unit
}

private class ScreenRepository : MessengerRepository {
    companion object {
        const val PEER = "00000000-0000-0000-0000-000000000001"
        const val CHAT = "00000000-0000-0000-0000-000000000002"
    }

    override val contacts = MutableStateFlow(listOf(Contact(PEER, "Алиса", true)))
    override val conversations =
        MutableStateFlow(
            listOf(Conversation(CHAT, "Алиса", ConversationType.Direct, "", 1, 0, true, null))
        )
    override val pairings = MutableStateFlow(emptyList<PairingPrompt>())
    override val ownId = MutableStateFlow<String?>("00000000-0000-0000-0000-000000000003")
    override val transferBusy = MutableStateFlow(false)
    override val ownQr = MutableStateFlow<String?>(null)
    override val publicMessages = MutableStateFlow(emptyList<PublicMessage>())
    override val errors = MutableStateFlow<String?>(null)
    var sent: Pair<String, String>? = null
    var confirmation: Pair<String, String>? = null
    var photoContent = byteArrayOf()

    override fun messages(conversation: String) = flowOf(emptyList<ChatMessage>())

    override fun recipients(message: String) = flowOf(emptyList<RecipientDelivery>())

    override fun groupMembers(conversation: String) = flowOf(emptyList<GroupMember>())

    override suspend fun contactDetails(peer: String) =
        ContactDetails("Алиса", "fingerprint", "offgrid-check:1:public")

    override suspend fun photoBytes(fileId: String) = photoContent.copyOf()

    override suspend fun exportFile(fileId: String, open: () -> OutputStream) = Unit

    override suspend fun addFromQr(value: String, name: String) = Unit

    override suspend fun confirmPairing(peer: String, name: String) {
        confirmation = peer to name
        pairings.value = pairings.value.map { it.copy(confirmed = true) }
    }

    override suspend fun rejectPairing(peer: String) = Unit

    override suspend fun openContact(peer: String) = CHAT

    override suspend fun sendText(conversation: String, text: String) {
        sent = conversation to text
    }

    override suspend fun sendPublic(text: String) = Unit

    override suspend fun sendLocation(conversation: String, latitude: Double, longitude: Double) =
        Unit

    override suspend fun sendAttachment(
        conversation: String,
        source: AttachmentSource,
        photo: Boolean,
    ) = Unit

    override suspend fun createGroup(name: String, members: List<String>) = CHAT

    override suspend fun addGroupMember(group: String, member: String) = Unit

    override suspend fun removeGroupMember(group: String, member: String) = Unit

    override suspend fun leaveGroup(group: String) = Unit

    override suspend fun retry(message: String) = Unit

    override suspend fun markRead(conversation: String) = Unit

    override suspend fun deleteMessage(message: String) = Unit

    override suspend fun clearHistory(conversation: String) = Unit

    override suspend fun deleteConversation(conversation: String) = Unit

    override fun dismissError() = Unit
}
