"""
Unit tests for embedding service
"""
import pytest
import sys
import os

# Add app to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from app.embedder import Embedder
from app.config import config


class TestEmbedder:
    """Tests for Embedder class"""
    
    @pytest.fixture(scope="class")
    def embedder(self):
        """Create and load embedder once for all tests"""
        e = Embedder()
        e.load()
        return e
    
    def test_load_model(self, embedder):
        """Test that model loads successfully"""
        assert embedder.is_loaded()
    
    def test_embed_single_returns_vector(self, embedder):
        """Test single text embedding returns correct dimension"""
        text = "Привет, как дела?"
        embedding = embedder.embed_single(text)
        
        assert isinstance(embedding, list)
        assert len(embedding) == config.EMBEDDING_DIM  # 384 for e5-small
        assert all(isinstance(x, float) for x in embedding)
    
    def test_embed_batch_returns_vectors(self, embedder):
        """Test batch embedding returns correct number of vectors"""
        texts = [
            "Первый текст",
            "Второй текст",
            "Третий текст"
        ]
        embeddings = embedder.embed_batch(texts)
        
        assert len(embeddings) == 3
        for emb in embeddings:
            assert len(emb) == config.EMBEDDING_DIM
    
    def test_embed_empty_list_returns_empty(self, embedder):
        """Test empty batch returns empty list"""
        embeddings = embedder.embed_batch([])
        assert embeddings == []
    
    def test_cache_works(self, embedder):
        """Test that caching prevents recomputation"""
        text = "Тестовый текст для кэша"
        
        # Clear cache first
        embedder.clear_cache()
        initial_cache_size = len(embedder.cache)
        
        # First call - should compute
        emb1 = embedder.embed_single(text)
        assert len(embedder.cache) == initial_cache_size + 1
        
        # Second call - should use cache
        emb2 = embedder.embed_single(text)
        assert len(embedder.cache) == initial_cache_size + 1  # Same size
        
        # Results should be identical
        assert emb1 == emb2
    
    def test_similar_texts_have_similar_embeddings(self, embedder):
        """Test that semantically similar texts have close embeddings"""
        text1 = "Мой любимый цвет синий"
        text2 = "Я люблю синий цвет"
        text3 = "Погода сегодня хорошая"
        
        emb1 = embedder.embed_single(text1)
        emb2 = embedder.embed_single(text2)
        emb3 = embedder.embed_single(text3)
        
        # Cosine similarity helper
        def cosine_sim(a, b):
            dot = sum(x * y for x, y in zip(a, b))
            norm_a = sum(x ** 2 for x in a) ** 0.5
            norm_b = sum(x ** 2 for x in b) ** 0.5
            return dot / (norm_a * norm_b)
        
        sim_12 = cosine_sim(emb1, emb2)  # Similar texts
        sim_13 = cosine_sim(emb1, emb3)  # Different texts
        
        # Similar texts should have higher similarity
        assert sim_12 > sim_13, f"Similar texts should have higher similarity: {sim_12} vs {sim_13}"
        assert sim_12 > 0.7, f"Very similar texts should have high similarity: {sim_12}"
    
    def test_stats(self, embedder):
        """Test that stats returns correct information"""
        stats = embedder.get_stats()
        
        assert "model_name" in stats
        assert "embedding_dim" in stats
        assert "cache_size" in stats
        assert "model_loaded" in stats
        assert stats["model_loaded"] is True
    
    def test_text_truncation(self, embedder):
        """Test that long texts are truncated"""
        # Create text longer than MAX_TEXT_LENGTH
        long_text = "a" * 1000  # Longer than default 512
        
        # Should not raise error
        embedding = embedder.embed_single(long_text)
        assert len(embedding) == config.EMBEDDING_DIM


if __name__ == "__main__":
    pytest.main([__file__, "-v"])



