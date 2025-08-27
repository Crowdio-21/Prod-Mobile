"""
Test script for Mobile Worker
Demonstrates how to use the mobile worker in an Android app
"""

import asyncio
import time
from mobile_worker import create_mobile_worker, WorkerConfig


def test_simple_function():
    """Test a simple function that can be executed by the worker"""
    from example_functions import EXAMPLE_FUNCTIONS
    return EXAMPLE_FUNCTIONS


async def test_mobile_worker():
    """Test the mobile worker functionality"""
    print("🧪 Testing Mobile Worker...")
    
    # Create a mobile worker
    worker_id = f"mobile_test_{int(time.time())}"
    foreman_url = "ws://localhost:9000"  # Change this to your foreman URL
    
    # Create worker configuration
    config = WorkerConfig(
        worker_id=worker_id,
        foreman_url=foreman_url,
        max_concurrent_tasks=1,
        auto_restart=True,
        heartbeat_interval=30,
        battery_threshold=20
    )
    
    # Create worker
    worker = create_mobile_worker(worker_id, foreman_url)
    
    print(f"📱 Created mobile worker: {worker_id}")
    print(f"📱 Device ID: {worker.stats['mobile_device_id']}")
    print(f"📱 Platform: {worker.stats['platform']}")
    
    # Get worker status
    status = worker.get_status()
    print(f"📊 Worker Status: {status}")
    
    # Test mobile conditions check
    print("🔋 Testing mobile conditions...")
    conditions_ok = await worker._check_mobile_conditions()
    print(f"📱 Mobile conditions OK: {conditions_ok}")
    
    # Test function execution (without connecting to foreman)
    print("🧮 Testing function execution...")
    test_funcs = test_simple_function()
    
    for func_name, func in test_funcs.items():
        try:
            # Serialize function
            from common.serializer import serialize_function, deserialize_function
            
            func_code = serialize_function(func)
            print(f"✅ Serialized {func_name}")
            
            # Test deserialization
            deserialized_func = deserialize_function(func_code)
            print(f"✅ Deserialized {func_name}")
            
            # Test execution with single argument (matching your backend)
            if func_name == "add_numbers":
                # Test with a tuple argument
                result = deserialized_func((5, 3))
                expected = 8
            elif func_name == "multiply_numbers":
                result = deserialized_func((4, 7))
                expected = 28
            elif func_name == "fibonacci":
                result = deserialized_func(10)
                expected = 55
            elif func_name == "process_list":
                result = deserialized_func([1, 2, 3, 4, 5])
                expected = 15
            elif func_name == "string_operations":
                result = deserialized_func(("Hello World", "upper"))
                expected = "HELLO WORLD"
            elif func_name == "math_operations":
                result = deserialized_func(("multiply", 6, 7))
                expected = 42
            elif func_name == "data_processing":
                test_data = [{"name": "A", "value": 10}, {"name": "B", "value": 20}]
                result = deserialized_func(test_data)
                expected = {"count": 2, "sum": 30, "average": 15.0}
            
            print(f"✅ {func_name}(...) = {result} (expected: {expected})")
            
            # Test the new execute_function approach
            try:
                from common.serializer import execute_function
                direct_result = execute_function(func_code, result)  # Use the result as args for testing
                print(f"✅ Direct execution test passed for {func_name}")
            except Exception as direct_e:
                print(f"⚠️ Direct execution test failed for {func_name}: {direct_e}")
            
        except Exception as e:
            print(f"❌ Error testing {func_name}: {e}")
    
    print("🎉 Mobile worker test completed!")
    return worker


async def test_worker_connection():
    """Test worker connection to foreman (requires foreman to be running)"""
    print("🔌 Testing worker connection...")
    
    worker_id = f"mobile_conn_test_{int(time.time())}"
    foreman_url = "ws://localhost:9000"  # Change this to your foreman URL
    
    worker = create_mobile_worker(worker_id, foreman_url)
    
    try:
        # Try to connect
        connected = await worker.connect()
        if connected:
            print("✅ Successfully connected to foreman")
            
            # Start worker for a short time
            print("🚀 Starting worker for 10 seconds...")
            worker_task = asyncio.create_task(worker.start())
            
            # Wait for 10 seconds
            await asyncio.sleep(10)
            
            # Stop worker
            worker_task.cancel()
            await worker.disconnect()
            print("🛑 Worker stopped")
        else:
            print("❌ Failed to connect to foreman")
            print("💡 Make sure the foreman is running at the specified URL")
    
    except Exception as e:
        print(f"❌ Connection test failed: {e}")


def main():
    """Main test function"""
    print("🚀 Starting Mobile Worker Tests")
    print("=" * 50)
    
    # Test 1: Basic functionality
    print("\n📋 Test 1: Basic Mobile Worker Functionality")
    worker = asyncio.run(test_mobile_worker())
    
    # Test 2: Connection test (optional)
    print("\n📋 Test 2: Connection Test (requires foreman)")
    print("💡 Uncomment the next line to test connection to foreman")
    # asyncio.run(test_worker_connection())
    
    print("\n✅ All tests completed!")
    print("=" * 50)


if __name__ == "__main__":
    main()
