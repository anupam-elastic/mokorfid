package com.mokobara.mokoapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mokobara.mokoapp.databinding.ActivityBulkMapBinding

class BulkMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBulkMapBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBulkMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
    }
}
