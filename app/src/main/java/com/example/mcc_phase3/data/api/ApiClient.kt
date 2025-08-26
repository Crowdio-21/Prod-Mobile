package com.example.mcc_phase3.data.api

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.ConfigManager
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
    
    fun initialize(context: Context) {
        Log.d(TAG, "=== ApiClient Initialization ===")
        Log.d(TAG, "BASE_URL: ${getBaseUrl(context)}")
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
    
    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null
    
    private fun createRetrofit(context: Context): Retrofit {
        return Retrofit.Builder()
            .baseUrl(getBaseUrl(context))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .also {
                Log.d(TAG, "Retrofit instance created successfully for: ${getBaseUrl(context)}")
            }
    }
    
    fun getApiService(context: Context): ApiService {
        if (apiService == null) {
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
        // This allows dynamic URL updates for different network configurations
        val newRetrofit = Retrofit.Builder()
            .baseUrl(newBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        retrofit = newRetrofit
        apiService = retrofit!!.create(ApiService::class.java)
        Log.d(TAG, "updateBaseUrl() - ApiService recreated with new URL")
    }
    
    fun getBaseUrl(context: Context): String {
        val baseUrl = ConfigManager.getInstance(context).getStatServiceURL()
        Log.d(TAG, "getBaseUrl() called, returning: $baseUrl")
        return baseUrl
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
            error.message?.contains("ConnectException") == true -> {
                Log.e(TAG, "🔌 Connection Error: Server might be down or unreachable at ${getBaseUrl(context)}")
            }
            error.message?.contains("SocketTimeoutException") == true -> {
                Log.e(TAG, "⏰ Timeout Error: Request took longer than expected")
            }
            error.message?.contains("UnknownHostException") == true -> {
                Log.e(TAG, "🌐 Network Error: Cannot resolve host ${getBaseUrl(context)}")
            }
        }
    }
}
