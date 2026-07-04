"""
Embedding generator using sentence-transformers
"""
import hashlib
import logging
from threading import RLock
from typing import List, Optional

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
        self._lock = RLock()
        self.embedding_dim = config.EMBEDDING_DIM
    
    def load(self) -> None:
        """Load the embedding model"""
        with self._lock:
            if self.is_loaded():
                return

            logger.info("Loading embedding model: %s", config.MODEL_NAME)

            try:
                self.model = SentenceTransformer(config.MODEL_NAME)

                test_embedding = self.model.encode(
                    self._prepare_text("test", "query"),
                    convert_to_numpy=True,
                    normalize_embeddings=True,
                )
                actual_dim = len(test_embedding)
                self.embedding_dim = actual_dim

                if actual_dim != config.EMBEDDING_DIM:
                    logger.warning(
                        "Embedding dimension mismatch. Expected %s, got %s.",
                        config.EMBEDDING_DIM,
                        actual_dim,
                    )

                self._model_loaded = True
                logger.info("Model loaded successfully. Embedding dimension: %s", actual_dim)

            except Exception as exc:
                logger.error("Failed to load model: %s", exc)
                raise
    
    def is_loaded(self) -> bool:
        """Check if model is loaded"""
        return self._model_loaded and self.model is not None
    
    def _get_cache_key(self, text: str, input_type: str) -> str:
        """Generate cache key for text"""
        return f"{input_type}:{hashlib.sha256(text.encode('utf-8')).hexdigest()}"
    
    def _truncate_text(self, text: str) -> str:
        """Truncate text to max length"""
        if len(text) > config.MAX_TEXT_LENGTH:
            return text[:config.MAX_TEXT_LENGTH]
        return text

    def _prepare_text(self, text: str, input_type: str) -> str:
        normalized_text = text.strip()
        if input_type == "query":
            return f"query: {normalized_text}"
        if input_type == "passage":
            return f"passage: {normalized_text}"
        raise ValueError(f"Unsupported input_type: {input_type}")

    def embed_single(self, text: str, input_type: str = "query") -> List[float]:
        """
        Generate embedding for a single text.
        
        Args:
            text: Input text
            
        Returns:
            List of floats (embedding vector)
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        text = self._truncate_text(text.strip())
        cache_key = self._get_cache_key(text, input_type)
        cached = self.cache.get(cache_key)
        if cached is not None:
            logger.debug("Cache hit for %s text: %s...", input_type, text[:50])
            return cached

        embedding = self.model.encode(
            self._prepare_text(text, input_type),
            convert_to_numpy=True,
            normalize_embeddings=True,
        ).tolist()

        with self._lock:
            self.cache[cache_key] = embedding
        
        return embedding
    
    def embed_batch(self, texts: List[str], input_type: str = "query") -> List[List[float]]:
        """
        Generate embeddings for multiple texts.
        
        Args:
            texts: List of input texts
            
        Returns:
            List of embedding vectors
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")

        if not texts:
            return []

        results = []
        uncached_texts = []
        uncached_indices = []
        
        for i, text in enumerate(texts):
            text = self._truncate_text(text.strip())
            cache_key = self._get_cache_key(text, input_type)
            
            if cache_key in self.cache:
                results.append((i, self.cache[cache_key]))
            else:
                uncached_texts.append(self._prepare_text(text, input_type))
                uncached_indices.append(i)
        
        if uncached_texts:
            embeddings = self.model.encode(
                uncached_texts,
                convert_to_numpy=True,
                batch_size=min(len(uncached_texts), config.MAX_BATCH_SIZE),
                normalize_embeddings=True,
            )
            
            for idx, embedding in zip(uncached_indices, embeddings):
                embedding_list = embedding.tolist()
                
                original_text = self._truncate_text(texts[idx].strip())
                cache_key = self._get_cache_key(original_text, input_type)
                self.cache[cache_key] = embedding_list
                
                results.append((idx, embedding_list))
        
        results.sort(key=lambda x: x[0])
        return [r[1] for r in results]
    
    def get_stats(self) -> dict:
        """Get cache and model statistics"""
        return {
            "model_name": config.MODEL_NAME,
            "embedding_dim": self.embedding_dim,
            "cache_size": len(self.cache),
            "cache_max_size": config.CACHE_SIZE,
            "model_loaded": self.is_loaded(),
            "max_batch_size": config.MAX_BATCH_SIZE,
            "max_text_length": config.MAX_TEXT_LENGTH,
            "max_request_texts": config.MAX_REQUEST_TEXTS,
        }
    
    def clear_cache(self) -> None:
        """Clear the embedding cache"""
        self.cache.clear()
        logger.info("Embedding cache cleared")


# Global embedder instance
embedder = Embedder()


