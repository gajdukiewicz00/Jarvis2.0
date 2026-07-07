package org.jarvis.desktop.features.media

import javafx.application.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless construction + validation coverage for [MediaJobCreateForm].
 *
 * This only verifies that the widget tree builds without throwing and that
 * the form's own submit-validation logic (source path required; a
 * user-owned dub voice requires consent) produces the right
 * [MediaCreateJobRequest] — via [javafx.scene.control.Button.fire], which
 * invokes the registered action handler directly without any layout, CSS,
 * or pixel rendering. No toolkit rendering is asserted on.
 */
class MediaJobCreateFormTest {

    companion object {
        private var toolkitStarted = false

        @JvmStatic
        @BeforeAll
        fun startToolkit() {
            System.setProperty("testfx.robot", "glass")
            System.setProperty("testfx.headless", "true")
            System.setProperty("prism.order", "sw")
            System.setProperty("prism.text", "t2k")
            System.setProperty("glass.platform", "Monocle")
            System.setProperty("monocle.platform", "Headless")
            System.setProperty("java.awt.headless", "true")

            val latch = CountDownLatch(1)
            try {
                Platform.startup { latch.countDown() }
            } catch (alreadyStarted: IllegalStateException) {
                latch.countDown()
            }
            toolkitStarted = latch.await(10, TimeUnit.SECONDS)
        }
    }

    private fun onFxThread(build: () -> Unit) {
        assumeTrue(toolkitStarted, "JavaFX did not start headlessly in this environment — see HeadlessJavaFxSmokeTest")
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try {
                build()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Test body did not complete on the FX thread in time" }
        failure?.let { throw AssertionError("Test body threw on the FX thread", it) }
    }

    @Test
    fun `constructs with Probe selected by default and all four job kinds listed`() {
        onFxThread {
            val form = MediaJobCreateForm(onSubmit = {})
            assertNotNull(form)
            assertEquals(4, form.jobTypeCombo.items.size)
            assertEquals(MediaJobKind.PROBE, form.jobTypeCombo.value)
        }
    }

    @Test
    fun `Start is a no-op and shows a validation message when the source path is blank`() {
        onFxThread {
            var submitted: MediaCreateJobRequest? = null
            val form = MediaJobCreateForm(onSubmit = { submitted = it })

            form.sourcePathField.text = "   "
            form.startButton.fire()

            assertNull(submitted)
            assertTrue(form.validationLabel.text.isNotBlank())
            assertTrue(form.validationLabel.isVisible)
        }
    }

    @Test
    fun `Start submits a Probe request built from the entered source path`() {
        onFxThread {
            var submitted: MediaCreateJobRequest? = null
            val form = MediaJobCreateForm(onSubmit = { submitted = it })

            form.sourcePathField.text = "/workspace/movie.mp4"
            form.preferredLanguageField.text = "en"
            form.startButton.fire()

            val probe = submitted as? MediaCreateJobRequest.Probe
            assertNotNull(probe)
            assertEquals("/workspace/movie.mp4", probe!!.sourcePath)
            assertEquals("en", probe.preferredLanguage)
        }
    }

    @Test
    fun `Mux request carries the optional subtitle and dub-audio fields`() {
        onFxThread {
            var submitted: MediaCreateJobRequest? = null
            val form = MediaJobCreateForm(onSubmit = { submitted = it })

            form.jobTypeCombo.value = MediaJobKind.MUX
            form.sourcePathField.text = "/workspace/movie.mp4"
            form.subtitleFileField.text = "/workspace/sub.srt"
            form.startButton.fire()

            val mux = submitted as? MediaCreateJobRequest.Mux
            assertNotNull(mux)
            assertEquals("/workspace/movie.mp4", mux!!.sourcePath)
            assertEquals("/workspace/sub.srt", mux.subtitleFile)
            assertNull(mux.dubAudioFile)
        }
    }

    @Test
    fun `Dub requires explicit consent when the voice profile mode is user_owned`() {
        onFxThread {
            var submitted: MediaCreateJobRequest? = null
            val form = MediaJobCreateForm(onSubmit = { submitted = it })

            form.jobTypeCombo.value = MediaJobKind.RUSSIAN_DUB_AUDIO
            form.sourcePathField.text = "/workspace/transcript.ru.json"
            form.voiceModeCombo.value = MediaJobCreateForm.USER_OWNED_VOICE_MODE
            form.startButton.fire()

            assertNull(submitted)
            assertTrue(form.validationLabel.text.contains("consent", ignoreCase = true))

            form.consentCheckBox.isSelected = true
            form.startButton.fire()

            val dub = submitted as? MediaCreateJobRequest.Dub
            assertNotNull(dub)
            assertEquals(MediaJobCreateForm.USER_OWNED_VOICE_MODE, dub!!.voiceProfileMode)
            assertTrue(dub.consentConfirmed)
        }
    }

    @Test
    fun `setBusy disables the whole form`() {
        onFxThread {
            val form = MediaJobCreateForm(onSubmit = {})
            form.setBusy(true)
            assertTrue(form.startButton.isDisabled)
            form.setBusy(false)
            assertTrue(!form.startButton.isDisabled)
        }
    }
}
