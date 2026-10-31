"""Voice integration — offline Whisper STT, Optimus/Kokoro TTS, signal bus."""

from ui.voice.optimus_tts import OptimusTtsError, default_optimus_dir, synthesize as optimus_synthesize, warmup as optimus_warmup
from ui.voice.kokoro_tts import KokoroTtsError, synthesize as kokoro_synthesize, warmup as kokoro_warmup
from ui.voice.bus import VoiceBus, read_voice_state, signals_dir, write_voice_state
from ui.voice.speech import SpeechError, synthesize as synthesize_speech, warmup as warmup_speech
from ui.voice.whisper_stt import WhisperSttError, transcribe_pcm_bytes

__all__ = [
    "KokoroTtsError",
    "OptimusTtsError",
    "SpeechError",
    "VoiceBus",
    "WhisperSttError",
    "default_optimus_dir",
    "kokoro_synthesize",
    "kokoro_warmup",
    "optimus_synthesize",
    "optimus_warmup",
    "read_voice_state",
    "signals_dir",
    "synthesize_speech",
    "transcribe_pcm_bytes",
    "warmup_speech",
    "write_voice_state",
]
