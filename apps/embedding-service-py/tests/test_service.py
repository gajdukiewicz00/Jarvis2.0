import os
import sys

import numpy as np
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.embedder import Embedder
from app import embedder as embedder_module
from app import main as main_module


class FakeSentenceTransformer:
    def __init__(self, model_name: str):
        self.model_name = model_name

    def encode(self, inputs, convert_to_numpy=True, batch_size=None, normalize_embeddings=False):
        if isinstance(inputs, str):
            return self._vector(inputs, normalize_embeddings)
        return np.vstack([self._vector(value, normalize_embeddings) for value in inputs])

    def _vector(self, text: str, normalize_embeddings: bool):
        vector = np.zeros(384, dtype=np.float32)
        lowered = text.lower()
        vector[0] = 1.0 if lowered.startswith("query:") else 0.5
        vector[1] = 1.0 if lowered.startswith("passage:") else 0.0
        vector[2] = 1.0 if "denis" in lowered else 0.1
        vector[3] = 1.0 if "backend" in lowered else 0.1
        if normalize_embeddings:
            norm = np.linalg.norm(vector)
            if norm:
                vector = vector / norm
        return vector


@pytest.fixture
def loaded_embedder(monkeypatch):
    monkeypatch.setattr(embedder_module, "SentenceTransformer", FakeSentenceTransformer)
    service = Embedder()
    service.load()
    return service


@pytest.fixture
def client(loaded_embedder, monkeypatch):
    monkeypatch.setattr(main_module, "embedder", loaded_embedder)
    return TestClient(main_module.app)


def test_health_reports_loaded_model(client):
    response = client.get("/health")

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "healthy"
    assert payload["model_loaded"] is True
    assert payload["embedding_dim"] == 384
    assert payload["max_batch_size"] >= 1


def test_embed_single_and_batch_respect_input_type(client):
    single = client.post("/embed/single", json={"text": "Denis builds backend systems", "input_type": "query"})
    batch = client.post("/embed", json={
        "texts": ["Denis builds backend systems"],
        "input_type": "passage",
    })

    assert single.status_code == 200
    assert batch.status_code == 200
    single_payload = single.json()
    batch_payload = batch.json()
    assert single_payload["input_type"] == "query"
    assert batch_payload["input_type"] == "passage"
    assert single_payload["embedding"][0] > batch_payload["embeddings"][0][0]
    assert batch_payload["embeddings"][0][1] > single_payload["embedding"][1]


def test_invalid_payload_rejects_blank_texts(client):
    response = client.post("/embed", json={"texts": ["   "]})

    assert response.status_code == 422


def test_embedder_cache_separates_query_and_passage(loaded_embedder):
    query_vector = loaded_embedder.embed_single("Denis builds backend systems", input_type="query")
    passage_vector = loaded_embedder.embed_single("Denis builds backend systems", input_type="passage")

    assert query_vector != passage_vector
    assert len(loaded_embedder.cache) == 2


def test_batch_endpoint_handles_multiple_values(client):
    response = client.post("/embed", json={
        "texts": ["Denis builds backend systems", "Backend work in Warsaw"],
        "input_type": "passage",
    })

    assert response.status_code == 200
    payload = response.json()
    assert payload["count"] == 2
    assert len(payload["embeddings"]) == 2
