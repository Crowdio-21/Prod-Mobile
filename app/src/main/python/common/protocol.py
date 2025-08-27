"""
Protocol module for CrowdCompute mobile worker
Defines message types and communication protocol
"""

import json
import uuid
from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional


class MessageType(Enum):
    """Message types for worker-foreman communication"""
    
    # Worker messages
    WORKER_READY = "worker_ready"
    WORKER_HEARTBEAT = "worker_heartbeat"
    WORKER_BUSY = "worker_busy"
    WORKER_AVAILABLE = "worker_available"
    
    # Task messages
    ASSIGN_TASK = "assign_task"
    TASK_RESULT = "task_result"
    TASK_ERROR = "task_error"
    TASK_PROGRESS = "task_progress"
    
    # Control messages
    PING = "ping"
    PONG = "pong"
    SHUTDOWN = "shutdown"
    RESTART = "restart"
    
    # Job messages
    JOB_START = "job_start"
    JOB_COMPLETE = "job_complete"
    JOB_ERROR = "job_error"
    
    # Mobile-specific messages
    MOBILE_STATUS = "mobile_status"
    BATTERY_UPDATE = "battery_update"
    NETWORK_UPDATE = "network_update"


class Message:
    """Message class for worker-foreman communication"""
    
    def __init__(
        self,
        msg_type: MessageType,
        data: Optional[Dict[str, Any]] = None,
        job_id: Optional[str] = None,
        task_id: Optional[str] = None,
        message_id: Optional[str] = None,
        timestamp: Optional[datetime] = None
    ):
        self.type = msg_type
        self.data = data or {}
        self.job_id = job_id
        self.task_id = task_id
        self.message_id = message_id or str(uuid.uuid4())
        self.timestamp = timestamp or datetime.now()
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert message to dictionary"""
        return {
            "type": self.type.value,
            "data": self.data,
            "job_id": self.job_id,
            "task_id": self.task_id,
            "message_id": self.message_id,
            "timestamp": self.timestamp.isoformat()
        }
    
    def to_json(self) -> str:
        """Convert message to JSON string"""
        return json.dumps(self.to_dict(), default=str)
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Message':
        """Create message from dictionary"""
        return cls(
            msg_type=MessageType(data["type"]),
            data=data.get("data"),
            job_id=data.get("job_id"),
            task_id=data.get("task_id"),
            message_id=data.get("message_id"),
            timestamp=datetime.fromisoformat(data["timestamp"]) if data.get("timestamp") else None
        )
    
    @classmethod
    def from_json(cls, json_str: str) -> 'Message':
        """Create message from JSON string"""
        data = json.loads(json_str)
        return cls.from_dict(data)
    
    def __str__(self) -> str:
        return f"Message({self.type.value}, job_id={self.job_id}, task_id={self.task_id})"
    
    def __repr__(self) -> str:
        return self.__str__()


class TaskMessage(Message):
    """Specialized message for task-related communication"""
    
    def __init__(
        self,
        task_id: str,
        job_id: str,
        func_code: str,
        task_args: list,
        **kwargs
    ):
        super().__init__(
            msg_type=MessageType.ASSIGN_TASK,
            data={
                "func_code": func_code,
                "task_args": task_args
            },
            job_id=job_id,
            task_id=task_id,
            **kwargs
        )


class ResultMessage(Message):
    """Specialized message for task results"""
    
    def __init__(
        self,
        task_id: str,
        job_id: str,
        result: Any,
        execution_time: float = 0.0,
        **kwargs
    ):
        super().__init__(
            msg_type=MessageType.TASK_RESULT,
            data={
                "result": result,
                "execution_time": execution_time
            },
            job_id=job_id,
            task_id=task_id,
            **kwargs
        )


class ErrorMessage(Message):
    """Specialized message for errors"""
    
    def __init__(
        self,
        task_id: str,
        job_id: str,
        error: str,
        error_type: str = "execution_error",
        **kwargs
    ):
        super().__init__(
            msg_type=MessageType.TASK_ERROR,
            data={
                "error": error,
                "error_type": error_type
            },
            job_id=job_id,
            task_id=task_id,
            **kwargs
        )


class MobileStatusMessage(Message):
    """Specialized message for mobile device status"""
    
    def __init__(
        self,
        worker_id: str,
        battery_level: int,
        is_charging: bool,
        network_available: bool,
        device_id: str,
        **kwargs
    ):
        super().__init__(
            msg_type=MessageType.MOBILE_STATUS,
            data={
                "worker_id": worker_id,
                "battery_level": battery_level,
                "is_charging": is_charging,
                "network_available": network_available,
                "device_id": device_id,
                "platform": "android"
            },
            **kwargs
        )


class HeartbeatMessage(Message):
    """Specialized message for worker heartbeat"""
    
    def __init__(
        self,
        worker_id: str,
        status: str = "online",
        current_task: Optional[str] = None,
        **kwargs
    ):
        super().__init__(
            msg_type=MessageType.WORKER_HEARTBEAT,
            data={
                "worker_id": worker_id,
                "status": status,
                "current_task": current_task
            },
            **kwargs
        )


# Message factory functions
def create_worker_ready_message(worker_id: str, mobile_info: Optional[Dict[str, Any]] = None) -> Message:
    """Create a worker ready message"""
    data = {"worker_id": worker_id}
    if mobile_info:
        data.update(mobile_info)
    
    return Message(
        msg_type=MessageType.WORKER_READY,
        data=data
    )


def create_ping_message(worker_id: str) -> Message:
    """Create a ping message"""
    return Message(
        msg_type=MessageType.PING,
        data={"worker_id": worker_id}
    )


def create_pong_message(worker_id: str) -> Message:
    """Create a pong message"""
    return Message(
        msg_type=MessageType.PONG,
        data={"worker_id": worker_id}
    )


def create_task_result_message(task_id: str, job_id: str, result: Any, mobile_device_id: Optional[str] = None) -> Message:
    """Create a task result message"""
    data = {"result": result, "task_id": task_id}
    if mobile_device_id:
        data["mobile_device_id"] = mobile_device_id
    
    return Message(
        msg_type=MessageType.TASK_RESULT,
        data=data,
        job_id=job_id
    )


def create_task_error_message(task_id: str, job_id: str, error: str, mobile_device_id: Optional[str] = None) -> Message:
    """Create a task error message"""
    data = {"error": error, "task_id": task_id}
    if mobile_device_id:
        data["mobile_device_id"] = mobile_device_id
    
    return Message(
        msg_type=MessageType.TASK_ERROR,
        data=data,
        job_id=job_id
    )
