package ru.parik.salon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.parik.salon.ui.SalonApp
import ru.parik.salon.ui.SalonViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SalonApp(viewModel<SalonViewModel>(), intent.getStringExtra("appointment_id")) }
    }
}
