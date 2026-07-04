package org.jarvis.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jarvis.android.ui.commands.CommandScreen
import org.jarvis.android.ui.finance.ManualFinanceScreen
import org.jarvis.android.ui.health.HealthScreen
import org.jarvis.android.ui.settings.SettingsScreen
import org.jarvis.android.ui.statistics.StatisticsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { JarvisHome() }
        }
    }
}

private enum class Tab { FINANCE, HEALTH, STATS, COMMANDS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisHome() {
    var tab by remember { mutableStateOf(Tab.FINANCE) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.FINANCE,
                    onClick = { tab = Tab.FINANCE },
                    icon = {},
                    label = { Text("Finance") })
                NavigationBarItem(
                    selected = tab == Tab.HEALTH,
                    onClick = { tab = Tab.HEALTH },
                    icon = {},
                    label = { Text("Health") })
                NavigationBarItem(
                    selected = tab == Tab.STATS,
                    onClick = { tab = Tab.STATS },
                    icon = {},
                    label = { Text("Stats") })
                NavigationBarItem(
                    selected = tab == Tab.COMMANDS,
                    onClick = { tab = Tab.COMMANDS },
                    icon = {},
                    label = { Text("Commands") })
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = {},
                    label = { Text("Server") })
            }
        }
    ) { padding ->
        when (tab) {
            Tab.FINANCE  -> ManualFinanceScreen(Modifier.padding(padding))
            Tab.HEALTH   -> HealthScreen(Modifier.padding(padding))
            Tab.STATS    -> StatisticsScreen(Modifier.padding(padding))
            Tab.COMMANDS -> CommandScreen(Modifier.padding(padding))
            Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }
}
