"""
Chat handler for processing multi-turn conversations
"""
import logging
from typing import List, Dict
from .model_loader import model_loader
from .config import config

logger = logging.getLogger(__name__)


class ChatHandler:
    """Handles chat requests with conversation history"""
    
    @staticmethod
    def format_prompt(messages: List[Dict[str, str]]) -> str:
        """
        Format messages into a simple prompt for prompt-only backends.

        The local runtime uses native role-based chat for llama.cpp, so this
        formatter is only a fallback for backends that still require raw text.
        
        Args:
            messages: List of message dicts with 'role' and 'content'
            
        Returns:
            Formatted prompt string
        """
        prompt_parts = []
        system_message = None
        conversation = []
        
        # Separate system message from conversation
        for msg in messages:
            if msg["role"] == "system":
                system_message = msg["content"]
            else:
                conversation.append(msg)
        
        # Build a Llama2-style prompt as a compatibility fallback.
        for i, msg in enumerate(conversation):
            if msg["role"] == "user":
                if i == 0 and system_message:
                    # First user message includes system prompt
                    prompt_parts.append(f"<s>[INST] <<SYS>>\n{system_message}\n<</SYS>>\n\n{msg['content']} [/INST]")
                else:
                    prompt_parts.append(f"<s>[INST] {msg['content']} [/INST]")
            elif msg["role"] == "assistant":
                prompt_parts.append(f" {msg['content']} </s>")
        
        # If last message was user, we're ready for generation
        # If last was assistant, add a new user turn marker
        prompt = "".join(prompt_parts)
        
        # If the conversation ends with assistant, we need to add prompt for next user turn
        if conversation and conversation[-1]["role"] == "assistant":
            prompt += "\n<s>[INST] "
        
        return prompt
    
    @staticmethod
    def process_chat(
        messages: List[Dict[str, str]],
        max_tokens: int = None,
        temperature: float = None
    ) -> Dict[str, any]:
        """
        Process a chat request
        
        Args:
            messages: List of message dicts
            max_tokens: Maximum tokens to generate
            temperature: Sampling temperature
            
        Returns:
            Dict with reply, tokens, and model info
        """
        # Validate messages
        if not messages:
            raise ValueError("Messages list cannot be empty")
        
        # Limit history length
        if len(messages) > config.MAX_HISTORY_LENGTH:
            # Keep system message if present, then trim oldest messages
            system_msgs = [m for m in messages if m["role"] == "system"]
            other_msgs = [m for m in messages if m["role"] != "system"]
            keep = config.MAX_HISTORY_LENGTH - len(system_msgs)
            # Guard the zero/negative case explicitly: `other_msgs[-0:]` is
            # `other_msgs[0:]` (the whole list) in Python, which would
            # silently defeat this trim entirely when len(system_msgs) ==
            # MAX_HISTORY_LENGTH (or leave nothing trimmed at all if system
            # messages alone already exceed the limit).
            messages = system_msgs + (other_msgs[-keep:] if keep > 0 else [])
        
        # Use defaults if not provided
        max_tokens = max_tokens or config.MAX_TOKENS
        temperature = temperature or config.TEMPERATURE
        
        try:
            if model_loader.supports_chat_messages():
                reply, input_tokens, output_tokens = model_loader.chat(
                    messages=messages,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    top_p=config.TOP_P
                )
            else:
                prompt = ChatHandler.format_prompt(messages)
                logger.debug(f"Formatted prompt: {prompt[:200]}...")
                reply, input_tokens, output_tokens = model_loader.generate(
                    prompt=prompt,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    top_p=config.TOP_P
                )
            
            return {
                "reply": reply,
                "tokens": {
                    "input": input_tokens,
                    "output": output_tokens
                },
                "model": model_loader.model_name
            }
            
        except Exception as e:
            logger.error(f"Chat processing failed: {e}")
            raise


chat_handler = ChatHandler()
