package com.example.bluetooth_messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.bluetooth_messenger.ui.global.AppViewModel
import com.example.bluetooth_messenger.ui.messenger.NativeBiometric
import com.example.bluetooth_messenger.ui.messenger.OffGridApp
import com.example.bluetooth_messenger.ui.theme.BluetoothmessengerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var biometric: NativeBiometric
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        biometric = NativeBiometric(this, vm, lifecycleScope)
        setContent {
            BluetoothmessengerTheme {
                OffGridApp(vm, onClose = { vm.session.closeTask(); finishAndRemoveTask() }, onBiometric = biometric::authenticate)
            }
        }
    }
    override fun onStart() { super.onStart(); vm.session.foreground() }
    override fun onStop() {
        biometric.cancel()
        if (!isChangingConfigurations) vm.session.background()
        super.onStop()
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.session.interacted()
    }
}
