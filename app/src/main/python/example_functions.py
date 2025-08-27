"""
Example functions for testing the mobile worker
These functions match the format expected by the CrowdCompute backend
"""

def add_numbers(args):
    """
    Add two numbers
    Args: args - tuple of (a, b) where a and b are numbers
    Returns: sum of a and b
    """
    a, b = args
    return a + b


def multiply_numbers(args):
    """
    Multiply two numbers
    Args: args - tuple of (a, b) where a and b are numbers
    Returns: product of a and b
    """
    a, b = args
    return a * b


def fibonacci(n):
    """
    Calculate fibonacci number
    Args: n - integer
    Returns: fibonacci number at position n
    """
    if n <= 1:
        return n
    return fibonacci(n-1) + fibonacci(n-2)


def process_list(args):
    """
    Process a list of numbers
    Args: args - list of numbers
    Returns: sum of all numbers in the list
    """
    numbers = args
    return sum(numbers)


def string_operations(args):
    """
    Perform string operations
    Args: args - tuple of (text, operation) where operation is 'upper', 'lower', or 'length'
    Returns: processed string or length
    """
    text, operation = args
    if operation == 'upper':
        return text.upper()
    elif operation == 'lower':
        return text.lower()
    elif operation == 'length':
        return len(text)
    else:
        return f"Unknown operation: {operation}"


def math_operations(args):
    """
    Perform mathematical operations
    Args: args - tuple of (operation, a, b) where operation is 'add', 'subtract', 'multiply', 'divide'
    Returns: result of mathematical operation
    """
    operation, a, b = args
    if operation == 'add':
        return a + b
    elif operation == 'subtract':
        return a - b
    elif operation == 'multiply':
        return a * b
    elif operation == 'divide':
        if b == 0:
            raise ValueError("Division by zero")
        return a / b
    else:
        raise ValueError(f"Unknown operation: {operation}")


def data_processing(args):
    """
    Process data (example for more complex operations)
    Args: args - list of dictionaries with 'name' and 'value' keys
    Returns: dictionary with statistics
    """
    data = args
    if not data:
        return {"count": 0, "sum": 0, "average": 0}
    
    values = [item['value'] for item in data if 'value' in item]
    count = len(values)
    total = sum(values)
    average = total / count if count > 0 else 0
    
    return {
        "count": count,
        "sum": total,
        "average": average,
        "min": min(values) if values else None,
        "max": max(values) if values else None
    }


# Dictionary of all available functions for easy testing
EXAMPLE_FUNCTIONS = {
    "add_numbers": add_numbers,
    "multiply_numbers": multiply_numbers,
    "fibonacci": fibonacci,
    "process_list": process_list,
    "string_operations": string_operations,
    "math_operations": math_operations,
    "data_processing": data_processing
}
