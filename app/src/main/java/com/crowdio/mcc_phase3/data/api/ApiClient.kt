package com.crowdio.mcc_phase3.data.api

import android.content.Context
import android.util.Log
import com.crowdio.mcc_phase3.data.ConfigManager
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException

object ApiClient {
    private const val TAG = "ApiClient"
    
    // Timeout configuration - increased for better network resilience
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 30L
    
    // Retry configuration
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 1000L
    
    fun initialize(context: Context) {
        Log.d(TAG, "=== ApiClient Initialization ===")
        Log.d(TAG, "BASE_URL: ${getBaseUrl(context)}")
        Log.d(TAG, "Setting up HTTP client with enhanced timeouts and retry mechanism")
        Log.d(TAG, "Timeouts: connect=${CONNECT_TIMEOUT_SECONDS}s, read=${READ_TIMEOUT_SECONDS}s, write=${WRITE_TIMEOUT_SECONDS}s")
    }
    
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
        .also {
            Log.d(TAG, "Gson configured with LOWER_CASE_WITH_UNDERSCORES naming policy")
        }
    
    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null
    private var currentBaseUrl: String? = null
    private var okHttpClientInstance: OkHttpClient? = null
    
    /**
     * Force complete reset of ApiClient
     * Call this when configuration changes (e.g., new IP address set)
     */
    fun reset(context: Context) {
        Log.d(TAG, "🔄 Resetting ApiClient completely")
        clearOkHttpClient()
        retrofit = null
        apiService = null
        currentBaseUrl = null
        Log.d(TAG, "✅ ApiClient reset complete - will reinitialize on next getApiService() call")
    }
    
    private fun clearOkHttpClient() {
        okHttpClientInstance?.let { client ->
            try {
                // Close all idle connections
                client.connectionPool.evictAll()
                // Cancel all pending calls
                client.dispatcher.cancelAll()
                Log.d(TAG, "OkHttpClient connections cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing OkHttpClient connections", e)
            }
        }
        okHttpClientInstance = null
    }
    
    private fun getOrCreateOkHttpClient(): OkHttpClient {
        if (okHttpClientInstance == null) {
            okHttpClientInstance = OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    Log.d(TAG, "HTTP logging interceptor set to BODY level")
                })
                .addInterceptor(RetryInterceptor())
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
                .also {
                    Log.d(TAG, "OkHttpClient configured with enhanced settings")
                }
        }
        return okHttpClientInstance!!
    }
    
    private fun createRetrofit(context: Context): Retrofit {
        return Retrofit.Builder()
            .baseUrl(getBaseUrl(context))
            .client(getOrCreateOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .also {
                Log.d(TAG, "Retrofit instance created successfully for: ${getBaseUrl(context)}")
            }
    }
    
    fun getApiService(context: Context): ApiService {
        val baseUrl = getBaseUrl(context)
        
        // Recreate if base URL changed or not yet initialized
        if (apiService == null || currentBaseUrl != baseUrl) {
            Log.d(TAG, "Base URL changed from $currentBaseUrl to $baseUrl, recreating Retrofit and OkHttpClient")
            currentBaseUrl = baseUrl
            // Force recreation of OkHttpClient to clear cached connections
            clearOkHttpClient()
            retrofit = createRetrofit(context)
            apiService = retrofit!!.create(ApiService::class.java).also {
                Log.d(TAG, "ApiService instance created successfully")
                Log.d(TAG, "=== ApiClient Initialization Complete ===")
            }
        }
        return apiService!!
    }
    
    fun updateBaseUrl(context: Context, newBaseUrl: String) {
        Log.d(TAG, "updateBaseUrl() called: changing from ${getBaseUrl(context)} to $newBaseUrl")
        // Force recreation of OkHttpClient to clear cached connections
        clearOkHttpClient()
        currentBaseUrl = newBaseUrl
        // This allows dynamic URL updates for different network configurations
        val newRetrofit = Retrofit.Builder()
            .baseUrl(newBaseUrl)
            .client(getOrCreateOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        retrofit = newRetrofit
        apiService = retrofit!!.create(ApiService::class.java)
        Log.d(TAG, "updateBaseUrl() - ApiService and OkHttpClient recreated with new URL")
    }
    
    fun getBaseUrl(context: Context): String {
        val baseUrl = ConfigManager.getInstance(context).getStatServiceURL()
        return if (baseUrl != null) {
            Log.d(TAG, "getBaseUrl() called, returning: $baseUrl")
            baseUrl
        } else {
            Log.w(TAG, "⚠️ Foreman IP not configured, using placeholder URL")
            Log.w(TAG, "💡 Please configure Foreman IP in Settings before using the app")
            "http://unconfigured.local:8080"  // Placeholder that will fail gracefully
        }
    }
    
    fun logApiCall(context: Context, endpoint: String, method: String = "GET") {
        Log.d(TAG, "🌐 API Call: $method ${getBaseUrl(context)}$endpoint")
    }
    
    fun logApiSuccess(endpoint: String, responseCode: Int, responseSize: Int = -1) {
        val sizeInfo = if (responseSize > 0) ", size: ${responseSize}bytes" else ""
        Log.d(TAG, "✅ API Success: $endpoint -> HTTP $responseCode$sizeInfo")
    }
    
    fun logApiError(context: Context, endpoint: String, error: Throwable) {
        Log.e(TAG, "❌ API Error: $endpoint", error)
        when {
            error is ConnectException -> {
                Log.e(TAG, "🔌 Connection Error: Server might be down or unreachable at ${getBaseUrl(context)}")
                Log.e(TAG, "💡 Suggestion: Check if the backend server is running and accessible")
            }
            error is SocketTimeoutException -> {
                Log.e(TAG, "⏰ Timeout Error: Request took longer than ${READ_TIMEOUT_SECONDS}s")
                Log.e(TAG, "💡 Suggestion: Check network connectivity or server performance")
            }
            error is UnknownHostException -> {
                Log.e(TAG, "🌐 Network Error: Cannot resolve host ${getBaseUrl(context)}")
                Log.e(TAG, "💡 Suggestion: Check DNS resolution and network configuration")
            }
            error is IOException -> {
                Log.e(TAG, "📡 IO Error: Network communication failed")
                Log.e(TAG, "💡 Suggestion: Check network connectivity and firewall settings")
            }
            else -> {
                Log.e(TAG, "❓ Unknown Error: ${error.javaClass.simpleName}")
            }
        }
    }
    
    /**
     * Retry interceptor for automatic retry on network failures
     */
    private class RetryInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            var response: okhttp3.Response? = null
            var exception: Exception? = null
            
            for (attempt in 0..MAX_RETRIES) {
                try {
                    response = chain.proceed(request)
                    
                    // If response is successful, return it immediately
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Request successful on attempt ${attempt + 1}")
                        return response
                    }
                    
                    // If response is not successful but not a network error, don't retry
                    if (response.code in 400..499) {
                        Log.w(TAG, "⚠️ Client error (${response.code}), not retrying")
                        return response
                    }
                    
                    // For server errors (5xx), retry
                    if (response.code in 500..599) {
                        Log.w(TAG, "🔄 Server error (${response.code}), retrying... (attempt ${attempt + 1}/${MAX_RETRIES + 1})")
                        response.close()
                        if (attempt < MAX_RETRIES) {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                            continue
                        }
                    }
                    
                    return response
                    
                } catch (e: Exception) {
                    exception = e
                    Log.w(TAG, "🔄 Network error on attempt ${attempt + 1}/${MAX_RETRIES + 1}: ${e.message}")
                    
                    // Don't retry on client errors
                    if (e is ConnectException || e is UnknownHostException) {
                        Log.e(TAG, "❌ Connection error, not retrying: ${e.message}")
                        break
                    }
                    
                    // Retry on timeout and IO errors
                    if (e is SocketTimeoutException || e is IOException) {
                        if (attempt < MAX_RETRIES) {
                            try {
                                Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                                continue
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                    
                    break
                }
            }
            
            // If we get here, all retries failed
            Log.e(TAG, "❌ All ${MAX_RETRIES + 1} attempts failed")
            throw exception ?: IOException("Request failed after ${MAX_RETRIES + 1} attempts")
        }
    }
}
