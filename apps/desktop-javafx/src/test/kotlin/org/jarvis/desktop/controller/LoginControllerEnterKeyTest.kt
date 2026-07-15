package org.jarvis.desktop.controller

import javafx.event.ActionEvent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import org.jarvis.desktop.e2e.E2eFx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Complements [LoginControllerValidationTest] by firing the enter-key
 * [javafx.event.ActionEvent] handlers that [LoginController.initialize] wires
 * onto the password fields. Firing the handler executes the `{ handleLogin() }`
 * / `{ handleRegister() }` lambda bodies (as opposed to merely constructing
 * them), then falls through the synchronous empty-field validation guard so no
 * background HTTP thread is ever started.
 */
class LoginControllerEnterKeyTest {

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
    fun `firing the login password enter handler runs handleLogin and hits the empty-field guard`() {
        E2eFx.onFx {
            val controller = newController()
            controller.initialize()

            val loginPassword = field(controller, "loginPassword") as PasswordField
            loginPassword.onAction.handle(ActionEvent())

            val error = field(controller, "loginError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "loginButton") as Button).isDisable)
        }
    }

    @Test
    fun `firing the register confirm enter handler runs handleRegister and hits the empty-field guard`() {
        E2eFx.onFx {
            val controller = newController()
            controller.initialize()

            val registerConfirm = field(controller, "registerPasswordConfirm") as PasswordField
            registerConfirm.onAction.handle(ActionEvent())

            val error = field(controller, "registerError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "registerButton") as Button).isDisable)
        }
    }

    @Test
    fun `loginSuccessHandler companion property round-trips`() {
        val previous = LoginController.loginSuccessHandler
        try {
            val handler: (javafx.stage.Stage) -> Unit = { }
            LoginController.loginSuccessHandler = handler
            assertTrue(LoginController.loginSuccessHandler === handler)

            LoginController.loginSuccessHandler = null
            assertTrue(LoginController.loginSuccessHandler == null)
        } finally {
            LoginController.loginSuccessHandler = previous
        }
    }
}
