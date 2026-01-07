#!/usr/bin/env python3
"""
Sentiment Analysis Worker using Available Mobile Libraries
Uses TextBlob instead of PyTorch for mobile compatibility
"""

import json
import time
import os


def sentiment_worker_pytorch(text):
    """
    Sentiment analysis worker using TextBlob
    Mobile-optimized alternative to PyTorch
    """
    import json
    import time
    
    start = time.time()
    
    try:
        from textblob import TextBlob
        
        # Analyze sentiment using TextBlob
        blob = TextBlob(text)
        polarity = blob.sentiment.polarity  # -1.0 to 1.0
        subjectivity = blob.sentiment.subjectivity  # 0.0 to 1.0
        
        # Convert polarity to class
        predicted_class = 1 if polarity > 0 else 0
        
        # Calculate confidence based on polarity strength
        confidence = abs(polarity)
        
        latency_ms = int((time.time() - start) * 1000)
        
        result = {
            "text": text[:50] + "..." if len(text) > 50 else text,
            "sentiment": round(polarity, 3),
            "confidence": round(confidence, 3),
            "predicted_class": predicted_class,
            "class_name": "positive" if predicted_class == 1 else "negative",
            "neg_probability": round(max(0, -polarity), 3),
            "pos_probability": round(max(0, polarity), 3),
            "subjectivity": round(subjectivity, 3),
            "model": "TextBlob",
            "latency_ms": latency_ms,
            "status": "success"
        }
        
        print(f"[Worker] Success: {result['class_name'].upper()} | Polarity: {polarity:.3f}")
        return json.dumps(result)
    
    except Exception as e:
        import traceback
        traceback.print_exc()
        
        latency_ms = int((time.time() - start) * 1000)
        
        return json.dumps({
            "text": text[:50] + "..." if len(text) > 50 else text,
            "sentiment": 0.0,
            "confidence": 0.0,
            "latency_ms": latency_ms,
            "status": "error",
            "error": str(e),
            "model": "TextBlob"
        })
