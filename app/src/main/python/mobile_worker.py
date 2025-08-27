"""
Mobile Worker for CrowdCompute
Adapted for Android devices using Chaquopy
"""

import asyncio
import json
import uuid
import time
from datetime import datetime
from typing import List, Optional, Dict, Any
from dataclasses import dataclass, asdict

# Mobile-specific imports
try:
    from android import mActivity
    from android.storage import primary_external_storage_path
    MOBILE_AVAILABLE = True
except ImportError:
    MOBILE_AVAILABLE = False
    print("Warning: Android-specific features not available")

from common.serializer import execute_function, get_runtime_info
from common.protocol import Message, MessageType


@dataclass
class WorkerConfig:
    """Worker configuration for mobile devices"""
    worker_id: str
    foreman_url: str = "ws://192.168.8.101:9000"
    max_concurrent_tasks: int = 1
    auto_restart: bool = True
    heartbeat_interval: int = 30
    mobile_device_id: Optional[str] = None
    battery_threshold: int = 20  # Minimum battery percentage to continue working


@dataclass
class TaskResult:
    """Task execution result"""
    task_id: str
    result: Optional[str] = None
    error: Optional[str] = None
    execution_time: float = 0.0


class MobileWorker:
    """Mobile-optimized worker for CrowdCompute"""
    
    def __init__(self, config: WorkerConfig):
        self.config = config
        self.websocket = None
        self.is_connected = False
        self.current_task: Optional[Dict[str, Any]] = None
        
        # Initialize event loop for Android background threads
        try:
            self.loop = asyncio.get_event_loop()
        except RuntimeError:
            # No event loop in current thread, create a new one
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
        
        # Create queue in the event loop
        self.task_queue = asyncio.Queue(loop=self.loop)
        
        self.stats = {
            "tasks_completed": 0,
            "tasks_failed": 0,
            "total_execution_time": 0.0,
            "started_at": datetime.now(),
            "mobile_device_id": config.mobile_device_id or self._get_device_id(),
            "platform": "android"
        }
        
        # Message tracking
        self.last_message_time = None
        self.messages_received_count = 0
        
        # Mobile-specific state
        self.battery_level = 100
        self.network_available = True
        self.is_charging = False
        
        # Setup logging
        self._setup_logging()
        
        print(f"📱 Mobile Worker initialized: {config.worker_id}")
        print(f"📱 Device ID: {self.stats['mobile_device_id']}")
        print(f"📱 Platform: {self.stats['platform']}")
    
    def _get_device_id(self) -> str:
        """Get unique device identifier"""
        if MOBILE_AVAILABLE:
            try:
                # Use Android ID as device identifier
                from android.provider import Settings
                android_id = Settings.Secure.getString(
                    mActivity.getContentResolver(), 
                    Settings.Secure.ANDROID_ID
                )
                return f"android_{android_id}"
            except Exception as e:
                print(f"Warning: Could not get Android ID: {e}")
        
        # Fallback to UUID
        return f"mobile_{uuid.uuid4().hex[:8]}"
    
    def _setup_logging(self):
        """Setup mobile-appropriate logging"""
        if MOBILE_AVAILABLE:
            try:
                log_dir = f"{primary_external_storage_path()}/CrowdCompute/logs"
                import os
                os.makedirs(log_dir, exist_ok=True)
                self.log_file = f"{log_dir}/worker_{self.config.worker_id}.log"
            except Exception as e:
                print(f"Warning: Could not setup file logging: {e}")
                self.log_file = None
        else:
            self.log_file = None
    
    def _log(self, message: str, level: str = "INFO"):
        """Log message with mobile-appropriate handling"""
        timestamp = datetime.now().isoformat()
        log_entry = f"[{timestamp}] {level}: {message}"
        
        print(log_entry)
        
        if self.log_file:
            try:
                with open(self.log_file, 'a', encoding='utf-8') as f:
                    f.write(log_entry + '\n')
            except Exception as e:
                print(f"Warning: Could not write to log file: {e}")
    
    async def _check_mobile_conditions(self) -> bool:
        """Check if mobile device can continue working"""
        if not MOBILE_AVAILABLE:
            return True
        
        try:
            # Check battery level
            from android.content import IntentFilter, Intent
            from android.content.pm import PackageManager
            
            battery_status = mActivity.registerReceiver(
                None, 
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            
            if battery_status:
                level = battery_status.getIntExtra("level", -1)
                scale = battery_status.getIntExtra("scale", -1)
                status = battery_status.getIntExtra("status", -1)
                temperature = battery_status.getIntExtra("temperature", -1)
                voltage = battery_status.getIntExtra("voltage", -1)
                
                if level != -1 and scale != -1:
                    self.battery_level = (level * 100) // scale
                
                # Check if charging
                self.is_charging = status in [2, 5]  # CHARGING or FULL
                
                # Log detailed battery info
                self._log(f"🔋 Battery: {self.battery_level}% (Level: {level}/{scale})")
                self._log(f"🔌 Charging: {self.is_charging} (Status: {status})")
                if temperature != -1:
                    self._log(f"🌡️ Battery temp: {temperature/10}°C")
                if voltage != -1:
                    self._log(f"⚡ Battery voltage: {voltage}mV")
                
                # Stop if battery too low and not charging
                if self.battery_level < self.config.battery_threshold and not self.is_charging:
                    self._log(f"⚠️ Battery too low ({self.battery_level}%) and not charging, pausing work", "WARNING")
                    return False
            
            # Check network connectivity
            from android.net import ConnectivityManager
            from android.content import Context
            
            cm = mActivity.getSystemService(Context.CONNECTIVITY_SERVICE)
            if cm:
                active_network = cm.getActiveNetworkInfo()
                self.network_available = active_network and active_network.isConnected()
                
                if active_network:
                    network_type = active_network.getTypeName()
                    network_subtype = active_network.getSubtypeName()
                    self._log(f"📶 Network: {network_type} ({network_subtype}) - Connected: {self.network_available}")
                else:
                    self._log("📶 Network: No active network")
                
                if not self.network_available:
                    self._log("⚠️ Network not available, pausing work", "WARNING")
                    return False
            
            return True
            
        except Exception as e:
            self._log(f"❌ Error checking mobile conditions: {e}", "ERROR")
            return True  # Continue if we can't check
    
    async def connect(self):
        """Connect to the foreman using mobile-optimized WebSocket"""
        try:
            self._log(f"🔌 Connecting to foreman at {self.config.foreman_url}...")
            
            # Use Android WebSocket client
            if MOBILE_AVAILABLE:
                from okhttp3 import OkHttpClient, Request
                from okhttp3.ws import WebSocket, WebSocketListener
                from okhttp3 import Response
                
                client = OkHttpClient()
                request = Request.Builder().url(self.config.foreman_url).build()
                
                # Create WebSocket connection
                self.websocket = client.newWebSocket(request, self._create_websocket_listener())
                self.is_connected = True
                
                # Wait a moment for connection to establish
                await asyncio.sleep(1)
                
            else:
                # Fallback for testing
                import websockets
                self.websocket = await websockets.connect(self.config.foreman_url)
                self.is_connected = True
            
            self._log(f"✅ Connected to foreman as worker {self.config.worker_id}")
            
            # Send worker ready message
            ready_message = Message(
                msg_type=MessageType.WORKER_READY,
                data={
                    "worker_id": self.config.worker_id,
                    "mobile_device_id": self.stats["mobile_device_id"],
                    "platform": self.stats["platform"],
                    "battery_level": self.battery_level,
                    "is_charging": self.is_charging
                }
            )
            await self._send_message(ready_message)
            
            # Send a test message to verify connection
            test_message = Message(
                msg_type=MessageType.PING,
                data={
                    "worker_id": self.config.worker_id,
                    "test": "connection_verification"
                }
            )
            await self._send_message(test_message)
            
            return True
            
        except Exception as e:
            self._log(f"❌ Failed to connect to foreman: {e}", "ERROR")
            self.is_connected = False
            return False
    
    def _create_websocket_listener(self):
        """Create WebSocket listener for Android"""
        if not MOBILE_AVAILABLE:
            return None
        
        class MobileWebSocketListener:
            def __init__(self, worker):
                self.worker = worker
            
            def onOpen(self, webSocket, response):
                self.worker._log("WebSocket connection opened")
            
            def onMessage(self, webSocket, text):
                self.worker._log(f"📨 WebSocket message received: {text[:100]}...")
                # Use the worker's event loop to handle messages
                if self.worker.loop and not self.worker.loop.is_closed():
                    self.worker._log("🔄 Scheduling message handling in event loop")
                    self.worker.loop.call_soon_threadsafe(
                        lambda: asyncio.create_task(
                            self.worker._handle_websocket_message(text)
                        )
                    )
                else:
                    self.worker._log("Warning: Event loop not available for message handling", "WARNING")
            
            def onClosing(self, webSocket, code, reason):
                self.worker._log("WebSocket connection closing")
            
            def onFailure(self, webSocket, t, response):
                self.worker._log(f"WebSocket connection failed: {t}", "ERROR")
                self.worker.is_connected = False
        
        return MobileWebSocketListener(self)
    
    async def _handle_websocket_message(self, message_data: str):
        """Handle incoming WebSocket message"""
        try:
            # Track message
            self.last_message_time = datetime.now()
            self.messages_received_count += 1
            
            self._log(f"📥 Received WebSocket message: {message_data[:100]}...")
            message = Message.from_json(message_data)
            self._log(f"📋 Parsed message type: {message.type}")
            
            # Log more details for task assignments
            if message.type == MessageType.ASSIGN_TASK:
                self._log(f"🎯 TASK ASSIGNMENT RECEIVED!")
                self._log(f"📋 Task ID: {message.data.get('task_id', 'Unknown')}")
                self._log(f"📋 Job ID: {message.job_id}")
                self._log(f"📋 Function code length: {len(message.data.get('func_code', ''))}")
                self._log(f"📋 Task args: {message.data.get('task_args', 'None')}")
            
            await self.handle_message(message)
        except Exception as e:
            self._log(f"Error handling WebSocket message: {e}", "ERROR")
            self._log(f"Message data: {message_data}", "ERROR")
    
    async def _send_message(self, message: Message):
        """Send message through WebSocket"""
        try:
            if self.websocket:
                if MOBILE_AVAILABLE:
                    # Android WebSocket - send synchronously
                    self.websocket.send(message.to_json())
                    self._log(f"📤 Sent message: {message.type}")
                else:
                    # Standard WebSocket
                    await self.websocket.send(message.to_json())
        except Exception as e:
            self._log(f"Error sending message: {e}", "ERROR")
    
    async def disconnect(self):
        """Disconnect from the foreman"""
        if self.websocket:
            if MOBILE_AVAILABLE:
                self.websocket.close(1000, "Worker shutting down")
            else:
                await self.websocket.close()
            self.websocket = None
        self.is_connected = False
        self._log("🔌 Disconnected from foreman")
    
    def stop(self):
        """Stop the worker gracefully"""
        self._log("🛑 Stopping worker gracefully...")
        self.is_connected = False
        
        # Cancel the event loop if it's running
        if self.loop and self.loop.is_running():
            self.loop.call_soon_threadsafe(self.loop.stop)
    
    async def handle_message(self, message: Message):
        """Handle incoming messages from foreman"""
        try:
            if message.type == MessageType.ASSIGN_TASK:
                await self._handle_task_assignment(message)
            elif message.type == MessageType.PING:
                # Respond to ping
                pong_message = Message(
                    msg_type=MessageType.PONG,
                    data={
                        "worker_id": self.config.worker_id,
                        "battery_level": self.battery_level,
                        "is_charging": self.is_charging
                    }
                )
                await self._send_message(pong_message)
            else:
                self._log(f"Unknown message type: {message.type}")
                
        except Exception as e:
            self._log(f"❌ Error handling message: {e}", "ERROR")
    
    async def _handle_task_assignment(self, message: Message):
        """Handle a task assignment from foreman"""
        try:
            task_id = message.data["task_id"]
            job_id = message.job_id
            func_code = message.data["func_code"]
            task_args = message.data["task_args"]
            
            self._log(f"📋 Received task {task_id} for job {job_id} | worker_runtime={get_runtime_info()}")
            self._log(f"🔧 Function code length: {len(func_code)} characters")
            self._log(f"📦 Task args: {task_args}")
            
            # Check mobile conditions before executing
            if not await self._check_mobile_conditions():
                self._log("⚠️ Mobile conditions not suitable, rejecting task", "WARNING")
                # Send task back with mobile condition error
                error_message = Message(
                    msg_type=MessageType.TASK_ERROR,
                    data={
                        "error": "Mobile device conditions not suitable (low battery/network)",
                        "task_id": task_id,
                        "mobile_device_id": self.stats["mobile_device_id"]
                    },
                    job_id=job_id
                )
                await self._send_message(error_message)
                return
            
            # Set current task
            self.current_task = {
                "task_id": task_id,
                "job_id": job_id
            }
            
            # Execute the task - client provides the function and arguments
            result = await self._execute_task(func_code, task_args)
            
            # Send result back
            result_message = Message(
                msg_type=MessageType.TASK_RESULT,
                data={
                    "result": result,
                    "task_id": task_id,
                    "mobile_device_id": self.stats["mobile_device_id"]
                },
                job_id=job_id
            )
            await self._send_message(result_message)
            
            self._log(f"✅ Completed task {task_id}")
            
            # Clear current task
            self.current_task = None
            
        except Exception as e:
            self._log(f"❌ Error executing task {task_id}: {e}", "ERROR")
            
            # Send error back
            error_message = Message(
                msg_type=MessageType.TASK_ERROR,
                data={
                    "error": str(e),
                    "task_id": task_id,
                    "mobile_device_id": self.stats["mobile_device_id"]
                },
                job_id=job_id
            )
            await self._send_message(error_message)
            
            # Clear current task
            self.current_task = None
    
    async def _execute_task(self, func_code: str, task_args: Any) -> Any:
        """Execute a task in a safe environment with mobile optimizations"""
        start_time = datetime.now()
        
        try:
            self._log(f"🔄 Starting task execution...")
            self._log(f"📱 Device specs: Battery={self.battery_level}%, Charging={self.is_charging}, Network={self.network_available}")
            self._log(f"🔧 Runtime info: {get_runtime_info()}")
            self._log(f"📦 Task args type: {type(task_args)}, Value: {task_args}")
            
            # Execute the function directly - client provides the function
            self._log(f"⚡ Executing function from client...")
            self._log(f"🔧 Function code preview: {func_code[:200]}...")
            
            result = execute_function(func_code, task_args)
            
            execution_time = (datetime.now() - start_time).total_seconds()
            
            self._log(f"✅ Task completed successfully in {execution_time:.2f}s")
            self._log(f"📊 Result type: {type(result)}, Value: {result}")
            
            # Update stats
            self.stats["tasks_completed"] += 1
            self.stats["total_execution_time"] += execution_time
            
            return result
            
        except Exception as e:
            execution_time = (datetime.now() - start_time).total_seconds()
            error_msg = f"Task execution failed: {e}"
            
            self._log(f"❌ Task failed after {execution_time:.2f}s", "ERROR")
            self._log(f"🚨 Error details: {str(e)}", "ERROR")
            self._log(f"🔍 Error type: {type(e).__name__}", "ERROR")
            
            # Update stats
            self.stats["tasks_failed"] += 1
            self.stats["total_execution_time"] += execution_time
            
            raise Exception(error_msg)
    
    async def listen_for_tasks(self):
        """Listen for tasks from foreman (mobile-optimized)"""
        while self.is_connected:
            try:
                # Check mobile conditions periodically
                if not await self._check_mobile_conditions():
                    await asyncio.sleep(30)  # Wait longer when conditions are poor
                    continue
                
                # For Android WebSocket, messages are handled by the listener
                if not MOBILE_AVAILABLE:
                    # Fallback for testing
                    if not self.websocket:
                        break
                    
                    message_data = await self.websocket.recv()
                    message = Message.from_json(message_data)
                    await self.handle_message(message)
                
                await asyncio.sleep(1)
                
            except Exception as e:
                self._log(f"❌ Error in task listener: {e}", "ERROR")
                await asyncio.sleep(5)
    
    async def heartbeat(self):
        """Send periodic heartbeat to foreman with mobile status"""
        while self.is_connected:
            try:
                # Update mobile conditions
                await self._check_mobile_conditions()
                
                if self.websocket:
                    # Send heartbeat with mobile info
                    heartbeat_message = Message(
                        msg_type=MessageType.WORKER_HEARTBEAT,
                        data={
                            "worker_id": self.config.worker_id,
                            "status": "online",
                            "current_task": self.current_task["task_id"] if self.current_task else None,
                            "mobile_device_id": self.stats["mobile_device_id"],
                            "battery_level": self.battery_level,
                            "is_charging": self.is_charging,
                            "network_available": self.network_available
                        }
                    )
                    await self._send_message(heartbeat_message)
                
                await asyncio.sleep(self.config.heartbeat_interval)
                
            except Exception as e:
                self._log(f"❌ Error sending heartbeat: {e}", "ERROR")
                break
    
    async def start(self):
        """Start the mobile worker"""
        self._log(f"🚀 Starting Mobile Worker: {self.config.worker_id}")
        
        # Connect to foreman
        if not await self.connect():
            if self.config.auto_restart:
                self._log("🔄 Auto-restart enabled, retrying connection...")
                await asyncio.sleep(5)
                await self.start()
            return
        
        # Start background tasks
        task_listener = asyncio.create_task(self.listen_for_tasks())
        heartbeat_task = asyncio.create_task(self.heartbeat())
        
        try:
            # Keep worker running
            await asyncio.gather(task_listener, heartbeat_task)
        except KeyboardInterrupt:
            self._log("🛑 Worker stopped by user")
        except asyncio.CancelledError:
            self._log("🛑 Worker tasks cancelled")
        except Exception as e:
            self._log(f"❌ Worker error: {e}", "ERROR")
        finally:
            # Cancel background tasks
            task_listener.cancel()
            heartbeat_task.cancel()
            await self.disconnect()
    
    def start_sync(self):
        """Start the mobile worker synchronously (for Android service)"""
        self._log(f"🚀 Starting Mobile Worker synchronously: {self.config.worker_id}")
        
        try:
            # Run the async start method in the event loop
            self.loop.run_until_complete(self.start())
        except KeyboardInterrupt:
            self._log("🛑 Worker interrupted by user")
        except Exception as e:
            self._log(f"❌ Worker start error: {e}", "ERROR")
            raise e
        finally:
            # Clean up the event loop
            try:
                self.loop.close()
            except Exception as e:
                self._log(f"Warning: Error closing event loop: {e}")
    
    def get_status(self) -> Dict[str, Any]:
        """Get current worker status for mobile UI"""
        return {
            "worker_id": self.config.worker_id,
            "status": "online" if self.is_connected else "offline",
            "current_task": self.current_task,
            "connection_info": {
                "websocket_connected": self.websocket is not None,
                "foreman_url": self.config.foreman_url,
                "last_message_received": getattr(self, 'last_message_time', None),
                "messages_received": getattr(self, 'messages_received_count', 0)
            },
            "stats": {
                "tasks_completed": self.stats["tasks_completed"],
                "tasks_failed": self.stats["tasks_failed"],
                "total_execution_time": self.stats["total_execution_time"],
                "started_at": self.stats["started_at"].isoformat(),
                "uptime_seconds": (datetime.now() - self.stats["started_at"]).total_seconds()
            },
            "mobile_info": {
                "device_id": self.stats["mobile_device_id"],
                "platform": self.stats["platform"],
                "battery_level": self.battery_level,
                "is_charging": self.is_charging,
                "network_available": self.network_available,
                "runtime_info": get_runtime_info()
            },
            "config": asdict(self.config)
        }
    
    def get_detailed_device_info(self) -> Dict[str, Any]:
        """Get detailed device information for debugging"""
        device_info = {
            "device_id": self.stats["mobile_device_id"],
            "platform": self.stats["platform"],
            "battery": {
                "level": self.battery_level,
                "is_charging": self.is_charging,
                "threshold": self.config.battery_threshold
            },
            "network": {
                "available": self.network_available
            },
            "runtime": get_runtime_info(),
            "worker_stats": {
                "tasks_completed": self.stats["tasks_completed"],
                "tasks_failed": self.stats["tasks_failed"],
                "total_execution_time": self.stats["total_execution_time"],
                "uptime_seconds": (datetime.now() - self.stats["started_at"]).total_seconds()
            }
        }
        
        # Add Android-specific info if available
        if MOBILE_AVAILABLE:
            try:
                from android.os import Build
                device_info["android"] = {
                    "model": Build.MODEL,
                    "manufacturer": Build.MANUFACTURER,
                    "version": Build.VERSION.RELEASE,
                    "sdk": Build.VERSION.SDK_INT
                }
            except Exception as e:
                device_info["android"] = {"error": str(e)}
        
        return device_info
    
    def test_task_execution(self) -> Dict[str, Any]:
        """Test task execution functionality"""
        try:
            self._log("🧪 Testing task execution...")
            
            # Test function
            test_func_code = '''
def test_mobile_task(data):
    return [x * 2 for x in data]
'''
            test_data = [1, 2, 3, 4, 5]
            
            # Execute test task
            result = execute_function(test_func_code, test_data)
            
            self._log(f"✅ Test task executed successfully: {result}")
            
            return {
                "success": True,
                "result": result,
                "test_data": test_data,
                "worker_id": self.config.worker_id,
                "is_connected": self.is_connected
            }
            
        except Exception as e:
            self._log(f"❌ Test task execution failed: {e}", "ERROR")
            return {
                "success": False,
                "error": str(e),
                "worker_id": self.config.worker_id,
                "is_connected": self.is_connected
            }


# Factory function for easy creation
def create_mobile_worker(worker_id: str, foreman_url: str = "ws://192.168.8.101:9000") -> MobileWorker:
    """Create a mobile worker with default configuration"""
    try:
        config = WorkerConfig(
            worker_id=worker_id,
            foreman_url=foreman_url
        )
        return MobileWorker(config)
    except Exception as e:
        print(f"Error creating mobile worker: {e}")
        raise e


def test_mobile_worker():
    """Test function to verify mobile worker functionality"""
    try:
        print("🧪 Testing mobile worker functionality...")
        
        # Test basic function execution
        test_func_code = '''
def test_function(x):
    return x * 2
'''
        result = execute_function(test_func_code, 5)
        print(f"✅ Test function result: {result}")
        
        # Test message creation
        test_message = Message(
            msg_type=MessageType.WORKER_READY,
            data={"test": "message_creation"}
        )
        print(f"✅ Test message created: {test_message.type.value}")
        
        # Test task execution simulation
        print("🧪 Testing task execution simulation...")
        test_task_func = '''
def process_data(data):
    return [x * 2 for x in data]
'''
        test_data = [1, 2, 3, 4, 5]
        task_result = execute_function(test_task_func, test_data)
        print(f"✅ Task execution result: {task_result}")
        
        print("✅ Mobile worker test completed successfully")
        return True
        
    except Exception as e:
        print(f"❌ Mobile worker test failed: {e}")
        import traceback
        traceback.print_exc()
        return False
