package ru.buswidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvYandexMapsTerms).apply {
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/legal/maps_api/"))
                startActivity(intent)
            }
        }
    }
}
