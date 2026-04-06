"""
llama.cpp backend for LLM inference (GGUF models)
Uses llama-cpp-python with GPU offload
"""
import logging
import os
import subprocess
from typing import Dict, Generator, List, Tuple

from .base import LLMBackend

logger = logging.getLogger(__name__)

# Try to import llama-cpp-python
try:
    from llama_cpp import Llama
    LLAMA_CPP_AVAILABLE = True
except ImportError:
    LLAMA_CPP_AVAILABLE = False
    Llama = None


class LlamaCppBackend(LLMBackend):
    """
    llama.cpp backend using llama-cpp-python.
    Optimized for low latency with GPU offload.
    """
    
    def __init__(
        self,
        model_path: str,
        n_gpu_layers: int = -1,  # -1 = all layers on GPU
        n_ctx: int = 4096,
        n_batch: int = 512,
        n_threads: int = 6,
        max_new_tokens: int = 512,
        verbose: bool = False,
        chat_format: str | None = None,
    ):
        if not LLAMA_CPP_AVAILABLE:
            raise ImportError(
                "llama-cpp-python is not installed. "
                "Install with: pip install llama-cpp-python --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124"
            )
        
        self.model_path = model_path
        self.n_gpu_layers = n_gpu_layers
        self.n_ctx = n_ctx
        self.n_batch = n_batch
        self.n_threads = n_threads
        self.max_new_tokens_limit = max_new_tokens
        self.verbose = verbose
        self.chat_format = chat_format

        self.model = None
        self._model_info = {}
        self.model_name = os.path.basename(self.model_path)

    def _read_training_context_length(self) -> int | None:
        if self.model is None:
            return None

        n_ctx_train = getattr(self.model, "n_ctx_train", None)
        if callable(n_ctx_train):
            try:
                return int(n_ctx_train())
            except Exception:  # pragma: no cover - best-effort fallback
                logger.debug("Failed to read n_ctx_train() from llama_cpp model", exc_info=True)

        metadata = getattr(self.model, "metadata", None) or {}
        for key in ("qwen2.context_length", "llama.context_length", "general.context_length"):
            value = metadata.get(key)
            if value is None:
                continue
            try:
                return int(value)
            except (TypeError, ValueError):
                logger.debug("Ignoring non-integer metadata value for %s: %r", key, value)

        return None

    def _read_transformer_block_count(self) -> int | None:
        metadata = getattr(self.model, "metadata", None) or {}
        for key in ("qwen2.block_count", "llama.block_count", "gemma.block_count", "general.block_count"):
            value = metadata.get(key)
            if value is None:
                continue
            try:
                return int(value)
            except (TypeError, ValueError):
                logger.debug("Ignoring non-integer block count for %s: %r", key, value)
        return None

    def _effective_n_gpu_layers(self) -> int | None:
        if self.n_gpu_layers == 0:
            return 0
        if self.n_gpu_layers > 0:
            return self.n_gpu_layers

        block_count = self._read_transformer_block_count()
        if block_count is not None:
            return block_count

        return None
    
    def load(self) -> None:
        """Load GGUF model"""
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(f"Model not found: {self.model_path}")
        
        logger.info("=" * 60)
        logger.info("LLAMA.CPP BACKEND LOADING:")
        logger.info(f"  Model path: {self.model_path}")
        logger.info(f"  n_gpu_layers: {self.n_gpu_layers} (-1 = all)")
        logger.info(f"  n_ctx: {self.n_ctx}")
        logger.info(f"  n_batch: {self.n_batch}")
        logger.info(f"  n_threads: {self.n_threads}")
        logger.info(f"  chat_format: {self.chat_format or 'auto'}")
        logger.info("=" * 60)

        try:
            llama_kwargs = {
                "model_path": self.model_path,
                "n_gpu_layers": self.n_gpu_layers,
                "n_ctx": self.n_ctx,
                "n_batch": self.n_batch,
                "n_threads": self.n_threads,
                "verbose": self.verbose,
                "use_mmap": True,
                "use_mlock": False,
            }
            if self.chat_format:
                llama_kwargs["chat_format"] = self.chat_format

            self.model = Llama(**llama_kwargs)

            training_context = self._read_training_context_length()

            # Store model metadata
            self._model_info = {
                "n_vocab": self.model.n_vocab(),
                "n_ctx_train": training_context,
                "block_count": self._read_transformer_block_count(),
                "model_size": os.path.getsize(self.model_path) / 1e9,
                "model_name": self.model_name,
            }

            logger.info(f"Model loaded successfully!")
            logger.info(f"  Vocab size: {self._model_info['n_vocab']}")
            logger.info(f"  Training context: {self._model_info['n_ctx_train']}")
            logger.info(f"  Model size: {self._model_info['model_size']:.2f} GB")
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise
    
    def is_loaded(self) -> bool:
        return self.model is not None

    def supports_chat_messages(self) -> bool:
        return True
    
    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Tuple[str, int, int]:
        """Generate text (non-streaming)"""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        effective_max = min(max_tokens, self.max_new_tokens_limit)
        
        try:
            # Tokenize to count input tokens
            input_tokens = self.model.tokenize(prompt.encode("utf-8"))
            input_length = len(input_tokens)
            
            # Generate
            output = self.model(
                prompt,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                echo=False,  # Don't include prompt in output
                stop=["</s>", "[/INST]", "<|end|>", "<|eot_id|>"],
            )
            
            generated_text = output["choices"][0]["text"].strip()
            output_tokens = output["usage"]["completion_tokens"]
            
            return generated_text, input_length, output_tokens
            
        except Exception as e:
            logger.error(f"Generation failed: {e}")
            raise

    def _count_message_tokens(self, messages: List[Dict[str, str]]) -> int:
        if not self.is_loaded():
            return 0
        prompt = "\n".join(f"{m.get('role', 'user')}: {m.get('content', '')}" for m in messages)
        return len(self.model.tokenize(prompt.encode("utf-8")))

    def chat(
        self,
        messages: List[Dict[str, str]],
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Tuple[str, int, int]:
        """Generate text directly from role-based chat messages."""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")

        effective_max = min(max_tokens, self.max_new_tokens_limit)

        try:
            output = self.model.create_chat_completion(
                messages=messages,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                stream=False,
            )

            generated_text = output["choices"][0]["message"]["content"].strip()
            usage = output.get("usage") or {}
            prompt_tokens = int(usage.get("prompt_tokens") or self._count_message_tokens(messages))
            completion_tokens = int(
                usage.get("completion_tokens")
                or len(self.model.tokenize(generated_text.encode("utf-8")))
            )

            return generated_text, prompt_tokens, completion_tokens

        except Exception as e:
            logger.error(f"Chat generation failed: {e}")
            raise
    
    def generate_stream(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Generator[str, None, None]:
        """Generate text with streaming output"""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        effective_max = min(max_tokens, self.max_new_tokens_limit)
        
        try:
            stream = self.model(
                prompt,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                echo=False,
                stop=["</s>", "[/INST]", "<|end|>", "<|eot_id|>"],
                stream=True,
            )
            
            for output in stream:
                token_text = output["choices"][0]["text"]
                if token_text:
                    yield token_text
                    
        except Exception as e:
            logger.error(f"Streaming generation failed: {e}")
            raise

    def chat_stream(
        self,
        messages: List[Dict[str, str]],
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Generator[str, None, None]:
        """Stream role-based chat completion tokens."""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")

        effective_max = min(max_tokens, self.max_new_tokens_limit)

        try:
            stream = self.model.create_chat_completion(
                messages=messages,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                stream=True,
            )

            for chunk in stream:
                delta = chunk["choices"][0].get("delta", {})
                token_text = delta.get("content")
                if token_text:
                    yield token_text

        except Exception as e:
            logger.error(f"Streaming chat generation failed: {e}")
            raise
    
    def warmup(self) -> None:
        """Run warmup inference"""
        if not self.is_loaded():
            return
        
        logger.info("Running warmup inference...")
        try:
            self.generate("Hello", max_tokens=5, temperature=0.1)
            logger.info("Warmup complete")
        except Exception as e:
            logger.warning(f"Warmup failed (non-fatal): {e}")
    
    def get_diagnostics(self) -> Dict:
        """Get backend diagnostics"""
        diag = {
            "backend": "llamacpp",
            "model_name": self.model_name,
            "model_path": self.model_path,
            "n_gpu_layers": self.n_gpu_layers,
            "configured_n_gpu_layers": self.n_gpu_layers,
            "effective_n_gpu_layers": self._effective_n_gpu_layers(),
            "n_ctx": self.n_ctx,
            "n_batch": self.n_batch,
            "n_threads": self.n_threads,
            "loaded": self.is_loaded(),
            "max_new_tokens_limit": self.max_new_tokens_limit,
        }
        
        if self._model_info:
            diag.update(self._model_info)
        
        # Try to get GPU info without requiring PyTorch.
        try:
            result = subprocess.run(
                [
                    "nvidia-smi",
                    "--query-gpu=name,memory.total,driver_version",
                    "--format=csv,noheader",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=5,
            )
            if result.returncode == 0 and result.stdout.strip():
                gpu_name, memory_total, driver_version = [
                    item.strip() for item in result.stdout.strip().split(",", maxsplit=2)
                ]
                diag["gpu_name"] = gpu_name
                diag["gpu_memory_total"] = memory_total
                diag["gpu_driver_version"] = driver_version
        except (FileNotFoundError, subprocess.SubprocessError):
            pass

        return diag
    
    def unload(self) -> None:
        """Unload model from memory"""
        if self.model is not None:
            del self.model
            self.model = None
            self._model_info = {}
        
        logger.info("Model unloaded")
