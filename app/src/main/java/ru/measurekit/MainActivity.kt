package ru.measurekit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.measurekit.ui.MeasureKitApp
import ru.measurekit.ui.theme.MeasureKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeasureKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MeasureKitApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
