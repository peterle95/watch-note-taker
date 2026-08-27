package com.peterle95.watchnotetaker.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.Text
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScalingLazyColumn {
                    item { Text("Watch Note Taker") }
                    item { Button(onClick = {}) { Text("Record") } }
                    item { Text("0 recordings queued") }
                }
            }
        }
    }
}
