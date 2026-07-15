package com.apexlions.hfstorage.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.hfstorage.mobile.ui.HfStorageApp
import com.apexlions.hfstorage.mobile.ui.HfStorageTheme
import com.apexlions.hfstorage.mobile.ui.HfStorageViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HfStorageTheme {
                val viewModel: HfStorageViewModel = viewModel()
                HfStorageApp(viewModel)
            }
        }
    }
}
