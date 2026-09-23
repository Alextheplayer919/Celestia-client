package com.proxy.mcbedrock

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val vpnPermissionRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val status = TextView(this).apply {
            text = "Bedrock Proxy — skeleton build.\n\n" +
                "This does not yet relay traffic. See MinecraftVpnService.kt " +
                "for TODOs on packet capture, relay, and protocol decoding."
        }

        val startButton = Button(this).apply {
            text = "Start Proxy"
            setOnClickListener { requestVpnPermission() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Proxy"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MinecraftVpnService::class.java))
            }
        }

        layout.addView(status)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnPermissionRequestCode)
        } else {
            onVpnPermissionGranted()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnPermissionRequestCode && resultCode == RESULT_OK) {
            onVpnPermissionGranted()
        }
    }

    private fun onVpnPermissionGranted() {
        startService(Intent(this, MinecraftVpnService::class.java))
    }
}
