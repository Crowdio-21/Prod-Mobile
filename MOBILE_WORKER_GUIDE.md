# Mobile Worker Guide for CrowdCompute

This guide explains how to use the mobile-adapted worker for CrowdCompute that runs on Android devices using Chaquopy.

## Overview

The mobile worker is a Python-based distributed computing worker that has been adapted to run on Android devices. It provides the same functionality as the desktop worker but with mobile-specific optimizations:

- **Battery Management**: Automatically pauses work when battery is low
- **Network Monitoring**: Checks network connectivity before executing tasks
- **Mobile Logging**: Logs to device storage for debugging
- **Android Integration**: Uses Android-specific APIs for device information
- **JSON Serialization**: Uses JSON-based function serialization (compatible with uv and Python 3.12.6)

## Key Features

### 🚀 Mobile-Optimized
- Battery level monitoring and automatic pausing
- Network connectivity checks
- Device-specific logging
- Android ID-based device identification

### 🔧 Easy Integration
- Simple Kotlin service integration
- Clean Material Design UI
- Real-time status monitoring
- Test functionality included

### 📱 Android-Specific
- Uses Chaquopy for Python execution (Python 3.12)
- Android WebSocket client integration
- Device storage for logs
- Battery and network status monitoring
- JSON-based function serialization (no pickle)

## File Structure

```
app/src/main/
├── python/
│   ├── mobile_worker.py          # Main mobile worker implementation
│   ├── test_mobile_worker.py     # Test script for worker functionality
│   ├── example_functions.py      # Example functions for testing
│   └── common/
│       ├── __init__.py
│       ├── serializer.py         # JSON-based function serialization
│       └── protocol.py           # Communication protocol definitions
├── java/com/example/mcc_phase3/
│   ├── services/
│   │   └── MobileWorkerService.kt    # Android service for worker management
│   └── MobileWorkerActivity.kt       # UI for worker control
└── res/
    ├── layout/
    │   └── activity_mobile_worker.xml # UI layout
    └── drawable/
        ├── status_background.xml      # Status background styles
        ├── stats_background.xml
        └── info_background.xml
```

## Quick Start

### 1. Build and Run

1. Open the project in Android Studio
2. Build the project: `./gradlew build`
3. Install on device: `./gradlew installDebug`
4. Launch the app and navigate to the Mobile Worker activity

### 2. Basic Usage

```kotlin
// Start the mobile worker service
val intent = Intent(this, MobileWorkerService::class.java)
bindService(intent, connection, Context.BIND_AUTO_CREATE)

// Start the worker
workerService?.startWorker("ws://your-foreman-url:9000")

// Check status
val isRunning = workerService?.isWorkerRunning()
val stats = workerService?.getWorkerStats()
```

### 3. Python Integration

```python
# Create a mobile worker
from mobile_worker import create_mobile_worker

worker = create_mobile_worker("my_worker", "ws://foreman:9000")

# Start the worker
await worker.start()

# Get status
status = worker.get_status()
```

## Configuration

### Worker Configuration

```python
from mobile_worker import WorkerConfig

config = WorkerConfig(
    worker_id="my_mobile_worker",
    foreman_url="ws://localhost:9000",
    max_concurrent_tasks=1,
    auto_restart=True,
    heartbeat_interval=30,
    battery_threshold=20  # Pause when battery < 20%
)
```

### Android Service Configuration

```kotlin
// In your Android app
val service = MobileWorkerService()
service.startWorker("ws://your-foreman:9000")
```

## Mobile-Specific Features

### Battery Management

The mobile worker automatically monitors battery levels and pauses work when:
- Battery level drops below the configured threshold (default: 20%)
- Device is not charging
- Battery is critically low

```python
# Check mobile conditions
conditions_ok = await worker._check_mobile_conditions()
if not conditions_ok:
    print("Mobile conditions not suitable for work")
```

### Network Monitoring

The worker checks network connectivity before executing tasks:

```python
# Network status is automatically checked
if not worker.network_available:
    print("Network not available, pausing work")
```

### Device Logging

Logs are stored in device storage for debugging:

```
/storage/emulated/0/CrowdCompute/logs/worker_[worker_id].log
```

## Testing

### Run Tests

```python
# Test basic functionality
python test_mobile_worker.py

# Test connection (requires foreman)
# Uncomment the connection test in test_mobile_worker.py
```

### Android Testing

```kotlin
// Test worker functionality
workerService?.testWorker()

// Check worker status
val status = workerService?.getWorkerStatus()
```

## Troubleshooting

### Common Issues

1. **Python Module Not Found**
   - Ensure Chaquopy is properly configured in `build.gradle.kts`
   - Check that Python files are in the correct location

2. **WebSocket Connection Failed**
   - Verify foreman URL is correct
   - Check network connectivity
   - Ensure foreman is running

3. **Battery Issues**
   - Check battery threshold configuration
   - Verify charging status
   - Review battery optimization settings

4. **Permission Issues**
   - Ensure INTERNET permission is granted
   - Check storage permissions for logging

### Debug Logging

Enable debug logging by checking the logcat output:

```bash
adb logcat | grep MobileWorkerService
```

## Integration with Foreman

The mobile worker communicates with the CrowdCompute foreman using the same protocol as desktop workers, with additional mobile-specific information:

### Message Types

- `WORKER_READY`: Includes mobile device information
- `WORKER_HEARTBEAT`: Includes battery and network status
- `TASK_RESULT`: Includes mobile device ID
- `MOBILE_STATUS`: Mobile-specific status updates

### Example Messages

```json
{
  "type": "worker_ready",
  "data": {
    "worker_id": "android_worker_123",
    "mobile_device_id": "android_abc123",
    "platform": "android",
    "battery_level": 85,
    "is_charging": false
  }
}
```

## Performance Considerations

### Mobile Optimizations

1. **Battery Efficiency**
   - Automatic pausing when battery is low
   - Reduced heartbeat frequency
   - Efficient task execution

2. **Network Efficiency**
   - Connection pooling
   - Automatic reconnection
   - Network status monitoring

3. **Memory Management**
   - Efficient function serialization
   - Automatic cleanup of completed tasks
   - Memory-conscious logging

### Best Practices

1. **Battery Management**
   - Set appropriate battery thresholds
   - Monitor charging status
   - Pause work during critical battery levels

2. **Network Usage**
   - Use efficient WebSocket connections
   - Implement retry logic
   - Monitor network quality

3. **Task Execution**
   - Keep tasks lightweight
   - Avoid long-running operations
   - Implement proper error handling

## Security Considerations

### Function Safety

The mobile worker uses JSON-based serialization for better security:

```python
from common.serializer import serialize_function, deserialize_function

# Serialize function to JSON
func_code = serialize_function(my_function)

# Deserialize and execute
func = deserialize_function(func_code)
result = func(args)
```

This approach is more secure than pickle and compatible with modern Python environments.

### Network Security

- Use secure WebSocket connections (WSS) in production
- Implement proper authentication
- Validate all incoming messages

## Future Enhancements

### Planned Features

1. **Advanced Battery Management**
   - Predictive battery usage
   - Smart task scheduling
   - Power-aware execution

2. **Enhanced Monitoring**
   - Real-time performance metrics
   - Advanced logging
   - Crash reporting

3. **Improved UI**
   - Real-time task visualization
   - Performance graphs
   - Advanced configuration options

## Support

For issues and questions:

1. Check the troubleshooting section
2. Review the log files
3. Test with the provided test scripts
4. Verify configuration settings

## License

This mobile worker is part of the CrowdCompute project and follows the same licensing terms.
