# CrowdCompute Mobile App

A modern Android mobile application for monitoring and managing CrowdCompute distributed computing framework.

## Features

- **Real-time Dashboard**: View live statistics of jobs, tasks, and workers
- **Job Management**: Monitor job progress and status
- **Worker Monitoring**: Track worker performance and availability
- **Activity Log**: View recent system activities
- **WebSocket Integration**: Real-time updates from the CrowdCompute framework

## Architecture

The app follows clean architecture principles and uses the MVI (Model-View-Intent) pattern:

- **Data Layer**: API service, WebSocket manager, and repository
- **Domain Layer**: Use cases and business logic
- **Presentation Layer**: ViewModels, Fragments, and UI components
- **UI Layer**: Material 3 design with XML layouts

## Setup

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 27+ (API level 27)
- CrowdCompute framework running on your network

### Configuration

1. **Update API Base URL**: 
   - For emulator: `http://10.0.2.2:8000` (default)
   - For physical device: Update `ApiClient.kt` with your server's IP address

2. **Update WebSocket URL**:
   - Default: `ws://10.0.2.2:9000`
   - Update in `MainViewModel.kt` for your network

### Network Configuration

The app is configured to connect to:
- **API Endpoint**: Port 8000 (FastAPI worker)
- **WebSocket**: Port 9000 (Foreman)

Make sure your CrowdCompute framework is accessible from your mobile device's network.

## Usage

1. **Dashboard**: View overall system statistics and connection status
2. **Jobs**: Monitor job progress, completion status, and task counts
3. **Workers**: Track worker availability, performance, and current tasks
4. **Activity**: View recent system events and activities

## Dependencies

- **Networking**: Retrofit, OkHttp
- **WebSocket**: Java-WebSocket
- **UI**: Material 3, ConstraintLayout, RecyclerView
- **Architecture**: Navigation Component, ViewModel, LiveData
- **JSON**: Gson

## Building

1. Clone the repository
2. Open in Android Studio
3. Update network configuration if needed
4. Build and run on your device

## Troubleshooting

- **Connection Issues**: Verify your CrowdCompute framework is running and accessible
- **Network Errors**: Check firewall settings and network configuration
- **Build Errors**: Ensure all dependencies are properly synced

## Contributing

This app follows Kotlin best practices and Material Design guidelines. When contributing:

- Follow the established architecture patterns
- Use proper error handling and loading states
- Maintain consistent UI/UX patterns
- Add appropriate tests for new functionality
