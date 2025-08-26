# Test functions for Chaquopy integration
# These functions can be called from the Android app

def add_numbers(a, b):
    """Add two numbers"""
    return a + b

def multiply_numbers(a, b):
    """Multiply two numbers"""
    return a * b

def calculate_factorial(n):
    """Calculate factorial of a number"""
    if n <= 1:
        return 1
    return n * calculate_factorial(n - 1)

def fibonacci(n):
    """Calculate nth Fibonacci number"""
    if n <= 1:
        return n
    a, b = 0, 1
    for _ in range(2, n + 1):
        a, b = b, a + b
    return b

def process_list(numbers):
    """Process a list of numbers"""
    if not numbers:
        return []
    return {
        'sum': sum(numbers),
        'average': sum(numbers) / len(numbers),
        'max': max(numbers),
        'min': min(numbers),
        'count': len(numbers)
    }

def string_operations(text):
    """Perform string operations"""
    return {
        'original': text,
        'uppercase': text.upper(),
        'lowercase': text.lower(),
        'length': len(text),
        'words': len(text.split()),
        'reversed': text[::-1]
    }

def numpy_operations(data):
    """Perform numpy operations if available"""
    try:
        import numpy as np
        arr = np.array(data)
        return {
            'array': arr.tolist(),
            'mean': float(np.mean(arr)),
            'std': float(np.std(arr)),
            'sum': float(np.sum(arr)),
            'shape': arr.shape
        }
    except ImportError:
        return {'error': 'numpy not available'}

def pandas_operations(data):
    """Perform pandas operations if available"""
    try:
        import pandas as pd
        df = pd.DataFrame(data)
        return {
            'dataframe': df.to_dict(),
            'shape': df.shape,
            'columns': df.columns.tolist(),
            'describe': df.describe().to_dict()
        }
    except ImportError:
        return {'error': 'pandas not available'}

# Main execution function for testing
def execute_function(function_name, *args):
    """Execute a function by name with arguments"""
    functions = {
        'add_numbers': add_numbers,
        'multiply_numbers': multiply_numbers,
        'calculate_factorial': calculate_factorial,
        'fibonacci': fibonacci,
        'process_list': process_list,
        'string_operations': string_operations,
        'numpy_operations': numpy_operations,
        'pandas_operations': pandas_operations
    }
    
    if function_name in functions:
        return functions[function_name](*args)
    else:
        return {'error': f'Function {function_name} not found'}

# Test execution
if __name__ == "__main__":
    # Test basic functions
    print("Testing basic functions:")
    print(f"add_numbers(5, 3) = {add_numbers(5, 3)}")
    print(f"multiply_numbers(4, 7) = {multiply_numbers(4, 7)}")
    print(f"calculate_factorial(5) = {calculate_factorial(5)}")
    print(f"fibonacci(10) = {fibonacci(10)}")
    
    # Test list processing
    numbers = [1, 2, 3, 4, 5]
    print(f"process_list({numbers}) = {process_list(numbers)}")
    
    # Test string operations
    text = "Hello World from Python!"
    print(f"string_operations('{text}') = {string_operations(text)}")
