"""
Transformers backend for LLM inference (HuggingFace models)
Supports GPU (CUDA) and CPU with optional 4-bit quantization
"""
import logging
from typing import Dict, Tuple, Optional, Generator
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM, TextIteratorStreamer
from threading import Thread

from .base import LLMBackend

logger = logging.getLogger(__name__)


class TransformersBackend(LLMBackend):
    """HuggingFace Transformers backend with GPU support"""
    
    def __init__(
        self,
        model_path: str,
        device: str = "auto",
        quantization: str = "none",  # none, 4bit, 8bit
        max_new_tokens: int = 512
    ):
        self.model_path = model_path
        self.requested_device = device
        self.quantization = quantization
        self.max_new_tokens_limit = max_new_tokens
        
        self.model = None
        self.tokenizer = None
        self.device = None
        
    def load(self) -> None:
        """Load model and tokenizer"""
        try:
            gpu_available = torch.cuda.is_available()
            
            logger.info("=" * 60)
            logger.info("TRANSFORMERS BACKEND LOADING:")
            logger.info(f"  Model path: {self.model_path}")
            logger.info(f"  torch.cuda.is_available() = {gpu_available}")
            logger.info(f"  torch.version.cuda = {torch.version.cuda}")
            logger.info(f"  Quantization: {self.quantization}")
            
            if gpu_available:
                logger.info(f"  GPU: {torch.cuda.get_device_name(0)}")
                logger.info(f"  VRAM total: {torch.cuda.get_device_properties(0).total_memory / 1e9:.2f} GB")
                self.device = "cuda"
            else:
                logger.warning("  GPU NOT AVAILABLE - using CPU (slow!)")
                self.device = "cpu"
            logger.info("=" * 60)
            
            # Load tokenizer
            logger.info("Loading tokenizer...")
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_path,
                trust_remote_code=True
            )
            if self.tokenizer.pad_token is None:
                self.tokenizer.pad_token = self.tokenizer.eos_token
            
            # Determine loading strategy
            load_kwargs = {
                "trust_remote_code": True,
                "low_cpu_mem_usage": True,
            }
            
            if self.device == "cuda":
                load_kwargs["device_map"] = "auto"
                
                if self.quantization == "4bit":
                    try:
                        from transformers import BitsAndBytesConfig
                        load_kwargs["quantization_config"] = BitsAndBytesConfig(
                            load_in_4bit=True,
                            bnb_4bit_compute_dtype=torch.float16,
                            bnb_4bit_use_double_quant=True,
                            bnb_4bit_quant_type="nf4"
                        )
                        logger.info("Using 4-bit quantization (bitsandbytes)")
                    except ImportError:
                        logger.warning("bitsandbytes not available, using FP16")
                        load_kwargs["torch_dtype"] = torch.float16
                elif self.quantization == "8bit":
                    try:
                        from transformers import BitsAndBytesConfig
                        load_kwargs["quantization_config"] = BitsAndBytesConfig(
                            load_in_8bit=True
                        )
                        logger.info("Using 8-bit quantization (bitsandbytes)")
                    except ImportError:
                        logger.warning("bitsandbytes not available, using FP16")
                        load_kwargs["torch_dtype"] = torch.float16
                else:
                    load_kwargs["torch_dtype"] = torch.float16
                    logger.info("Using FP16 precision")
            else:
                # CPU: use bfloat16 if supported
                load_kwargs["torch_dtype"] = torch.bfloat16
                logger.info("Using BF16 precision (CPU)")
            
            logger.info("Loading model... (this may take 1-3 minutes)")
            self.model = AutoModelForCausalLM.from_pretrained(
                self.model_path,
                **load_kwargs
            )
            self.model.eval()
            
            logger.info(f"Model loaded! Parameters: {self.model.num_parameters():,}")
            
            if gpu_available:
                allocated = torch.cuda.memory_allocated(0) / 1e9
                reserved = torch.cuda.memory_reserved(0) / 1e9
                logger.info(f"GPU memory - allocated: {allocated:.2f} GB, reserved: {reserved:.2f} GB")
                
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise
    
    def is_loaded(self) -> bool:
        return self.model is not None and self.tokenizer is not None
    
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
        
        # Clamp max_tokens
        effective_max = min(max_tokens, self.max_new_tokens_limit)
        
        try:
            inputs = self.tokenizer(
                prompt,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=2048
            )
            
            if self.device == "cuda":
                inputs = {k: v.to(self.device) for k, v in inputs.items()}
            
            input_length = inputs["input_ids"].shape[1]
            
            with torch.no_grad():
                outputs = self.model.generate(
                    **inputs,
                    max_new_tokens=effective_max,
                    temperature=temperature,
                    top_p=top_p,
                    do_sample=True,
                    pad_token_id=self.tokenizer.pad_token_id,
                    eos_token_id=self.tokenizer.eos_token_id
                )
            
            generated_text = self.tokenizer.decode(
                outputs[0][input_length:],
                skip_special_tokens=True
            )
            
            output_length = outputs[0].shape[0] - input_length
            
            return generated_text.strip(), input_length, output_length
            
        except Exception as e:
            logger.error(f"Generation failed: {e}")
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
            inputs = self.tokenizer(
                prompt,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=2048
            )
            
            if self.device == "cuda":
                inputs = {k: v.to(self.device) for k, v in inputs.items()}
            
            # Create streamer
            streamer = TextIteratorStreamer(
                self.tokenizer,
                skip_prompt=True,
                skip_special_tokens=True
            )
            
            # Generate in background thread
            generation_kwargs = {
                **inputs,
                "max_new_tokens": effective_max,
                "temperature": temperature,
                "top_p": top_p,
                "do_sample": True,
                "pad_token_id": self.tokenizer.pad_token_id,
                "eos_token_id": self.tokenizer.eos_token_id,
                "streamer": streamer,
            }
            
            thread = Thread(target=self.model.generate, kwargs=generation_kwargs)
            thread.start()
            
            # Yield tokens as they come
            for text in streamer:
                yield text
            
            thread.join()
            
        except Exception as e:
            logger.error(f"Streaming generation failed: {e}")
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
            "backend": "transformers",
            "model_path": self.model_path,
            "device": self.device,
            "quantization": self.quantization,
            "loaded": self.is_loaded(),
            "max_new_tokens_limit": self.max_new_tokens_limit,
        }
        
        if torch.cuda.is_available():
            diag["gpu_name"] = torch.cuda.get_device_name(0)
            diag["gpu_memory_allocated_gb"] = round(torch.cuda.memory_allocated(0) / 1e9, 2)
            diag["gpu_memory_reserved_gb"] = round(torch.cuda.memory_reserved(0) / 1e9, 2)
            diag["gpu_memory_total_gb"] = round(torch.cuda.get_device_properties(0).total_memory / 1e9, 2)
            diag["cuda_version"] = torch.version.cuda
        
        if self.is_loaded():
            diag["model_parameters"] = self.model.num_parameters()
        
        return diag
    
    def unload(self) -> None:
        """Unload model from memory"""
        if self.model is not None:
            del self.model
            self.model = None
        if self.tokenizer is not None:
            del self.tokenizer
            self.tokenizer = None
        
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        
        logger.info("Model unloaded")



