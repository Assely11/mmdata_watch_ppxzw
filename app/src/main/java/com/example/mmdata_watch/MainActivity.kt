package com.example.mmdata_watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mmdata_watch.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var isMeasuring = false
    private var writer: BufferedWriter? = null
    private var startEpoch: Long = 0
    private var startNano: Long = 0
    private var fileName: String? = null

    private lateinit var startTimeText: TextView
    private lateinit var endTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var sensorDataText: TextView
    private lateinit var magnetometerChart: LineChart
    private lateinit var magXSet: LineDataSet
    private lateinit var magYSet: LineDataSet
    private lateinit var magZSet: LineDataSet

    private val REQUEST_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化传感器管理器
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 检查每个传感器是否存在，如果存在则获取并注册监听
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        // 初始化视图组件
        startTimeText = findViewById(R.id.startTimeText)
        endTimeText = findViewById(R.id.endTimeText)
        durationText = findViewById(R.id.durationText)
        sensorDataText = findViewById(R.id.sensorDataText)
        magnetometerChart = findViewById(R.id.magnetometerChart)
        val fileNameInput: EditText = findViewById(R.id.fileNameInput)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)

        setupChart()

        startButton.setOnClickListener {
            fileName = fileNameInput.text.toString().trim()
            if (fileName.isNullOrEmpty()) {
                Toast.makeText(this, "请输入文件名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkPermission()) startMeasurement()  // 检查权限
        }

        stopButton.setOnClickListener { stopMeasurement() }
    }

    // 检查并请求权限
    private fun checkPermission(): Boolean {
        // Android 10 及以上版本，不需要动态请求存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true

        // 检查存储权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION_CODE)
            return false
        }
        return true
    }

    // 权限请求结果处理
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)  // 添加这一行

        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，继续操作
                startMeasurement()
            } else {
                // 权限被拒绝，提醒用户
                Toast.makeText(this, "权限被拒绝，无法进行测量", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }


    // 获取 UTC 时间
    private fun getUtcTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(System.currentTimeMillis()))
    }

    // 设置图表
    private fun setupChart() {
        magXSet = LineDataSet(mutableListOf(), "Mag X")
        magYSet = LineDataSet(mutableListOf(), "Mag Y")
        magZSet = LineDataSet(mutableListOf(), "Mag Z")

        magXSet.color = Color.RED
        magYSet.color = Color.GREEN
        magZSet.color = Color.BLUE

        for (set in arrayOf(magXSet, magYSet, magZSet)) {
            set.lineWidth = 2f
            set.setDrawCircles(false)
        }

        val data = LineData(magXSet, magYSet, magZSet)
        magnetometerChart.data = data
        magnetometerChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        magnetometerChart.xAxis.setDrawGridLines(false)
        magnetometerChart.axisRight.isEnabled = false
        magnetometerChart.description.isEnabled = false
        magnetometerChart.legend.isEnabled = true
        magnetometerChart.invalidate()
    }

    // 开始测量
    private fun startMeasurement() {
        // 清空图表和之前的数据
        magnetometerChart.clear()  // 清空图表
        magXSet.clear()
        magYSet.clear()
        magZSet.clear()

        // 重新初始化数据集
        setupChart()  // 确保图表的数据集重新设置

        try {
            val dir = filesDir
            val file = File(dir, "$fileName.csv")
            writer = BufferedWriter(FileWriter(file))

            val startUtc = getUtcTime()
            writer?.write("StartTimeUTC,$startUtc\n")
            writer?.write("time_since_start(s),sensor,x,y,z\n")

            startEpoch = System.currentTimeMillis()
            startNano = System.nanoTime()
            startTimeText.text = "开始时间：$startUtc"
            Toast.makeText(this, "开始测量\n文件路径：${file.absolutePath}", Toast.LENGTH_LONG).show()

            isMeasuring = true

            accSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }



    // 停止测量
    private fun stopMeasurement() {
        if (!isMeasuring) return
        isMeasuring = false
        sensorManager.unregisterListener(this)

        try {
            val endUtc = getUtcTime()
            writer?.write("EndTimeUTC,$endUtc\n")
            writer?.flush()
            writer?.close()

            endTimeText.text = "结束时间：$endUtc"
            val durationSec = (System.currentTimeMillis() - startEpoch) / 1000
            durationText.text = "总时长：$durationSec 秒"

        } catch (e: IOException) {
            e.printStackTrace()
        }

        Toast.makeText(this, "测量结束", Toast.LENGTH_SHORT).show()
    }

    // 传感器数据变化
// 实时采样与写入
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isMeasuring || writer == null || event == null) return

        // 获取时间（时间戳）
        val timeSinceStart = (System.nanoTime() - startNano) / 1_000_000_000.0
        val timeStr = String.format(Locale.US, "%.8f", timeSinceStart)

        // 根据传感器类型写入对应数据
        val x = event.values[0]
        val y = if (event.values.size > 1) event.values[1] else 0f
        val z = if (event.values.size > 2) event.values[2] else 0f

        val sensorType = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "acc"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            Sensor.TYPE_MAGNETIC_FIELD -> "mag"
            Sensor.TYPE_PRESSURE -> "press"
            else -> "unknown"
        }

        // 只在对应传感器的数据存在时才写入文件
        try {
            writer?.write("$timeStr,$sensorType,$x,$y,$z\n")
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // 更新图表（如果是磁力计数据）
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            addMagPoint(timeSinceStart.toFloat(), x, y, z)
        }

        // 更新UI（显示传感器数据）
        runOnUiThread {
            sensorDataText.text = String.format(Locale.US, "%s: X=%.3f Y=%.3f Z=%.3f", sensorType, x, y, z)
        }
    }


    // 绘制磁强计图表
    private fun addMagPoint(timeSec: Float, x: Float, y: Float, z: Float) {
        val data = magnetometerChart.data
        if (data == null) return
        data.addEntry(Entry(timeSec, x), 0)  // X轴数据
        data.addEntry(Entry(timeSec, y), 1)  // Y轴数据
        data.addEntry(Entry(timeSec, z), 2)  // Z轴数据
        data.notifyDataChanged()

        magnetometerChart.notifyDataSetChanged()  // 通知图表数据已更新
        magnetometerChart.setVisibleXRangeMaximum(30f)  // 设置显示最大可视范围
        magnetometerChart.moveViewToX(timeSec)  // 移动视图到当前时间
        magnetometerChart.invalidate()  // 强制刷新图表
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }
}
