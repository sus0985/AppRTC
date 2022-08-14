package com.vlending.apprtc.view

import android.content.Intent
import android.os.Bundle
import com.vlending.apprtc.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding>({ ActivityMainBinding.inflate(it) }) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.buttonConnect.setOnClickListener {
            val roomId = binding.etMyId.text.toString()

            if (roomId.isNotEmpty()) {
                val intent = Intent(this, CallActivity::class.java)
                intent.putExtra("room", roomId)
                startActivity(intent)
            }
        }
    }
}