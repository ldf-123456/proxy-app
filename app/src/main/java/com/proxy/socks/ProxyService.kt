package com.proxy.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.io.DataInputStream
import java.io.DataOutputStream

class ProxyService : Service() {
    
    companion object {
        var isRunning = false
        private const val TAG = "ProxyService"
        private const val LOCAL_PORT = 10809
    }
    
    private var serverSocket: ServerSocket? = null
    private var isRunningFlag = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                val server = intent.getStringExtra("server") ?: "192.168.31.42"
                val port = intent.getIntExtra("port", 1080)
                startProxy(server, port)
            }
            "STOP" -> {
                stopProxy()
            }
        }
        return START_STICKY
    }
    
    private fun startProxy(server: String, port: Int) {
        startForeground(1, createNotification("代理服务启动中..."))
        
        Thread {
            try {
                serverSocket = ServerSocket(LOCAL_PORT)
                serverSocket?.soTimeout = 1000
                isRunningFlag = true
                isRunning = true
                
                mainHandler.post {
                    updateNotification("代理运行中 (本地:$LOCAL_PORT)")
                }
                
                Log.d(TAG, "代理服务启动，监听端口 $LOCAL_PORT")
                Log.d(TAG, "远程服务器: $server:$port")
                
                while (isRunningFlag) {
                    try {
                        val client = serverSocket!!.accept()
                        Thread(ProxyHandler(client, server, port)).start()
                    } catch (e: Exception) {
                        if (isRunningFlag) {
                            // 超时继续
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "代理服务错误: ${e.message}")
            }
            isRunning = false
            stopSelf()
        }.start()
    }
    
    private fun stopProxy() {
        isRunningFlag = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭服务器: ${e.message}")
        }
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "proxy_channel",
                "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "代理工具服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, "proxy_channel")
            .setContentTitle("代理工具")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }
}

class ProxyHandler(
    private val clientSocket: Socket,
    private val serverHost: String,
    private val serverPort: Int
) : Runnable {
    
    companion object {
        private const val TAG = "ProxyHandler"
    }
    
    override fun run() {
        try {
            val request = ByteArray(65536)
            val read = clientSocket.getInputStream().read(request)
            
            if (read <= 0) {
                clientSocket.close()
                return
            }
            
            // 连接到远程SOCKS5代理
            val remoteSocket = Socket(serverHost, serverPort)
            val remoteOut = remoteSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            
            // SOCKS5握手
            remoteOut.write(byteArrayOf(0x05, 0x01, 0x00))
            remoteOut.flush()
            
            val handshake = ByteArray(2)
            remoteIn.read(handshake)
            
            if (handshake[0] != 0x05.toByte() || handshake[1] != 0x00.toByte()) {
                remoteSocket.close()
                clientSocket.close()
                return
            }
            
            // 解析请求并连接
            val requestStr = String(request, 0, read)
            val lines = requestStr.split("\r\n")
            val firstLine = lines.firstOrNull() ?: ""
            
            if (firstLine.startsWith("CONNECT")) {
                // HTTPS代理
                val parts = firstLine.split(" ")[1].split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 443 else 443
                
                connectAndTunnel(remoteSocket, clientSocket, host, port)
            } else {
                // HTTP代理
                var host = ""
                var port = 80
                
                for (line in lines) {
                    if (line.lowercase().startsWith("host:")) {
                        val hostPort = line.substring(5).trim()
                        if (hostPort.contains(":")) {
                            val hp = hostPort.split(":")
                            host = hp[0]
                            port = hp[1].toIntOrNull() ?: 80
                        } else {
                            host = hostPort
                        }
                        break
                    }
                }
                
                if (host.isNotEmpty()) {
                    connectAndTunnel(remoteSocket, clientSocket, host, port)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理请求错误: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {}
        }
    }
    
    private fun connectAndTunnel(remoteSocket: Socket, clientSocket: Socket, host: String, port: Int) {
        try {
            val remoteOut = remoteSocket.getOutputStream()
            
            // 发送连接请求
            val hostBytes = host.toByteArray()
            val request = ByteArray(4 + 1 + hostBytes.size + 2)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03 // 域名
            request[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
            request[5 + hostBytes.size] = (port shr 8).toByte()
            request[5 + hostBytes.size + 1] = port.toByte()
            
            remoteSocket.getOutputStream().write(request)
            remoteSocket.getOutputStream().flush()
            
            val response = ByteArray(10)
            remoteSocket.getInputStream().read(response)
            
            if (response[1] == 0x00.toByte()) {
                // 连接成功
                clientSocket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientSocket.getOutputStream().flush()
                
                // 双向转发
                forwardData(clientSocket, remoteSocket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "隧道错误: ${e.message}")
        }
    }
    
    private fun forwardData(clientSocket: Socket, remoteSocket: Socket) {
        val buffer = ByteArray(8192)
        
        Thread {
            try {
                while (true) {
                    val read = clientSocket.getInputStream().read(buffer)
                    if (read <= 0) break
                    remoteSocket.getOutputStream().write(buffer, 0, read)
                    remoteSocket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // 连接关闭
            }
        }.start()
        
        Thread {
            try {
                while (true) {
                    val read = remoteSocket.getInputStream().read(buffer)
                    if (read <= 0) break
                    clientSocket.getOutputStream().write(buffer, 0, read)
                    clientSocket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // 连接关闭
            }
        }.start()
    }
}
