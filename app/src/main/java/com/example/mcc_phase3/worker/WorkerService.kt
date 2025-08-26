package com.example.mcc_phase3.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.mcc_phase3.data.websocket.WebSocketManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.random.Random

class WorkerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val webSocketManager = WebSocketManager.getInstance()
    private val isRunning = AtomicBoolean(false)
    private val tasksCompleted = AtomicInteger(0)
    private val tasksFailed = AtomicInteger(0)
    private val totalExecutionTime = AtomicInteger(0)
    private val startTime = System.currentTimeMillis()

    // Task executor for computational tasks
    private val taskExecutor = MobileTaskExecutor()

    // Track current worker status
    private var currentStatus = "offline"
    private var currentTaskId: String? = null
    private val activeTasks = mutableSetOf<String>()

    companion object {
        private const val TAG = "WorkerService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== WorkerService Created ===")
        Log.d(TAG, WorkerConfig.getConfigSummary())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_WORKER" -> startWorker()
            "STOP_WORKER" -> stopWorker()
            else -> startWorker()
        }
        return START_STICKY
    }

    private fun startWorker() {
        if (isRunning.get()) {
            Log.w(TAG, "Worker is already running")
            return
        }

        Log.d(TAG, "🚀 Starting worker")
        isRunning.set(true)

        serviceScope.launch {
            connectToForeman()
            startHeartbeat()
        }
    }

    private fun stopWorker() {
        Log.d(TAG, "🛑 Stopping worker")
        isRunning.set(false)
        currentStatus = "offline"
        webSocketManager.disconnect()
    }

    private suspend fun connectToForeman() {
        val listener = object : WebSocketManager.WebSocketListener {
            override fun onConnected() {
                Log.d(TAG, "✅ Connected to foreman")
                currentStatus = "online"
                sendWorkerRegistration()
            }

            override fun onMessage(message: String) {
                Log.d(TAG, "📨 Received message: $message")
                handleIncomingMessage(message)
            }

            override fun onDisconnected() {
                Log.w(TAG, "🔌 Disconnected from foreman")
                currentStatus = "offline"
            }

            override fun onError(error: Exception?) {
                Log.e(TAG, "❌ WebSocket error", error)
                currentStatus = "offline"
            }
        }

        webSocketManager.addListener(listener)
        Log.d(TAG, "🔌 Attempting to connect to: ${WorkerConfig.FOREMAN_URL}")
        webSocketManager.connect(WorkerConfig.FOREMAN_URL)
    }

    private fun handleIncomingMessage(message: String) {
        try {
            Log.d(TAG, "📨 Raw message received: $message")
            val json = JSONObject(message)
            val type = json.optString("type", "")

            Log.d(TAG, "📋 Processing message type: $type")

            when (type) {
                "assign_task" -> handleTaskAssignment(json)
                "task_cancel" -> handleTaskCancel(json)
                "ping" -> sendPongResponse()
                else -> Log.w(TAG, "🤷 Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing message: $message", e)
        }
    }

    private fun handleTaskAssignment(json: JSONObject) {
        try {
            Log.d(TAG, "📋 Processing task assignment: ${json.toString()}")
            val data = json.getJSONObject("data")
            val taskId = data.getString("task_id")
            val jobId = json.optString("job_id", "")

            Log.d(TAG, "📋 Task assigned: $taskId (Job: $jobId)")
            Log.d(TAG, "📋 Task data: ${data.toString()}")

            if (activeTasks.size >= WorkerConfig.MAX_CONCURRENT_TASKS) {
                Log.w(TAG, "⚠️ Worker at capacity, rejecting task $taskId")
                sendTaskRejection(taskId, "Worker at capacity")
                return
            }

            // Accept the task
            activeTasks.add(taskId)
            currentTaskId = taskId
            currentStatus = "busy"

            // Process the task
            serviceScope.launch {
                processTask(taskId, jobId, data)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling task assignment", e)
        }
    }

    private suspend fun processTask(taskId: String, jobId: String, taskData: JSONObject) {
        try {
            Log.d(TAG, "🔄 Starting task execution: $taskId")

            // Update status to running
            currentStatus = "running"

            val startTime = System.currentTimeMillis()

            // Execute the real task
            val result = executeRealTask(taskData)

            val executionTime = (System.currentTimeMillis() - startTime).toInt()
            totalExecutionTime.addAndGet(executionTime)

            Log.d(TAG, "✅ Task completed: $taskId in ${executionTime}ms")

            // Send task completed
            sendTaskResult(taskId, jobId, result)
            tasksCompleted.incrementAndGet()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Task failed: $taskId", e)
            sendTaskError(taskId, jobId, e.message ?: "Unknown error")
            tasksFailed.incrementAndGet()
        } finally {
            // Clean up task
            activeTasks.remove(taskId)
            if (currentTaskId == taskId) {
                currentTaskId = null
            }

            // Update status
            currentStatus = if (activeTasks.isEmpty()) "online" else "busy"
        }
    }

    private suspend fun executeRealTask(taskData: JSONObject): Any {
        return withContext(Dispatchers.Default) {
            try {
                // Extract task information
                val functionName = extractFunctionName(taskData)
                val taskArgs = extractTaskArgs(taskData)

                Log.d(TAG, "🔧 Executing function: $functionName")
                Log.d(TAG, "📝 Task args: $taskArgs")

                // Execute the task based on function name or type
                taskExecutor.executeTask(functionName, taskArgs)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Task execution failed", e)
                throw e
            }
        }
    }

    private fun extractFunctionName(taskData: JSONObject): String {
        // Try different ways to extract function name
        return when {
            taskData.has("function_name") -> taskData.getString("function_name")
            taskData.has("func_name") -> taskData.getString("func_name")
            taskData.has("task_type") -> taskData.getString("task_type")
            taskData.has("func_pickle") -> "pickled_function" // For Python pickled functions
            else -> "unknown_function"
        }
    }

    private fun extractTaskArgs(taskData: JSONObject): List<Any> {
        return try {
            when {
                taskData.has("task_args") -> {
                    val argsArray = taskData.getJSONArray("task_args")
                    (0 until argsArray.length()).map { i ->
                        argsArray.get(i)
                    }
                }
                taskData.has("args") -> {
                    val argsArray = taskData.getJSONArray("args")
                    (0 until argsArray.length()).map { i ->
                        argsArray.get(i)
                    }
                }
                taskData.has("arguments") -> {
                    val argsArray = taskData.getJSONArray("arguments")
                    (0 until argsArray.length()).map { i ->
                        argsArray.get(i)
                    }
                }
                else -> {
                    // Try to extract individual parameters
                    listOfNotNull(
                        taskData.opt("param1"),
                        taskData.opt("param2"),
                        taskData.opt("param3"),
                        taskData.opt("data"),
                        taskData.opt("input")
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract task args, using empty list", e)
            emptyList()
        }
    }

    private fun handleTaskCancel(json: JSONObject) {
        try {
            val data = json.getJSONObject("data")
            val taskId = data.getString("task_id")

            Log.d(TAG, "🚫 Task cancellation requested: $taskId")

            if (activeTasks.contains(taskId)) {
                activeTasks.remove(taskId)
                if (currentTaskId == taskId) {
                    currentTaskId = null
                }
                currentStatus = if (activeTasks.isEmpty()) "online" else "busy"
                sendTaskCancelled(taskId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling task cancellation", e)
        }
    }

    // Message sending methods
    private fun sendWorkerRegistration() {
        val msg = JSONObject().apply {
            put("type", "worker_ready")
            put("data", JSONObject().apply {
                put("worker_id", WorkerConfig.WORKER_ID)
                put("capabilities", JSONArray().apply {
                    WorkerConfig.CAPABILITIES.forEach { put(it) }
                })
                put("max_concurrent_tasks", WorkerConfig.MAX_CONCURRENT_TASKS)
                put("status", currentStatus)
                put("platform", "android")
                put("execution_modes", JSONArray().apply {
                    put("mathematical_computation")
                    put("data_processing")
                    put("string_manipulation")
                    put("statistical_analysis")
                })
            })
        }
        val messageStr = msg.toString()
        webSocketManager.sendMessage(messageStr)
        Log.d(TAG, "📤 Sent worker registration")
        Log.d(TAG, "📤 Registration message: $messageStr")
    }

    private fun sendPongResponse() {
        val msg = JSONObject().apply {
            put("type", "pong")
            put("data", JSONObject())
        }
        webSocketManager.sendMessage(msg.toString())
    }

    private fun sendTaskResult(taskId: String, jobId: String, result: Any) {
        val msg = JSONObject().apply {
            put("type", "task_result")
            put("data", JSONObject().apply {
                put("result", result)
                put("task_id", taskId)
            })
            put("job_id", jobId)
        }
        val messageStr = msg.toString()
        webSocketManager.sendMessage(messageStr)
        Log.d(TAG, "📤 Task result sent: $taskId")
        Log.d(TAG, "📤 Message content: $messageStr")
    }

    private fun sendTaskError(taskId: String, jobId: String, error: String) {
        val msg = JSONObject().apply {
            put("type", "task_error")
            put("data", JSONObject().apply {
                put("error", error)
                put("task_id", taskId)
            })
            put("job_id", jobId)
        }
        val messageStr = msg.toString()
        webSocketManager.sendMessage(messageStr)
        Log.d(TAG, "📤 Task error sent: $taskId")
        Log.d(TAG, "📤 Message content: $messageStr")
    }

    private fun sendTaskRejection(taskId: String, reason: String) {
        val msg = JSONObject().apply {
            put("type", "task_rejected")
            put("data", JSONObject().apply {
                put("worker_id", WorkerConfig.WORKER_ID)
                put("task_id", taskId)
                put("reason", reason)
            })
        }
        webSocketManager.sendMessage(msg.toString())
        Log.d(TAG, "📤 Task rejected: $taskId - $reason")
    }

    private fun sendTaskCancelled(taskId: String) {
        val msg = JSONObject().apply {
            put("type", "task_cancelled")
            put("data", JSONObject().apply {
                put("worker_id", WorkerConfig.WORKER_ID)
                put("task_id", taskId)
            })
        }
        webSocketManager.sendMessage(msg.toString())
        Log.d(TAG, "📤 Task cancelled: $taskId")
    }

    private suspend fun startHeartbeat() {
        while (isRunning.get()) {
            delay(WorkerConfig.HEARTBEAT_INTERVAL * 1000L)
            if (webSocketManager.isConnected()) {
                sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        val msg = JSONObject().apply {
            put("type", "worker_heartbeat")
            put("data", JSONObject().apply {
                put("worker_id", WorkerConfig.WORKER_ID)
                put("status", currentStatus)
                put("current_task", currentTaskId)
                put("active_tasks", activeTasks.size)
                put("tasks_completed", tasksCompleted.get())
                put("tasks_failed", tasksFailed.get())
                put("uptime", System.currentTimeMillis() - startTime)
            })
        }
        webSocketManager.sendMessage(msg.toString())
        Log.v(TAG, "💓 Heartbeat sent - Status: $currentStatus, Tasks: ${activeTasks.size}")
    }

    override fun onDestroy() {
        Log.d(TAG, "🧹 WorkerService destroying")
        isRunning.set(false)
        serviceScope.cancel()
        webSocketManager.disconnect()
        super.onDestroy()

        Log.d(TAG, "📊 Final stats - Completed: ${tasksCompleted.get()}, Failed: ${tasksFailed.get()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Task executor for mobile computational tasks
 */
class MobileTaskExecutor {

    companion object {
        private const val TAG = "MobileTaskExecutor"
    }

    suspend fun executeTask(functionName: String, args: List<Any>): Any {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "🔧 Executing: $functionName with ${args.size} arguments")

            try {
                when (functionName.lowercase()) {
                    // Mathematical computations
                    "calculate_prime", "is_prime" -> calculatePrime(args)
                    "factorial" -> calculateFactorial(args)
                    "fibonacci" -> calculateFibonacci(args)
                    "matrix_multiply", "matrix_multiplication" -> matrixMultiplication(args)
                    "square_root", "sqrt" -> calculateSquareRoot(args)
                    "power", "pow" -> calculatePower(args)

                    // Data processing
                    "sum", "add" -> sumNumbers(args)
                    "average", "mean" -> calculateAverage(args)
                    "sort", "sort_array" -> sortArray(args)
                    "reverse", "reverse_array" -> reverseArray(args)
                    "filter_even" -> filterEven(args)
                    "filter_odd" -> filterOdd(args)

                    // String operations
                    "reverse_string" -> reverseString(args)
                    "count_words" -> countWords(args)
                    "to_upper", "uppercase" -> toUpperCase(args)
                    "to_lower", "lowercase" -> toLowerCase(args)

                    // Statistical operations
                    "standard_deviation", "std_dev" -> calculateStandardDeviation(args)
                    "variance" -> calculateVariance(args)
                    "median" -> calculateMedian(args)
                    "mode" -> calculateMode(args)

                    // Complex computations
                    "monte_carlo_pi" -> monteCarloPI(args)
                    "hash_computation" -> hashComputation(args)
                    "search_array" -> searchArray(args)

                    // Handle pickled functions (from Python workers)
                    "pickled_function" -> handlePickledFunction(args)

                    // Default case
                    else -> handleGenericFunction(functionName, args)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing $functionName", e)
                throw RuntimeException("Task execution failed: ${e.message}")
            }
        }
    }

    // Mathematical functions
    private fun calculatePrime(args: List<Any>): Boolean {
        val n = (args.firstOrNull() as? Number)?.toInt() ?: throw IllegalArgumentException("Invalid argument for prime calculation")
        if (n < 2) return false
        for (i in 2..sqrt(n.toDouble()).toInt()) {
            if (n % i == 0) return false
        }
        return true
    }

    private fun calculateFactorial(args: List<Any>): Long {
        val n = (args.firstOrNull() as? Number)?.toInt() ?: throw IllegalArgumentException("Invalid argument for factorial")
        if (n < 0) throw IllegalArgumentException("Factorial not defined for negative numbers")
        return (1..n).fold(1L) { acc, i -> acc * i }
    }

    private fun calculateFibonacci(args: List<Any>): Long {
        val n = (args.firstOrNull() as? Number)?.toInt() ?: throw IllegalArgumentException("Invalid argument for fibonacci")
        if (n <= 1) return n.toLong()
        var a = 0L
        var b = 1L
        repeat(n - 1) {
            val temp = a + b
            a = b
            b = temp
        }
        return b
    }

    private fun matrixMultiplication(args: List<Any>): List<List<Double>> {
        // Simple 2x2 matrix multiplication for demo
        val size = (args.firstOrNull() as? Number)?.toInt() ?: 2
        val matrix1 = Array(size) { DoubleArray(size) { Random.nextDouble() } }
        val matrix2 = Array(size) { DoubleArray(size) { Random.nextDouble() } }
        val result = Array(size) { DoubleArray(size) }

        for (i in 0 until size) {
            for (j in 0 until size) {
                for (k in 0 until size) {
                    result[i][j] += matrix1[i][k] * matrix2[k][j]
                }
            }
        }

        return result.map { row -> row.toList() }
    }

    private fun calculateSquareRoot(args: List<Any>): Double {
        val n = (args.firstOrNull() as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid argument for square root")
        return sqrt(n)
    }

    private fun calculatePower(args: List<Any>): Double {
        val base = (args.getOrNull(0) as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid base")
        val exponent = (args.getOrNull(1) as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid exponent")
        return base.pow(exponent)
    }

    // Data processing functions
    private fun sumNumbers(args: List<Any>): Double {
        return args.mapNotNull { (it as? Number)?.toDouble() }.sum()
    }

    private fun calculateAverage(args: List<Any>): Double {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }
        return if (numbers.isNotEmpty()) numbers.sum() / numbers.size else 0.0
    }

    private fun sortArray(args: List<Any>): List<Double> {
        return args.mapNotNull { (it as? Number)?.toDouble() }.sorted()
    }

    private fun reverseArray(args: List<Any>): List<Any> {
        return args.reversed()
    }

    private fun filterEven(args: List<Any>): List<Int> {
        return args.mapNotNull { (it as? Number)?.toInt() }.filter { it % 2 == 0 }
    }

    private fun filterOdd(args: List<Any>): List<Int> {
        return args.mapNotNull { (it as? Number)?.toInt() }.filter { it % 2 == 1 }
    }

    // String operations
    private fun reverseString(args: List<Any>): String {
        val str = args.firstOrNull()?.toString() ?: ""
        return str.reversed()
    }

    private fun countWords(args: List<Any>): Int {
        val str = args.firstOrNull()?.toString() ?: ""
        return str.trim().split("\\s+".toRegex()).size
    }

    private fun toUpperCase(args: List<Any>): String {
        return args.firstOrNull()?.toString()?.uppercase() ?: ""
    }

    private fun toLowerCase(args: List<Any>): String {
        return args.firstOrNull()?.toString()?.lowercase() ?: ""
    }

    // Statistical operations
    private fun calculateStandardDeviation(args: List<Any>): Double {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }
        if (numbers.isEmpty()) return 0.0
        val mean = numbers.sum() / numbers.size
        val variance = numbers.sumOf { (it - mean).pow(2) } / numbers.size
        return sqrt(variance)
    }

    private fun calculateVariance(args: List<Any>): Double {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }
        if (numbers.isEmpty()) return 0.0
        val mean = numbers.sum() / numbers.size
        return numbers.sumOf { (it - mean).pow(2) } / numbers.size
    }

    private fun calculateMedian(args: List<Any>): Double {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }.sorted()
        if (numbers.isEmpty()) return 0.0
        val mid = numbers.size / 2
        return if (numbers.size % 2 == 0) {
            (numbers[mid - 1] + numbers[mid]) / 2
        } else {
            numbers[mid]
        }
    }

    private fun calculateMode(args: List<Any>): Double {
        val numbers = args.mapNotNull { (it as? Number)?.toDouble() }
        if (numbers.isEmpty()) return 0.0
        return numbers.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0.0
    }

    // Complex computations
    private fun monteCarloPI(args: List<Any>): Double {
        val iterations = (args.firstOrNull() as? Number)?.toInt() ?: 1000000
        var insideCircle = 0
        repeat(iterations) {
            val x = Random.nextDouble(-1.0, 1.0)
            val y = Random.nextDouble(-1.0, 1.0)
            if (x * x + y * y <= 1.0) insideCircle++
        }
        return 4.0 * insideCircle / iterations
    }

    private fun hashComputation(args: List<Any>): String {
        val input = args.firstOrNull()?.toString() ?: ""
        return input.hashCode().toString()
    }

    private fun searchArray(args: List<Any>): Int {
        val target = args.firstOrNull() ?: return -1
        val array = args.drop(1)
        return array.indexOf(target)
    }

    // Handle Python pickled functions (placeholder)
    private fun handlePickledFunction(args: List<Any>): String {
        Log.w(TAG, "⚠️ Python pickled function not supported on Android")
        return "Error: Python pickled functions not supported on mobile platform"
    }

    // Generic function handler
    private fun handleGenericFunction(functionName: String, args: List<Any>): Any {
        Log.w(TAG, "⚠️ Unknown function: $functionName")
        return mapOf(
            "status" to "unknown_function",
            "function" to functionName,
            "args_count" to args.size,
            "message" to "Function '$functionName' not implemented on mobile platform"
        )
    }
}