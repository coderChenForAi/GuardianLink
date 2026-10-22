package com.guardian.link.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.guardian.link.data.AppSettings
import com.guardian.link.data.FenceConfigRepository
import com.guardian.link.data.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        val context = LocalContext.current
        val repo = remember { SettingsRepository(context.applicationContext) }
        val fenceRepo = remember { FenceConfigRepository(context.applicationContext) }
        val scope = rememberCoroutineScope()
        val settings by repo.all.collectAsState(initial = null as AppSettings?)
        var showSettings by remember { mutableStateOf(false) }
        var showFence by remember { mutableStateOf(false) }
        var showEvents by remember { mutableStateOf(false) }

        val current = settings
        when {
            current == null -> Box(Modifier.fillMaxSize())
            !current.onboarded -> OnboardingScreen { role ->
                scope.launch { repo.completeOnboarding(role) }
            }
            showSettings -> SettingsScreen(repo, onBack = { showSettings = false })
            showFence -> FenceSettingsScreen(repo, fenceRepo, onBack = { showFence = false })
            showEvents -> EventsScreen(onBack = { showEvents = false })
            else -> HomeScreen(
                repo = repo,
                fenceRepo = fenceRepo,
                onOpenSettings = { showSettings = true },
                onOpenFence = { showFence = true },
                onOpenEvents = { showEvents = true },
            )
        }
    }
}
