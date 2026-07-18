// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.vaultcity.droid_qr_data_exchange.ui.GenerateScreen
import net.vaultcity.droid_qr_data_exchange.ui.ReadScreen
import net.vaultcity.droid_qr_data_exchange.ui.theme.DroidQRDataExchangeTheme
import net.vaultcity.droid_qr_data_exchange.viewmodel.GenerateViewModel
import net.vaultcity.droid_qr_data_exchange.viewmodel.ReadViewModel

private enum class MainTab(val label: String) {
    GENERATE("QR erstellen"),
    READ("QR einlesen"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidQRDataExchangeTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.GENERATE) }
    val generateViewModel: GenerateViewModel = viewModel()
    val readViewModel: ReadViewModel = viewModel()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (selectedTab) {
                MainTab.GENERATE -> GenerateScreen(viewModel = generateViewModel)
                MainTab.READ -> ReadScreen(viewModel = readViewModel)
            }
        }
    }
}
