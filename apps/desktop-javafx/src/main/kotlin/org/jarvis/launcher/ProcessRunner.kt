package org.jarvis.launcher

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages execution of backend/stop scripts.
 * Handles stdout/stderr streaming to both file and UI callback.
 * Uses lock files to prevent concurrent execution.
 */
class ProcessRunner(
    private val logFile: Path,
    private val onOutput: (String) -> Unit
) {
    companion object {
        private val startGuard = AtomicBoolean(false)
    }

    private val logger = LoggerFactory.getLogger(ProcessRunner::class.java)
    private val processRef = AtomicReference<Process?>(null)
    private val isRunning = AtomicBoolean(false)
    private var tempLockScript: Path? = null
    private var backendLockChannel: FileChannel? = null
    private var backendLock: FileLock? = null
    
    /**
     * Start a process (e.g., jarvis-launch.sh).
     * @param scriptPath Path to executable script
     * @param envVars Environment variables to set
     * @param workingDir Working directory for the process
     */
    fun start(
        scriptPath: Path,
        envVars: Map<String, String> = emptyMap(),
        workingDir: Path? = null
    ): CompletableFuture<Int> {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Process already running, ignoring start request")
            return CompletableFuture.completedFuture(0)
        }
        if (!startGuard.compareAndSet(false, true)) {
            logger.warn("Start already in progress, skipping")
            isRunning.set(false)
            return CompletableFuture.completedFuture(1)
        }
        
        val future = CompletableFuture<Int>()
        
        try {
            // Ensure log file exists
            Files.createDirectories(logFile.parent)
            if (!Files.exists(logFile)) {
                Files.createFile(logFile)
            }
            
            // Acquire lock to prevent concurrent backend starts
            val lockFile = JarvisPaths.run.resolve("backend.lock")
            Files.createDirectories(lockFile.parent)
            backendLockChannel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            backendLock = try {
                backendLockChannel?.tryLock()
            } catch (e: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (backendLock == null) {
                logger.warn("Backend lock is already held, skipping start")
                onOutput("ERROR: Backend already starting (lock held)")
                backendLockChannel?.close()
                backendLockChannel = null
                isRunning.set(false)
                startGuard.set(false)
                future.complete(1)
                return future
            }
            
            // Create lock script wrapper
                val lockFileStr = lockFile.toString()
                val scriptPathStr = scriptPath.toString()
                val lockScript = """
                    #!/bin/bash
                    LOCK_FILE="$lockFileStr"
                    exec 9>"${'$'}LOCK_FILE"
                    if ! flock -n 9; then
                        echo "ERROR: Backend already starting (lock held by another process)"
                        exit 1
                    fi
                    # Lock acquired, will be released when script exits
                    exec "$scriptPathStr" "${'$'}@"
                """.trimIndent()
            
            // Create temporary lock script
            val tempLock = Files.createTempFile("jarvis-backend-lock-", ".sh")
            tempLockScript = tempLock
            try {
                Files.write(tempLock, lockScript.toByteArray())
                // Set executable permissions
                try {
                    Files.setPosixFilePermissions(tempLock, 
                        java.util.Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE
                        ))
                } catch (e: Exception) {
                    // Fallback: use chmod via Runtime if Posix not available
                    logger.warn("Could not set Posix permissions, using chmod fallback")
                    Runtime.getRuntime().exec(arrayOf("chmod", "+x", tempLock.toString())).waitFor()
                }
                
                // Use lock script wrapper instead of direct script
                val processBuilder = ProcessBuilder()
                    .command("/bin/bash", tempLock.toString())
                    .redirectErrorStream(true) // Merge stderr into stdout
                
                // Set working directory
                if (workingDir != null) {
                    processBuilder.directory(workingDir.toFile())
                } else {
                    processBuilder.directory(scriptPath.parent.toFile())
                }
                
                // Set environment variables
                val env = processBuilder.environment()
                envVars.forEach { (key, value) ->
                    env[key] = value
                }
                
                // Add JARVIS_LOG_DIR if not set
                if (!env.containsKey("JARVIS_LOG_DIR")) {
                    env["JARVIS_LOG_DIR"] = JarvisPaths.logs.toString()
                }
                
                logger.info("Starting process with lock: ${scriptPath}")
                logger.info("Working directory: ${processBuilder.directory()}")
                logger.info("Log file: $logFile")
                
                val process = processBuilder.start()
                processRef.set(process)
                
                // Write PID to file
                val pidFile = JarvisPaths.backendPid
                Files.createDirectories(pidFile.parent)
                Files.writeString(pidFile, process.pid().toString())
                logger.info("Process started with PID: ${process.pid()}")
                
                // Start thread to read stdout/stderr and write to file + callback
                Thread {
                    try {
                        val fileWriter = FileWriter(logFile.toFile(), true)
                        val reader = process.inputStream.bufferedReader()
                        
                        reader.useLines { lines ->
                            lines.forEach { line ->
                                // Write to file
                                fileWriter.appendLine(line)
                                fileWriter.flush()
                                
                                // Callback to UI (on JavaFX thread)
                                onOutput(line)
                            }
                        }
                        
                        fileWriter.close()
                    } catch (e: Exception) {
                        logger.error("Error reading process output", e)
                    } finally {
                        val exitCode = process.waitFor()
                        isRunning.set(false)
                        processRef.set(null)
                        startGuard.set(false)
                        
                        // Clean up PID file
                        try {
                            Files.deleteIfExists(JarvisPaths.backendPid)
                        } catch (e: Exception) {
                            logger.warn("Failed to delete PID file", e)
                        }
                        
                        logger.info("Process finished with exit code: $exitCode")
                        future.complete(exitCode)
                        releaseBackendLock()
                    }
                }.apply {
                    isDaemon = true
                    name = "jarvis-backend-output-${process.pid()}"
                    start()
                }
                
            } finally {
                // Clean up temp lock script (will be deleted after process exits)
                // Note: We can't delete it immediately as the process is using it
                // It will be cleaned up by the OS when the process finishes
            }
            
        } catch (e: Exception) {
            logger.error("Failed to start process: ${scriptPath}", e)
            isRunning.set(false)
            startGuard.set(false)
            // Clean up temp lock script on error
            tempLockScript?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (e2: Exception) {
                    // Ignore
                }
            }
            releaseBackendLock()
            future.completeExceptionally(e)
        }
        
        return future
    }

    private fun releaseBackendLock() {
        try {
            backendLock?.release()
        } catch (e: Exception) {
            logger.debug("Failed to release backend lock", e)
        } finally {
            backendLock = null
        }
        try {
            backendLockChannel?.close()
        } catch (e: Exception) {
            logger.debug("Failed to close backend lock channel", e)
        } finally {
            backendLockChannel = null
        }
    }
    
    /**
     * Stop the running process.
     */
    fun stop(): Boolean {
        val process = processRef.get()
        if (process == null || !isRunning.get()) {
            logger.info("No process running to stop")
            return false
        }
        
        try {
            logger.info("Stopping process (PID: ${process.pid()})")
            process.destroy()
            
            // Wait up to 5 seconds for graceful shutdown
            val terminated = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!terminated) {
                logger.warn("Process did not terminate gracefully, forcing...")
                process.destroyForcibly()
                process.waitFor()
            }
            
            isRunning.set(false)
            processRef.set(null)
            startGuard.set(false)
            
            // Clean up PID file
            try {
                Files.deleteIfExists(JarvisPaths.backendPid)
            } catch (e: Exception) {
                logger.warn("Failed to delete PID file", e)
            }
            
            logger.info("Process stopped")
            releaseBackendLock()
            return true
        } catch (e: Exception) {
            logger.error("Error stopping process", e)
            startGuard.set(false)
            releaseBackendLock()
            return false
        }
    }
    
    /**
     * Check if process is currently running.
     */
    fun isRunning(): Boolean {
        val process = processRef.get()
        if (process == null) {
            return false
        }
        
        // Check if process is still alive
        return try {
            process.isAlive
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get process PID if running.
     */
    fun getPid(): Long? {
        val process = processRef.get()
        return process?.pid()
    }
}
