package net.discdd.bundletransport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.screens.TransportHomeScreen
import net.discdd.theme.ComposableTheme

class MainActivity: ComponentActivity() {
    // fields go here

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UsbConnectionManager.initialize(applicationContext)

        setContent {
            ComposableTheme {
                TransportHomeScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        UsbConnectionManager.cleanup(applicationContext)
    }
}