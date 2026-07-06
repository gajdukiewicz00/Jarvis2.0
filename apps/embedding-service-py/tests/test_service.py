import asyncio
import os
import sys
import time

import httpx
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


class SlowFakeSentenceTransformer(FakeSentenceTransformer):
    """Fake transformer whose encode() blocks synchronously, simulating the
    real CPU-bound SentenceTransformer.encode() call."""

    ENCODE_DELAY_SECONDS = 0.4

    def encode(self, inputs, convert_to_numpy=True, batch_size=None, normalize_embeddings=False):
        time.sleep(self.ENCODE_DELAY_SECONDS)
        return super().encode(
            inputs,
            convert_to_numpy=convert_to_numpy,
            batch_size=batch_size,
            normalize_embeddings=normalize_embeddings,
        )


def test_embed_batch_does_not_block_event_loop_for_concurrent_requests(monkeypatch):
    """Regression test for finding #36 (main.py:179): the /embed handler must
    offload the blocking SentenceTransformer.encode() call to a worker thread
    instead of calling it inline on the event loop. If it blocks the loop, a
    concurrent /health request cannot even start executing until the full
    encode duration has elapsed.
    """
    monkeypatch.setattr(embedder_module, "SentenceTransformer", SlowFakeSentenceTransformer)
    service = Embedder()
    service.load()
    monkeypatch.setattr(main_module, "embedder", service)

    async def run_concurrent_requests():
        transport = httpx.ASGITransport(app=main_module.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as async_client:
            start = time.time()

            embed_task = asyncio.create_task(
                async_client.post("/embed", json={"texts": ["Denis builds backend systems"]})
            )
            # Give the embed request a chance to start its (blocking) encode call
            # before we issue the concurrent health check.
            await asyncio.sleep(0.05)

            health_response = await async_client.get("/health")
            health_elapsed = time.time() - start

            embed_response = await embed_task
            return embed_response, health_response, health_elapsed

    embed_response, health_response, health_elapsed = asyncio.run(run_concurrent_requests())

    assert embed_response.status_code == 200
    assert health_response.status_code == 200
    # A healthy event loop answers /health well before the /embed request's
    # encode() call finishes. If /embed blocks the loop inline, /health cannot
    # complete until after the full ENCODE_DELAY_SECONDS has elapsed.
    assert health_elapsed < SlowFakeSentenceTransformer.ENCODE_DELAY_SECONDS * 0.75
