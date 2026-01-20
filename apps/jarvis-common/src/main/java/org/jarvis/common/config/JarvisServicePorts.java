package org.jarvis.common.config;

/**
 * Centralized port definitions for all Jarvis services.
 * These ports are FIXED and should not be changed without coordination.
 * 
 * Port allocation strategy:
 * - 8080-8099: Core services (gateway, voice, orchestrator)
 * - 5000-5099: Python ML services (LLM, embeddings)
 * - 5432: PostgreSQL
 * - 1883: MQTT (Mosquitto)
 */
public final class JarvisServicePorts {

    private JarvisServicePorts() {
        // Constants class
    }

    // ==================== Core Services ====================
    
    /** API Gateway - Main entry point */
    public static final int API_GATEWAY = 8080;
    
    /** Voice Gateway - STT/TTS processing */
    public static final int VOICE_GATEWAY = 8081;
    
    /** NLP Service - Intent recognition */
    public static final int NLP_SERVICE = 8082;
    
    /** Orchestrator - Command routing */
    public static final int ORCHESTRATOR = 8083;
    
    /** PC Control - System commands */
    public static final int PC_CONTROL = 8084;
    
    /** Security Service - JWT auth */
    public static final int SECURITY_SERVICE = 8088;
    
    /** Smart Home Service - IoT */
    public static final int SMART_HOME_SERVICE = 8086;
    
    /** Analytics Service - Data analysis */
    public static final int ANALYTICS_SERVICE = 8087;
    
    /** Life Tracker - Time/expense tracking */
    public static final int LIFE_TRACKER = 8085;
    
    /** User Profile - User preferences */
    public static final int USER_PROFILE = 8089;
    
    // ==================== LLM Services ====================
    
    /** LLM Service (Java) - API wrapper */
    public static final int LLM_SERVICE = 8091;
    
    /** Planner Service - Tasks/reminders */
    public static final int PLANNER_SERVICE = 8092;
    
    /** Memory Service - Long-term memory */
    public static final int MEMORY_SERVICE = 8093;
    
    /** LLM Server (Python) - Model inference */
    public static final int LLM_SERVER = 5000;
    
    /** Embedding Service (Python) - Text embeddings */
    public static final int EMBEDDING_SERVICE = 5001;
    
    // ==================== Infrastructure ====================
    
    /** PostgreSQL database */
    public static final int POSTGRES = 5432;
    
    /** MQTT broker (Mosquitto) */
    public static final int MQTT = 1883;
    
    /** MQTT WebSocket */
    public static final int MQTT_WS = 9001;
    
    /** RabbitMQ (if used) */
    public static final int RABBITMQ = 5672;
    
    /** RabbitMQ Management UI */
    public static final int RABBITMQ_MANAGEMENT = 15672;
}
