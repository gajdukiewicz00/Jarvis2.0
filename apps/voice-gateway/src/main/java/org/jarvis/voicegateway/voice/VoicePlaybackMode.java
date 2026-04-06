package org.jarvis.voicegateway.voice;

/**
 * How voice output should be delivered.
 * <ul>
 *   <li>PRE_RECORDED — use a .wav asset from voice-assets</li>
 *   <li>TTS — synthesize via TTS (Google Cloud / eSpeak)</li>
 *   <li>SILENT — no audio (e.g. notification-only, or suppress playback)</li>
 * </ul>
 */
public enum VoicePlaybackMode {
    PRE_RECORDED,
    TTS,
    SILENT
}
