# CROWDio Mobile Worker - Android App

A modern Android application that serves as both a monitoring client and a distributed computing worker for the CROWDio framework. Built with Material Design 3, featuring real-time task execution monitoring with horizontal progress indicators.

## 🚀 Features

### 📱 Dual Functionality
- **Monitoring Client**: Real-time dashboard for jobs, tasks, and workers
- **Mobile Worker**: Execute distributed computing tasks using Python (Chaquopy)

### 🎯 Core Capabilities
- **Real-time Dashboard**: Live monitoring with connection status and system health
- **Task Execution**: Python code execution with progress tracking
- **Activity Monitoring**: Horizontal loading lines showing task execution progress
- **Worker Management**: Start/stop mobile worker services
- **Network Resilience**: Circuit breaker pattern with automatic retries

### 🎨 Modern UI/UX
- **Material Design 3**: Latest Android design language
- **Dark Mode Support**: Complete dark theme with system detection
- **Progress Indicators**: Horizontal loading bars for task execution
- **Card-based Layout**: Clean, modern activity cards with status icons
- **Real-time Updates**: Live progress tracking and status updates

## 🏗️ Architecture

### **Clean Architecture**
- **Data Layer**: Repository pattern with API and WebSocket clients
- **Domain Layer**: Use cases and business logic
- **Presentation Layer**: MVI pattern with ViewModels and Fragments
- **UI Layer**: Material 3 components with ViewBinding

### **Key Components**
- **MainViewModel**: Central state management with MVI pattern
- **TaskProgressSimulator**: Real-time task execution progress tracking
- **ActivityAdapter**: Enhanced adapter with progress indicators
- **MobileWorkerService**: Background service for task execution
- **PythonExecutor**: Chaquopy integration for Python code execution

## 📊 Task Execution Monitoring

### **Horizontal Progress Indicators**
The app features sophisticated progress tracking for task execution:

- **Real-time Progress Bars**: Horizontal loading lines showing 0-100% completion
- **Status Icons**: Visual indicators (⚡ executing, ✅ completed, ❌ failed, ⏳ pending)
- **Execution Time**: Live timing display during task execution
- **Result Display**: Task results and error messages
- **Card-based UI**: Modern card layout with progress sections

### **Progress States**
- **Executing**: Shows progress bar with percentage and execution time
- **Completed**: Displays result and completion time
- **Failed**: Shows error message and failure time
- **Pending**: Indicates task is queued for execution

## 🛠️ Technical Stack

### **Android Framework**
- **Kotlin**: Modern Android development
- **Material 3**: Latest design system
- **ViewBinding**: Type-safe view references
- **Navigation Component**: Fragment navigation
- **ViewModel & LiveData**: Lifecycle-aware data management

### **Networking**
- **Retrofit 2.9.0**: REST API client
- **OkHttp 4.9.0**: HTTP client with interceptors
- **Java-WebSocket**: Real-time WebSocket communication

### **Python Integration**
- **Chaquopy**: Python SDK for Android (Python 3.12.6)
- **JSON Serialization**: Secure function serialization (no pickle)
- **Task Execution**: Local Python code execution

### **UI Components**
- **RecyclerView**: Efficient list rendering with progress indicators
- **CardView**: Modern card-based layouts
- **ProgressBar**: Horizontal progress indicators
- **ConstraintLayout**: Flexible, responsive layouts

## 🚀 Quick Start

### **Prerequisites**
- Android Studio Arctic Fox or later
- Android SDK API level 27+ (Android 8.1+)
- CROWDio framework running on your network

### **Installation**
1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd MCC-Phase3
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Open the project folder
   - Wait for Gradle sync to complete

3. **Configure Network Settings**
   - Update the Foreman IP address in settings
   - Default ports: API (8000), WebSocket (9000)

4. **Build and Run**
   - Connect Android device or start emulator
   - Click "Run" in Android Studio

## 📱 Usage Guide

### **Dashboard Overview**
- **Status Card**: Real-time connection status and system health
- **Quick Actions**: Mobile worker control and settings access
- **Tab Navigation**: Dashboard, Jobs, Workers, and Activity tabs

### **Activity Monitoring**
- **Real-time Progress**: Watch tasks execute with horizontal loading bars
- **Status Tracking**: Visual indicators for task states
- **Execution Details**: Task IDs, execution times, and results
- **Filtered View**: Shows only activities for the current device

### **Mobile Worker**
- **Start/Stop**: Control worker service from the app
- **Task Execution**: Automatic Python code execution
- **Progress Tracking**: Real-time progress monitoring
- **Result Display**: Task results and error handling

## 🔧 Configuration

### **Network Settings**
Access via Settings to configure:
- **Foreman IP Address**: Server endpoint configuration
- **Port Settings**: API and WebSocket port customization
- **Connection Testing**: Verify network connectivity

### **Worker Configuration**
- **Execution Modes**: Simulation, Python forwarding, or local execution
- **Task Limits**: Maximum concurrent tasks and timeouts
- **Heartbeat Interval**: Worker status update frequency

## 🎨 UI Features

### **Progress Indicators**
- **Horizontal Loading Bars**: Smooth progress animation
- **Status Icons**: Color-coded visual indicators
- **Execution Timing**: Real-time execution time display
- **Result Display**: Task results and error messages

### **Material Design 3**
- **Dynamic Colors**: Adaptive color palette
- **Typography**: Consistent text hierarchy
- **Components**: Modern buttons, cards, and navigation
- **Animations**: Smooth transitions and micro-interactions

### **Dark Mode**
- **Automatic Detection**: Follows system theme preferences
- **Manual Toggle**: Easy switching between themes
- **Persistent Settings**: Remembers user preferences
- **Consistent Theming**: All components support both themes

## 🔍 Task Progress Simulation

The app includes a sophisticated task progress simulator for demonstration:

### **Features**
- **Real-time Progress**: Updates every 200ms
- **Multiple Tasks**: Support for concurrent task simulation
- **Success/Failure**: Configurable task outcomes
- **Execution Timing**: Realistic execution time simulation

### **Usage**
```kotlin
val simulator = TaskProgressSimulator()
simulator.startTaskSimulation("task_001", shouldSucceed = true)
```

## 🐛 Troubleshooting

### **Common Issues**

#### **Connection Problems**
- Verify CROWDio framework is running
- Check Foreman IP address in settings
- Test network connectivity from device to server
- Ensure ports 8000 and 9000 are accessible

#### **Task Execution Issues**
- Check Python/Chaquopy configuration
- Verify task serialization format
- Review execution timeout settings
- Check device memory and performance

#### **Progress Display Issues**
- Verify activity adapter configuration
- Check task progress simulator status
- Review UI layout and binding
- Test with different task types

### **Debug Features**
- **Circuit Breaker Status**: Monitor in dashboard
- **Network Logs**: Check Android Studio logcat
- **Task Progress**: Real-time progress monitoring
- **Manual Reset**: Reset circuit breaker if needed

## 📊 Performance

### **Optimizations**
- **Efficient Rendering**: RecyclerView with progress indicators
- **Memory Management**: Proper cleanup of resources
- **Network Efficiency**: Connection pooling and retry logic
- **Battery Optimization**: Smart task scheduling

### **Monitoring**
- **Real-time Metrics**: Task execution timing
- **Progress Tracking**: Live progress updates
- **Error Handling**: Comprehensive exception management
- **Resource Usage**: Memory and CPU monitoring

## 🤝 Contributing

### **Code Standards**
- **Kotlin Best Practices**: Follow official conventions
- **Clean Architecture**: Maintain separation of concerns
- **Material Design**: Adhere to Material 3 guidelines
- **Error Handling**: Implement proper exception management

### **Development Process**
1. Fork the repository
2. Create a feature branch
3. Implement changes with tests
4. Submit pull request with description

## 📄 License

This project is part of the CROWDio framework. Please refer to the main project license for usage terms.

## 🔗 Related Projects

- **CROWDio Framework**: Main distributed computing platform
- **Foreman Service**: Central coordination service
- **Worker Services**: Task execution services
- **Python Integration**: Chaquopy for Android

---

**Built with ❤️ for the CROWDio community**

*Featuring real-time task execution monitoring with horizontal progress indicators*