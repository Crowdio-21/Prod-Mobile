"""
Serializer module for CrowdCompute mobile worker
Handles function serialization and deserialization using JSON
"""

import json
import sys
import inspect
from typing import Any, Callable, Dict, Optional


def serialize_function(func: Callable) -> str:
    """
    Serialize a function to a string representation using JSON
    
    Args:
        func: The function to serialize
        
    Returns:
        JSON string representation of the function
    """
    try:
        # Get function source code
        source = inspect.getsource(func)
        
        # Create function representation
        func_repr = {
            "source": source,
            "name": func.__name__,
            "module": func.__module__,
            "globals": {}  # We'll need to handle globals separately
        }
        
        # Serialize to JSON
        return json.dumps(func_repr)
    except Exception as e:
        raise ValueError(f"Failed to serialize function: {e}")


def deserialize_function(func_code: str) -> Callable:
    """
    Deserialize a function from a string representation
    
    Args:
        func_code: JSON string representation of the function
        
    Returns:
        The deserialized function
    """
    try:
        # Parse JSON
        func_repr = json.loads(func_code)
        
        # Extract source code
        source = func_repr["source"]
        
        # Create a new namespace for the function
        namespace = {}
        
        # Execute the source code in the namespace
        exec(source, namespace)
        
        # Get the function from the namespace
        func_name = func_repr["name"]
        if func_name in namespace:
            return namespace[func_name]
        else:
            raise ValueError(f"Function {func_name} not found in deserialized code")
            
    except Exception as e:
        raise ValueError(f"Failed to deserialize function: {e}")


def execute_function(func_code: str, args: Any) -> Any:
    """
    Execute a function directly from its code string
    
    Args:
        func_code: Function code as string (either JSON or direct code)
        args: Arguments to pass to the function
        
    Returns:
        Result of function execution
    """
    try:
        # Try to parse as JSON first (for backward compatibility)
        try:
            func = deserialize_function(func_code)
        except (json.JSONDecodeError, KeyError, ValueError):
            # If JSON parsing fails, treat as direct function code
            func = _create_function_from_code(func_code)
        
        # Execute the function with the provided arguments
        if isinstance(args, list):
            # If args is a list, unpack it
            if len(args) == 1:
                result = func(args[0])
            else:
                result = func(*args)
        else:
            # Single argument
            result = func(args)
            
        return result
        
    except Exception as e:
        raise ValueError(f"Failed to execute function: {e}")


def _create_function_from_code(func_code: str) -> Callable:
    """
    Create a function from direct code string
    
    Args:
        func_code: Function code as string
        
    Returns:
        The created function
    """
    try:
        # Create a new namespace for the function
        namespace = {}
        
        # Execute the source code in the namespace
        exec(func_code, namespace)
        
        # Find the first function in the namespace
        for name, obj in namespace.items():
            if callable(obj) and not name.startswith('_'):
                return obj
        
        raise ValueError("No function found in the provided code")
        
    except Exception as e:
        raise ValueError(f"Failed to create function from code: {e}")


def get_runtime_info() -> Dict[str, Any]:
    """
    Get information about the current runtime environment
    
    Returns:
        Dictionary containing runtime information
    """
    return {
        "python_version": sys.version,
        "platform": sys.platform,
        "executable": sys.executable,
        "path": sys.path[:5],  # First 5 entries to avoid too much data
        "modules_loaded": len(sys.modules)
    }


def safe_json_serialize(obj: Any) -> str:
    """
    Safely serialize an object to JSON, handling non-serializable types
    
    Args:
        obj: Object to serialize
        
    Returns:
        JSON string representation
    """
    try:
        return json.dumps(obj, default=str)
    except Exception as e:
        # Fallback: convert to string representation
        return json.dumps(str(obj))


def safe_json_deserialize(json_str: str) -> Any:
    """
    Safely deserialize a JSON string
    
    Args:
        json_str: JSON string to deserialize
        
    Returns:
        Deserialized object
    """
    try:
        return json.loads(json_str)
    except Exception as e:
        raise ValueError(f"Failed to deserialize JSON: {e}")


class FunctionValidator:
    """Validator for function safety and compatibility"""
    
    @staticmethod
    def validate_function(func: Callable) -> bool:
        """
        Validate that a function is safe to execute
        
        Args:
            func: Function to validate
            
        Returns:
            True if function is considered safe
        """
        try:
            # Check if function is callable
            if not callable(func):
                return False
            
            # Check function name for potentially dangerous operations
            func_name = func.__name__.lower()
            dangerous_keywords = [
                'exec', 'eval', 'compile', 'open', 'file', 'system',
                'subprocess', 'os.', 'sys.', '__import__'
            ]
            
            for keyword in dangerous_keywords:
                if keyword in func_name:
                    return False
            
            # Check source code if available
            try:
                import inspect
                source = inspect.getsource(func)
                for keyword in dangerous_keywords:
                    if keyword in source:
                        return False
            except (OSError, TypeError):
                # Can't inspect source, rely on name check
                pass
            
            return True
            
        except Exception:
            return False
    
    @staticmethod
    def get_function_info(func: Callable) -> Dict[str, Any]:
        """
        Get information about a function
        
        Args:
            func: Function to inspect
            
        Returns:
            Dictionary with function information
        """
        try:
            import inspect
            
            return {
                "name": func.__name__,
                "module": func.__module__,
                "doc": func.__doc__,
                "args": inspect.signature(func).parameters.keys(),
                "is_coroutine": inspect.iscoroutinefunction(func)
            }
        except Exception as e:
            return {
                "name": getattr(func, '__name__', 'unknown'),
                "error": str(e)
            }
