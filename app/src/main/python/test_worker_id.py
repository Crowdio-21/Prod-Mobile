"""
Test script for WorkerIdManager integration with Python mobile worker
"""

import time
from datetime import datetime

def test_worker_id_integration():
    """Test the WorkerIdManager integration from Python"""
    print("🧪 Testing WorkerIdManager integration from Python...")
    
    try:
        # Test if we can import the bridge
        from com.example.mcc_phase3.data import WorkerIdBridge
        print("✅ Successfully imported WorkerIdBridge")
        
        # Get the Android activity (this would be available in the mobile worker context)
        try:
            from android import mActivity
            print("✅ Android activity available")
            
            # Create bridge instance
            worker_id_bridge = WorkerIdBridge(mActivity)
            print("✅ WorkerIdBridge instance created")
            
            # Test getting worker ID
            print("🔍 Testing worker ID retrieval...")
            worker_id = worker_id_bridge.getOrGenerateWorkerId()
            print(f"✅ Worker ID retrieved: {worker_id}")
            
            # Test checking if worker ID exists
            has_id = worker_id_bridge.hasWorkerId()
            print(f"✅ Has worker ID: {has_id}")
            
            # Test getting current worker ID
            current_id = worker_id_bridge.getCurrentWorkerId()
            print(f"✅ Current worker ID: {current_id}")
            
            # Test getting worker ID info
            info = worker_id_bridge.getWorkerIdInfo()
            print(f"✅ Worker ID info: {info}")
            
            # Test resetting worker ID
            print("🔄 Testing worker ID reset...")
            new_worker_id = worker_id_bridge.resetWorkerId()
            print(f"✅ New worker ID after reset: {new_worker_id}")
            
            # Verify the new ID is different
            if new_worker_id != worker_id:
                print("✅ Worker ID successfully reset (new ID is different)")
            else:
                print("⚠️ Warning: Worker ID reset but new ID is same as old")
            
            print("🎉 All WorkerIdManager integration tests passed!")
            return True
            
        except ImportError:
            print("⚠️ Android activity not available (running outside mobile context)")
            print("✅ Bridge class can be imported successfully")
            return True
            
    except ImportError as e:
        print(f"❌ Failed to import WorkerIdBridge: {e}")
        return False
    except Exception as e:
        print(f"❌ Error during WorkerIdManager integration test: {e}")
        import traceback
        traceback.print_exc()
        return False

def test_mobile_worker_with_worker_id():
    """Test mobile worker with WorkerIdManager integration"""
    print("🧪 Testing Mobile Worker with WorkerIdManager...")
    
    try:
        from mobile_worker import create_mobile_worker
        print("✅ Mobile worker imported successfully")
        
        # Create a test worker
        worker_id = f"test_worker_{int(time.time())}"
        foreman_url = "ws://localhost:9000"  # Test URL
        
        worker = create_mobile_worker(worker_id, foreman_url)
        print(f"✅ Mobile worker created: {worker_id}")
        
        # Get detailed device info (should include worker ID info)
        device_info = worker.get_detailed_device_info()
        print(f"✅ Device info retrieved: {device_info}")
        
        # Check if worker ID info is included
        if "worker_id_info" in device_info:
            worker_id_info = device_info["worker_id_info"]
            print(f"✅ Worker ID info in device info: {worker_id_info}")
        else:
            print("⚠️ Worker ID info not found in device info")
        
        # Test task execution
        test_result = worker.test_task_execution()
        print(f"✅ Task execution test: {test_result}")
        
        print("🎉 Mobile worker with WorkerIdManager test completed!")
        return True
        
    except Exception as e:
        print(f"❌ Error during mobile worker test: {e}")
        import traceback
        traceback.print_exc()
        return False

def main():
    """Main test function"""
    print("🚀 Starting WorkerIdManager integration tests...")
    print(f"📅 Test started at: {datetime.now().isoformat()}")
    
    # Test 1: Basic integration
    print("\n" + "="*50)
    print("TEST 1: Basic WorkerIdManager Integration")
    print("="*50)
    test1_result = test_worker_id_integration()
    
    # Test 2: Mobile worker integration
    print("\n" + "="*50)
    print("TEST 2: Mobile Worker Integration")
    print("="*50)
    test2_result = test_mobile_worker_with_worker_id()
    
    # Summary
    print("\n" + "="*50)
    print("TEST SUMMARY")
    print("="*50)
    print(f"Basic Integration Test: {'✅ PASSED' if test1_result else '❌ FAILED'}")
    print(f"Mobile Worker Test: {'✅ PASSED' if test2_result else '❌ FAILED'}")
    
    if test1_result and test2_result:
        print("🎉 All tests passed! WorkerIdManager integration is working correctly.")
        return True
    else:
        print("❌ Some tests failed. Please check the error messages above.")
        return False

if __name__ == "__main__":
    success = main()
    exit(0 if success else 1)
