package com.example.mcc_phase3.data.api

import android.util.Log
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "http://192.168.8.101:8000" // For Android emulator to connect to localhost
    
    init {
        Log.d(TAG, "=== ApiClient Initialization ===")
        Log.d(TAG, "BASE_URL: $BASE_URL")
        Log.d(TAG, "Setting up HTTP client with timeouts and logging")
    }
    
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
        .also {
            Log.d(TAG, "Gson configured with LOWER_CASE_WITH_UNDERSCORES naming policy")
        }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            Log.d(TAG, "HTTP logging interceptor set to BODY level")
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
        .also {
            Log.d(TAG, "OkHttpClient configured: connect=15s, read=15s, write=15s")
        }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .also {
            Log.d(TAG, "Retrofit instance created successfully for: $BASE_URL")
        }
    
    val apiService: ApiService = retrofit.create(ApiService::class.java).also {
        Log.d(TAG, "ApiService instance created successfully")
        Log.d(TAG, "=== ApiClient Initialization Complete ===")
    }
    
    fun updateBaseUrl(newBaseUrl: String) {
        Log.d(TAG, "updateBaseUrl() called: changing from $BASE_URL to $newBaseUrl")
        // This allows dynamic URL updates for different network configurations
        val newRetrofit = Retrofit.Builder()
            .baseUrl(newBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        Log.w(TAG, "updateBaseUrl() - Note: Service instance not updated, need to recreate ApiService")
        // Note: In a real app, you'd want to recreate the service or use a different approach
    }
    
    fun getBaseUrl(): String {
        Log.d(TAG, "getBaseUrl() called, returning: $BASE_URL")
        return BASE_URL
    }
    
    fun logApiCall(endpoint: String, method: String = "GET") {
        Log.d(TAG, "🌐 API Call: $method $BASE_URL$endpoint")
    }
    
    fun logApiSuccess(endpoint: String, responseCode: Int, responseSize: Int = -1) {
        val sizeInfo = if (responseSize > 0) ", size: ${responseSize}bytes" else ""
        Log.d(TAG, "✅ API Success: $endpoint -> HTTP $responseCode$sizeInfo")
    }
    
    fun logApiError(endpoint: String, error: Throwable) {
        Log.e(TAG, "❌ API Error: $endpoint", error)
        when {
            error.message?.contains("ConnectException") == true -> {
                Log.e(TAG, "🔌 Connection Error: Server might be down or unreachable at $BASE_URL")
            }
            error.message?.contains("SocketTimeoutException") == true -> {
                Log.e(TAG, "⏰ Timeout Error: Request took longer than expected")
            }
            error.message?.contains("UnknownHostException") == true -> {
                Log.e(TAG, "🌐 Network Error: Cannot resolve host $BASE_URL")
            }
        }
    }
}
