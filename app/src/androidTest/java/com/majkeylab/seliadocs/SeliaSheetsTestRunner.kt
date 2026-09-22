package com.majkeylab.seliadocs

import androidx.test.runner.AndroidJUnitRunner
import com.majkeylab.seliadocs.settings.SettingsRepository
import kotlinx.coroutines.runBlocking

class SeliaSheetsTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        // Editor regressions start after setup. Dedicated onboarding tests reset this preference.
        runBlocking { SettingsRepository.create(targetContext).update { it.copy(onboardingComplete = true) } }
        super.onStart()
    }
}
