"""
Embedding generator using sentence-transformers
"""
import logging
import hashlib
from typing import List, Optional
from functools import lru_cache

from sentence_transformers import SentenceTransformer
from cachetools import LRUCache

from .config import config

logger = logging.getLogger(__name__)


class Embedder:
    """
    Generates embeddings using multilingual-e5-small.
    
    Features:
    - LRU caching for repeated texts
    - Batch processing for efficiency
    - Automatic text truncation
    """
    
    def __init__(self):
        self.model: Optional[SentenceTransformer] = None
        self.cache: LRUCache = LRUCache(maxsize=config.CACHE_SIZE)
        self._model_loaded = False
    
    def load(self) -> None:
        """Load the embedding model"""
        logger.info(f"Loading embedding model: {config.MODEL_NAME}")
        
        try:
            self.model = SentenceTransformer(config.MODEL_NAME)
            
            # Get actual embedding dimension
            test_embedding = self.model.encode("test", convert_to_numpy=True)
            actual_dim = len(test_embedding)
            
            if actual_dim != config.EMBEDDING_DIM:
                logger.warning(
                    f"Embedding dimension mismatch! Expected {config.EMBEDDING_DIM}, got {actual_dim}. "
                    f"Update EMBEDDING_DIM in config."
                )
            
            self._model_loaded = True
            logger.info(f"Model loaded successfully. Embedding dimension: {actual_dim}")
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise
    
    def is_loaded(self) -> bool:
        """Check if model is loaded"""
        return self._model_loaded and self.model is not None
    
    def _get_cache_key(self, text: str) -> str:
        """Generate cache key for text"""
        return hashlib.md5(text.encode()).hexdigest()
    
    def _truncate_text(self, text: str) -> str:
        """Truncate text to max length"""
        if len(text) > config.MAX_TEXT_LENGTH:
            return text[:config.MAX_TEXT_LENGTH]
        return text
    
    def embed_single(self, text: str) -> List[float]:
        """
        Generate embedding for a single text.
        
        Args:
            text: Input text
            
        Returns:
            List of floats (embedding vector)
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        # Truncate text
        text = self._truncate_text(text)
        
        # Check cache
        cache_key = self._get_cache_key(text)
        if cache_key in self.cache:
            logger.debug(f"Cache hit for text: {text[:50]}...")
            return self.cache[cache_key]
        
        # Generate embedding
        # e5 models expect "query: " or "passage: " prefix for best results
        prefixed_text = f"query: {text}"
        embedding = self.model.encode(prefixed_text, convert_to_numpy=True).tolist()
        
        # Cache result
        self.cache[cache_key] = embedding
        
        return embedding
    
    def embed_batch(self, texts: List[str]) -> List[List[float]]:
        """
        Generate embeddings for multiple texts.
        
        Args:
            texts: List of input texts
            
        Returns:
            List of embedding vectors
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        if len(texts) > config.MAX_BATCH_SIZE:
            logger.warning(f"Batch size {len(texts)} exceeds max {config.MAX_BATCH_SIZE}, processing in chunks")
        
        results = []
        uncached_texts = []
        uncached_indices = []
        
        # Check cache for each text
        for i, text in enumerate(texts):
            text = self._truncate_text(text)
            cache_key = self._get_cache_key(text)
            
            if cache_key in self.cache:
                results.append((i, self.cache[cache_key]))
            else:
                uncached_texts.append(f"query: {text}")
                uncached_indices.append(i)
        
        # Batch encode uncached texts
        if uncached_texts:
            embeddings = self.model.encode(
                uncached_texts,
                convert_to_numpy=True,
                batch_size=min(len(uncached_texts), config.MAX_BATCH_SIZE)
            )
            
            for idx, embedding in zip(uncached_indices, embeddings):
                embedding_list = embedding.tolist()
                
                # Cache the result
                original_text = self._truncate_text(texts[idx])
                cache_key = self._get_cache_key(original_text)
                self.cache[cache_key] = embedding_list
                
                results.append((idx, embedding_list))
        
        # Sort by original index and return embeddings only
        results.sort(key=lambda x: x[0])
        return [r[1] for r in results]
    
    def get_stats(self) -> dict:
        """Get cache and model statistics"""
        return {
            "model_name": config.MODEL_NAME,
            "embedding_dim": config.EMBEDDING_DIM,
            "cache_size": len(self.cache),
            "cache_max_size": config.CACHE_SIZE,
            "model_loaded": self.is_loaded(),
        }
    
    def clear_cache(self) -> None:
        """Clear the embedding cache"""
        self.cache.clear()
        logger.info("Embedding cache cleared")


# Global embedder instance
embedder = Embedder()



