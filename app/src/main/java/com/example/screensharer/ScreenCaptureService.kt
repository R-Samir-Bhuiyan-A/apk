package com.example.screensharer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException

class ScreenCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var webSocket: WebSocket
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        var IS_SERVICE_RUNNING = false
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCapture"
        private const val NOTIFICATION_ID = 1
        // IMPORTANT: Replace this with the RAW URL of your GitHub file containing the server address
        private const val WEBSOCKET_SERVER_URL = "ws://103.151.60.202:6967"
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode != 0 && data != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            startWebSocket(WEBSOCKET_SERVER_URL) // Directly use the static URL
            IS_SERVICE_RUNNING = true
        }
        return START_NOT_STICKY
    }
    
    // Removed fetchServerUrlAndStart() as we are using a direct URL for testing.


    private fun startWebSocket(serverUrl: String) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", "ScreenShareAndroid")
                .build()
            chain.proceed(newRequest)
        }.build()

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startScreenCapture()
            }
        })
    }
    
    private fun startScreenCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            0, imageReader!!.surface, null, null
        )
        captureAndSend()
    }
    
    private fun captureAndSend() {
        handler.postDelayed({
            var image: Image? = null
            try {
                image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val outputStream = ByteArrayOutputStream()
                    // Lower quality for faster transmission
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
                    webSocket.send(ByteString.of(*outputStream.toByteArray()))
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image?.close()
            }
            if (IS_SERVICE_RUNNING) {
                 captureAndSend()
            }
        }, 100) // 100ms delay ~ 10 fps
    }


    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screen Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Sharing")
            .setContentText("Your screen is being shared.")
            .setSmallIcon(R.mipmap.ic_launcher) // You would need to provide an icon
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        IS_SERVICE_RUNNING = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        if(this::webSocket.isInitialized) webSocket.close(1000, "Service Stopped")
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}