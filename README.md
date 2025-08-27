# CrowdCompute Mobile App

A modern, professional Android application for monitoring and managing the CrowdCompute distributed computing framework. Built with Material Design 3, featuring a clean interface, dark mode support, and robust network resilience.

## ✨ Features

### 🎯 Core Functionality
- **Real-time Dashboard**: Live monitoring of jobs, tasks, and workers
- **Job Management**: Track job progress, completion status, and task distribution
- **Worker Monitoring**: Monitor worker availability, performance, and task execution
- **Activity Log**: View recent system events and activities
- **Mobile Worker Control**: Direct control and monitoring of mobile worker services

### 🎨 Modern UI/UX
- **Material Design 3**: Latest Android design language with professional aesthetics
- **Dark Mode Support**: Complete dark theme with automatic system detection
- **Responsive Layout**: Optimized for various screen sizes and orientations
- **Bottom Navigation**: Modern tab-based navigation for easy access
- **Professional Design**: Clean, emoji-free interface suitable for enterprise use

### 🔧 Advanced Features
- **Network Resilience**: Robust error handling with circuit breaker pattern
- **Automatic Retries**: Exponential backoff for network failures
- **Connection Pooling**: Optimized network performance
- **Real-time Updates**: WebSocket integration for live data
- **Settings Management**: Configurable network endpoints and parameters

## 🏗️ Architecture

The app follows clean architecture principles with modern Android development patterns:

### **Data Layer**
- **API Service**: Retrofit-based REST API client
- **WebSocket Manager**: Real-time communication with CrowdCompute framework
- **Repository Pattern**: Centralized data management with circuit breaker
- **Config Manager**: Network configuration and endpoint management

### **Domain Layer**
- **Use Cases**: Business logic and data processing
- **Models**: Data classes for jobs, workers, and system statistics
- **Error Handling**: Comprehensive exception management

### **Presentation Layer**
- **MVI Pattern**: Model-View-Intent for state management
- **ViewModels**: Lifecycle-aware data holders
- **Fragments**: Modular UI components
- **Theme Manager**: Dynamic theme switching and persistence

### **UI Layer**
- **Material 3**: Modern design system
- **ConstraintLayout**: Flexible, responsive layouts
- **ViewPager2**: Smooth tab navigation
- **RecyclerView**: Efficient list rendering

## 🚀 Setup & Installation

### Prerequisites
- **Android Studio**: Arctic Fox or later
- **Android SDK**: API level 27+ (Android 8.1+)
- **CrowdCompute Framework**: Running on your network
- **Network Access**: Device must be able to reach the CrowdCompute server

### Quick Start

1. **Clone the Repository**
   ```bash
   git clone <repository-url>
   cd MCC-Phase3
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Open the project folder
   - Wait for Gradle sync to complete

3. **Configure Network Settings**
   - Open `SettingsActivity` in the app
   - Update the Foreman IP address to match your server
   - Default ports: API (8000), WebSocket (9000)

4. **Build and Run**
   - Connect your Android device or start an emulator
   - Click "Run" in Android Studio
   - Grant necessary permissions when prompted

### Network Configuration

The app connects to CrowdCompute framework via:
- **API Endpoint**: `http://[FOREMAN_IP]:8000` (FastAPI worker)
- **WebSocket**: `ws://[FOREMAN_IP]:9000` (Foreman real-time updates)

**Common IP Addresses:**
- **Emulator**: `10.0.2.2` (localhost)
- **Physical Device**: Your computer's local IP (e.g., `192.168.1.100`)

## 📱 Usage Guide

### Dashboard Overview
- **Status Card**: Real-time connection status and system health
- **Quick Actions**: Mobile worker control and settings access
- **Tab Navigation**: Switch between Dashboard, Jobs, Workers, and Activity

### Theme Customization
- **Light Mode**: Clean, professional light interface
- **Dark Mode**: Modern dark theme for low-light environments
- **System Mode**: Automatically follows device theme settings
- **Toggle**: Tap the theme icon in the toolbar to switch themes

### Network Management
- **Circuit Breaker**: Automatic protection against cascading failures
- **Retry Logic**: Intelligent retry with exponential backoff
- **Connection Pooling**: Optimized network resource usage
- **Error Handling**: User-friendly error messages and recovery

## 🛠️ Technical Details

### Dependencies

#### **Networking**
- **Retrofit 2.9.0**: REST API client
- **OkHttp 4.9.0**: HTTP client with interceptors
- **Java-WebSocket**: WebSocket communication

#### **UI & Architecture**
- **Material 3**: Modern design system
- **Navigation Component**: Fragment navigation
- **ViewModel & LiveData**: Lifecycle-aware data management
- **ViewPager2**: Tab navigation
- **ConstraintLayout**: Flexible layouts

#### **JSON & Utilities**
- **Gson**: JSON serialization/deserialization
- **Coroutines**: Asynchronous programming
- **SharedPreferences**: Theme and settings persistence

### Network Resilience Features

#### **Circuit Breaker Pattern**
- **Threshold**: 5 consecutive failures
- **Timeout**: 60 seconds recovery period
- **Success Threshold**: 2 successful requests to close circuit
- **Manual Reset**: Available for debugging

#### **Retry Mechanism**
- **Max Retries**: 3 attempts per request
- **Backoff Strategy**: Exponential delay (1s, 2s, 4s)
- **Timeout Configuration**: 30s connect, 60s read, 30s write

#### **Connection Pooling**
- **Pool Size**: 5 connections
- **Keep-Alive**: 5 minutes
- **Optimization**: Reduced connection overhead

## 🎨 UI/UX Features

### **Material Design 3**
- **Color System**: Dynamic color palette with light/dark variants
- **Typography**: Consistent text hierarchy and readability
- **Components**: Modern buttons, cards, and navigation elements
- **Animations**: Smooth transitions and micro-interactions

### **Dark Mode Implementation**
- **Automatic Detection**: Follows system theme preferences
- **Manual Toggle**: Easy switching between themes
- **Persistent Settings**: Remembers user preferences
- **Consistent Theming**: All components support both themes

### **Professional Design**
- **Clean Interface**: Minimal, focused design without unnecessary elements
- **Information Hierarchy**: Clear visual organization of data
- **Accessibility**: Proper contrast ratios and touch targets
- **Responsive Layout**: Adapts to different screen sizes

## 🔧 Configuration

### **Settings Activity**
Access via the settings button to configure:
- **Foreman IP Address**: Server endpoint configuration
- **Port Settings**: API and WebSocket port customization
- **Connection Testing**: Verify network connectivity
- **Reset Options**: Restore default settings

### **Theme Management**
- **Theme Persistence**: Settings saved across app sessions
- **Dynamic Switching**: Instant theme application
- **System Integration**: Respects device theme preferences

## 🐛 Troubleshooting

### **Common Issues**

#### **Connection Problems**
- **Verify Server**: Ensure CrowdCompute framework is running
- **Check IP Address**: Confirm correct Foreman IP in settings
- **Network Access**: Test connectivity from device to server
- **Firewall**: Ensure ports 8000 and 9000 are accessible

#### **Build Errors**
- **Gradle Sync**: Refresh project and sync dependencies
- **SDK Version**: Ensure Android SDK 27+ is installed
- **Dependencies**: Check all required libraries are included

#### **Runtime Issues**
- **Permissions**: Grant necessary network permissions
- **Memory**: Close other apps if experiencing slowdowns
- **Theme Issues**: Try switching themes or restarting the app

### **Debug Features**
- **Circuit Breaker Status**: Monitor in dashboard status
- **Network Logs**: Check Android Studio logcat for detailed errors
- **Manual Reset**: Use reset button for circuit breaker issues

## 🤝 Contributing

We welcome contributions! Please follow these guidelines:

### **Code Standards**
- **Kotlin Best Practices**: Follow official Kotlin conventions
- **Clean Architecture**: Maintain separation of concerns
- **Material Design**: Adhere to Material 3 guidelines
- **Error Handling**: Implement proper exception management

### **Development Process**
1. **Fork** the repository
2. **Create** a feature branch
3. **Implement** your changes
4. **Test** thoroughly on different devices
5. **Submit** a pull request with detailed description

### **Testing Requirements**
- **Unit Tests**: For business logic and utilities
- **UI Tests**: For critical user flows
- **Integration Tests**: For API and WebSocket functionality
- **Theme Testing**: Verify both light and dark modes

## 📄 License

This project is part of the CrowdCompute framework. Please refer to the main project license for usage terms.

## 🔗 Related Projects

- **CrowdCompute Framework**: Main distributed computing platform
- **Foreman Service**: Central coordination service
- **Worker Services**: Task execution services

---

**Built with ❤️ for the CrowdCompute community**
