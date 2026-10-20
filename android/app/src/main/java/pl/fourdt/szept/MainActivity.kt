package pl.fourdt.szept

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController

class MainActivity : FragmentActivity() {
    private val pendingLink = mutableStateOf<ContactLink?>(null)
    val locked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val engine = SzeptApp.instance.engine
        if (engine.appLock) locked.value = true
        setContent {
            SzeptTheme {
                val registered by engine.registered.collectAsState()
                val nav = rememberNavController()
                if (!registered) OnboardingScreen(engine) else SzeptNav(engine, nav, pendingLink)
                if (locked.value) LockScreen { authenticate() }
            }
        }
    }

    override fun onResume() { super.onResume(); if (SzeptApp.instance.engine.appLock && locked.value) authenticate() }
    override fun onStop() { super.onStop(); if (SzeptApp.instance.engine.appLock) locked.value = true }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) { i?.dataString?.let { ContactLink.parse(it) }?.let { pendingLink.value = it } }

    fun authenticate() {
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { locked.value = false; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.unlock_prompt))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
}
