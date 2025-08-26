# Chaquopy Integration for Python Execution on Android

This document describes the integration of Chaquopy to enable Python code execution on Android devices for the CROWDio Mobile Worker.

## Overview

The mobile worker now supports executing Python code sent from the backend using Chaquopy, a Python SDK for Android. This allows the mobile device to run Python 3.12.6 code locally without requiring a separate Python server.

## Configuration

### Build Configuration

The project has been configured with Chaquopy in the following files:

1. **Project-level build.gradle.kts**: Added Chaquopy plugin
2. **App-level build.gradle.kts**: Added Chaquopy configuration with Python 3.12.6
3. **settings.gradle.kts**: Added Chaquopy repository

### Worker Configuration

The `WorkerConfig.kt` file has been updated with:

- `PYTHON_LOCAL = true` (default mode)
- `SIMULATION_MODE = false` (disabled)
- Updated capabilities to include Python execution support

## Architecture

### Components

1. **PythonExecutor.kt**: Main class for Python code execution
2. **WorkerService.kt**: Updated to handle Python tasks
3. **test_functions.py**: Sample Python functions for testing

### Execution Flow

1. Backend sends serialized Python code and arguments via WebSocket
2. WorkerService detects Python task and routes to PythonExecutor
3. PythonExecutor deserializes code and arguments
4. Chaquopy executes the Python code
5. Results are returned to the backend

## Python Code Format

The backend should send Python tasks in the following format:

```json
{
  "type": "assign_task",
  "data": {
    "task_id": "task_123",
    "python_code": "base64_encoded_python_code",
    "python_args": "base64_encoded_arguments"
  }
}
```

### Supported Fields

- `python_code` / `serialized_code` / `code`: Serialized Python code
- `python_args` / `serialized_args` / `args` / `arguments`: Serialized arguments

## Python Libraries

The following Python libraries are pre-installed:

- **numpy**: Numerical computing
- **pandas**: Data manipulation and analysis
- **scipy**: Scientific computing
- **matplotlib**: Plotting and visualization
- **requests**: HTTP library
- **pickle-mixin**: Enhanced pickle support

## Usage Examples

### Basic Python Function

```python
def add_numbers(a, b):
    return a + b
```

### NumPy Operations

```python
import numpy as np

def process_array(data):
    arr = np.array(data)
    return {
        'mean': float(np.mean(arr)),
        'std': float(np.std(arr)),
        'sum': float(np.sum(arr))
    }
```

### Pandas Operations

```python
import pandas as pd

def analyze_data(data):
    df = pd.DataFrame(data)
    return {
        'shape': df.shape,
        'describe': df.describe().to_dict()
    }
```

## Serialization

### Code Serialization

Python code should be serialized as base64-encoded strings:

```python
import base64

code = "def hello(name): return f'Hello, {name}!'"
serialized_code = base64.b64encode(code.encode()).decode()
```

### Arguments Serialization

Arguments should be serialized using Python's pickle module:

```python
import pickle
import base64

args = [1, 2, 3, "test"]
serialized_args = base64.b64encode(pickle.dumps(args)).decode()
```

## Error Handling

The system includes comprehensive error handling:

1. **Python Runtime Errors**: Caught and reported back to backend
2. **Serialization Errors**: Graceful fallback to string processing
3. **Missing Dependencies**: Error messages for unavailable libraries
4. **Timeout Handling**: Task timeout protection

## Testing

### Local Testing

Use the provided `test_functions.py` for testing:

```kotlin
val pythonExecutor = PythonExecutor(context)
val result = pythonExecutor.executeSimpleFunction(
    "def add(a, b): return a + b",
    listOf(5, 3)
)
```

### Backend Integration Testing

Send test tasks through the WebSocket connection:

```json
{
  "type": "assign_task",
  "data": {
    "task_id": "test_001",
    "python_code": "ZGVmIGFkZChhLCBiKTogcmV0dXJuIGEgKyBi",
    "python_args": "gAJLAUsC"
  }
}
```

## Performance Considerations

1. **Memory Usage**: Python runtime consumes additional memory
2. **Startup Time**: First Python execution has initialization overhead
3. **Concurrent Tasks**: Limited to 1 concurrent task for stability
4. **Battery Impact**: Python execution may impact battery life

## Troubleshooting

### Common Issues

1. **Python Runtime Not Available**
   - Check Chaquopy initialization in logs
   - Verify Python version compatibility

2. **Serialization Errors**
   - Ensure proper base64 encoding
   - Check pickle compatibility

3. **Missing Libraries**
   - Verify library installation in build.gradle.kts
   - Check import statements in Python code

### Debug Logging

Enable detailed logging in `WorkerConfig.kt`:

```kotlin
const val DETAILED_LOGGING = true
const val LOG_FUNCTION_CONTENT = true
```

## Security Considerations

1. **Code Execution**: Python code runs with full system access
2. **Input Validation**: Validate all inputs before execution
3. **Resource Limits**: Implement timeout and memory limits
4. **Sandboxing**: Consider additional sandboxing for production

## Future Enhancements

1. **Virtual Environment Support**: Isolated Python environments
2. **Library Management**: Dynamic library installation
3. **Performance Optimization**: JIT compilation and caching
4. **Security Hardening**: Enhanced sandboxing and validation

## Dependencies

- **Chaquopy**: Python SDK for Android (version 15.0.1)
- **Python**: Version 3.12.6
- **Android**: Minimum SDK 27, Target SDK 36

## License

Chaquopy requires a license for commercial use. See [Chaquopy Licensing](https://chaquo.com/chaquopy/license/) for details.
