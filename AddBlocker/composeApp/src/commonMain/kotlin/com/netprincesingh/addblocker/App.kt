package com.netprincesingh.addblocker

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.netprincesingh.addblocker.navigation.AppNavigation

@Composable
fun App() {
    MaterialTheme {
        AppNavigation()
    }
}