package com.example.mcc_phase3.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection

/**
 * Network utility class for comprehensive network connectivity checks and diagnostics
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"
    private const val CONNECTIVITY_TIMEOUT_MS = 5000L
    private const val HTTP_TIMEOUT_MS = 10000L
    
    /**
     * Check if device has internet connectivity
     */
    fun hasInternetConnectivity(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w(TAG, "⚠️ No active network found")
            return false
        }
        
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        if (networkCapabilities == null) {
            Log.w(TAG, "⚠️ No network capabilities found")
            return false
        }
        
        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        
        Log.d(TAG, "🌐 Network capabilities: Internet=$hasInternet, Validated=$hasValidated")
        return hasInternet && hasValidated
    }
    
    /**
     * Check if specific host is reachable
     */
    suspend fun isHostReachable(host: String, port: Int = 80): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Checking if $host:$port is reachable")
            
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), CONNECTIVITY_TIMEOUT_MS.toInt())
            socket.close()
            
            Log.d(TAG, "Host $host:$port is reachable")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Host $host:$port is not reachable: ${e.message}")
            false
        }
    }
    
    /**
     * Check if specific URL is accessible via HTTP
     */
    suspend fun isUrlAccessible(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🌐 Checking if URL is accessible: $url")
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECTIVITY_TIMEOUT_MS.toInt()
            connection.readTimeout = HTTP_TIMEOUT_MS.toInt()
            connection.requestMethod = "HEAD"
            connection.connect()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            val isAccessible = responseCode in 200..399
            Log.d(TAG, "URL $url is accessible (HTTP $responseCode)")
            isAccessible
        } catch (e: Exception) {
            Log.w(TAG, "URL $url is not accessible: ${e.message}")
            false
        }
    }
    
    /**
     * Perform DNS resolution for a host
     */
    suspend fun resolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Resolving DNS for host: $host")
            
            val addresses = InetAddress.getAllByName(host)
            val ipAddresses = addresses.map { it.hostAddress }
            
            Log.d(TAG, "DNS resolution successful for $host: ${ipAddresses.joinToString(", ")}")
            ipAddresses.toList()
        } catch (e: Exception) {
            Log.w(TAG, "DNS resolution failed for $host: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Comprehensive network diagnostic for a specific endpoint
     */
    suspend fun performNetworkDiagnostic(context: Context, baseUrl: String): NetworkDiagnosticResult {
        Log.d(TAG, "🔧 Performing network diagnostic for: $baseUrl")
        
        val result = NetworkDiagnosticResult()
        
        // Check general internet connectivity
        result.hasInternetConnectivity = hasInternetConnectivity(context)
        Log.d(TAG, "Internet connectivity: ${result.hasInternetConnectivity}")
        
        if (!result.hasInternetConnectivity) {
            result.errorMessage = "No internet connectivity detected"
            return result
        }
        
        try {
            // Parse URL to get host and port
            val url = URL(baseUrl)
            val host = url.host
            val port = if (url.port != -1) url.port else url.defaultPort
            
            // DNS resolution
            result.resolvedAddresses = resolveHost(host)
            if (result.resolvedAddresses.isEmpty()) {
                result.errorMessage = "DNS resolution failed for $host"
                return result
            }
            
            // Host reachability
            result.isHostReachable = isHostReachable(host, port)
            if (!result.isHostReachable) {
                result.errorMessage = "Host $host:$port is not reachable"
                return result
            }
            
            // URL accessibility
            result.isUrlAccessible = isUrlAccessible(baseUrl)
            if (!result.isUrlAccessible) {
                result.errorMessage = "URL $baseUrl is not accessible"
                return result
            }
            
            result.isSuccessful = true
            result.errorMessage = null
            
        } catch (e: Exception) {
            result.errorMessage = "Network diagnostic failed: ${e.message}"
            Log.e(TAG, "Network diagnostic failed", e)
        }
        
        Log.d(TAG, "🔧 Network diagnostic completed: ${if (result.isSuccessful) "SUCCESS" else "FAILED"}")
        return result
    }
    
    /**
     * Get network type information
     */
    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "No Network"
        
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
    }
    
    /**
     * Result class for network diagnostics
     */
    data class NetworkDiagnosticResult(
        var isSuccessful: Boolean = false,
        var hasInternetConnectivity: Boolean = false,
        var resolvedAddresses: List<String> = emptyList(),
        var isHostReachable: Boolean = false,
        var isUrlAccessible: Boolean = false,
        var errorMessage: String? = null
    ) {
        fun getSummary(): String {
            return buildString {
                appendLine("Network Diagnostic Summary:")
                appendLine("- Internet Connectivity: ${if (hasInternetConnectivity) "[YES]" else "[NO]"}")
                appendLine("- DNS Resolution: ${if (resolvedAddresses.isNotEmpty()) "[OK] (${resolvedAddresses.joinToString(", ")})" else "[FAILED]"}")
                appendLine("- Host Reachability: ${if (isHostReachable) "[OK]" else "[FAILED]"}")
                appendLine("- URL Accessibility: ${if (isUrlAccessible) "[OK]" else "[FAILED]"}")
                appendLine("- Overall Status: ${if (isSuccessful) "SUCCESS" else "FAILED"}")
                errorMessage?.let { appendLine("- Error: $it") }
            }
        }
    }
}

