"""
Abstract base class for LLM backends
"""
from abc import ABC, abstractmethod
from typing import Dict, Tuple, Optional, Generator
import logging

logger = logging.getLogger(__name__)


class LLMBackend(ABC):
    """Abstract base class for LLM backends (transformers, llama.cpp, etc.)"""
    
    @abstractmethod
    def load(self) -> None:
        """Load the model into memory"""
        pass
    
    @abstractmethod
    def is_loaded(self) -> bool:
        """Check if model is loaded and ready"""
        pass
    
    @abstractmethod
    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Tuple[str, int, int]:
        """
        Generate text from prompt.
        
        Args:
            prompt: Input prompt (formatted for model)
            max_tokens: Maximum tokens to generate
            temperature: Sampling temperature
            top_p: Nucleus sampling parameter
            
        Returns:
            Tuple of (generated_text, input_tokens, output_tokens)
        """
        pass
    
    @abstractmethod
    def generate_stream(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Generator[str, None, None]:
        """
        Generate text with streaming output.
        
        Args:
            prompt: Input prompt
            max_tokens: Maximum tokens to generate
            temperature: Sampling temperature
            top_p: Nucleus sampling
            
        Yields:
            Text chunks as they are generated
        """
        pass
    
    @abstractmethod
    def warmup(self) -> None:
        """Run warmup inference to initialize caches"""
        pass
    
    @abstractmethod
    def get_diagnostics(self) -> Dict:
        """
        Get diagnostic information about the backend.
        
        Returns:
            Dict with backend info (name, device, memory usage, etc.)
        """
        pass
    
    @abstractmethod
    def unload(self) -> None:
        """Unload model from memory"""
        pass



