package com.proxy.socks

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var serverEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    
    private var isRunning = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        updateStatus()
    }
    
    private fun initViews() {
        serverEdit = findViewById(R.id.serverEdit)
        portEdit = findViewById(R.id.portEdit)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        
        // 默认值
        serverEdit.setText("192.168.31.42")
        portEdit.setText("1080")
        
        startButton.setOnClickListener {
            startProxy()
        }
        
        stopButton.setOnClickListener {
            stopProxy()
        }
        
        updateStatus()
    }
    
    private fun startProxy() {
        val server = serverEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull() ?: 1080
        
        val intent = Intent(this, ProxyService::class.java).apply {
            action = "START"
            putExtra("server", server)
            putExtra("port", port)
        }
        startForegroundService(intent)
        
        isRunning = true
        updateStatus()
    }
    
    private fun stopProxy() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        
        isRunning = false
        updateStatus()
    }
    
    private fun updateStatus() {
        if (isRunning) {
            startButton.isEnabled = false
            stopButton.isEnabled = true
            serverEdit.isEnabled = false
            portEdit.isEnabled = false
            statusText.text = "状态: 运行中"
            statusText.setTextColor(0xFF4CAF50.toInt())
        } else {
            startButton.isEnabled = true
            stopButton.isEnabled = false
            serverEdit.isEnabled = true
            portEdit.isEnabled = true
            statusText.text = "状态: 已停止"
            statusText.setTextColor(0xFFFF5722.toInt())
        }
    }
    
    override fun onResume() {
        super.onResume()
        isRunning = ProxyService.isRunning
        updateStatus()
    }
}
