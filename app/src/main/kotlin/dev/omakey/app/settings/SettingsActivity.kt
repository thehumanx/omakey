package dev.omakey.app.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omakey.app.R

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        onOpenSystemSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onSwitchKeyboard = {
                            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                            imm.showInputMethodPicker()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onOpenSystemSettings: () -> Unit, onSwitchKeyboard: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onOpenSystemSettings) {
            Text(text = stringResource(R.string.settings_enable_keyboard))
        }
        Button(onClick = onSwitchKeyboard) {
            Text(text = stringResource(R.string.settings_choose_keyboard))
        }
        Text(text = stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodyMedium)
    }
}
