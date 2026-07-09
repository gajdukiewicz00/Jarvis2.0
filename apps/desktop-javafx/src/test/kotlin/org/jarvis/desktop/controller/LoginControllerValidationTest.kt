package org.jarvis.desktop.controller

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import org.jarvis.desktop.e2e.E2eFx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [LoginController]'s synchronous validation branches and the
 * enter-key wiring in [LoginController.initialize].
 *
 * Only the early-return validation paths are exercised: they update the
 * inline error label and never spawn the background HTTP thread (which would
 * hit the real resolved gateway). The success paths that start a request
 * thread and navigate on completion are deliberately not fired.
 */
class LoginControllerValidationTest {

    private fun field(target: Any, name: String): Any {
        val f = LoginController::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    private fun inject(target: Any, name: String, value: Any) {
        val f = LoginController::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    /** Build a controller with all @FXML controls injected. Must run on the FX thread. */
    private fun newController(): LoginController {
        val controller = LoginController()
        inject(controller, "loginUsername", TextField())
        inject(controller, "loginPassword", PasswordField())
        inject(controller, "loginButton", Button())
        inject(controller, "loginError", Label())
        inject(controller, "registerUsername", TextField())
        inject(controller, "registerPassword", PasswordField())
        inject(controller, "registerPasswordConfirm", PasswordField())
        inject(controller, "registerButton", Button())
        inject(controller, "registerError", Label())
        inject(controller, "tabPane", TabPane())
        return controller
    }

    @Test
    fun `initialize wires enter-key handlers on the password fields`() {
        E2eFx.onFx {
            val controller = newController()
            controller.initialize()

            assertNotNull((field(controller, "loginPassword") as PasswordField).onAction)
            assertNotNull((field(controller, "registerPasswordConfirm") as PasswordField).onAction)
        }
    }

    @Test
    fun `handleLogin with empty fields shows the fill-all-fields error and leaves button enabled`() {
        E2eFx.onFx {
            val controller = newController()

            controller.handleLogin()

            val error = field(controller, "loginError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "loginButton") as Button).isDisable)
        }
    }

    @Test
    fun `handleLogin with a username but blank password still shows the fill-all-fields error`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "loginUsername") as TextField).text = "alice"

            controller.handleLogin()

            val error = field(controller, "loginError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
        }
    }

    @Test
    fun `handleRegister with empty fields shows the fill-all-fields error`() {
        E2eFx.onFx {
            val controller = newController()

            controller.handleRegister()

            val error = field(controller, "registerError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "registerButton") as Button).isDisable)
        }
    }

    @Test
    fun `handleRegister with mismatched passwords shows the passwords-do-not-match error`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "registerUsername") as TextField).text = "bob"
            (field(controller, "registerPassword") as PasswordField).text = "password1"
            (field(controller, "registerPasswordConfirm") as PasswordField).text = "password2"

            controller.handleRegister()

            assertEquals("Пароли не совпадают", (field(controller, "registerError") as Label).text)
        }
    }

    @Test
    fun `handleRegister with a too-short password shows the minimum-length error`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "registerUsername") as TextField).text = "bob"
            (field(controller, "registerPassword") as PasswordField).text = "short"
            (field(controller, "registerPasswordConfirm") as PasswordField).text = "short"

            controller.handleRegister()

            assertEquals("Пароль должен быть не менее 8 символов", (field(controller, "registerError") as Label).text)
        }
    }
}
