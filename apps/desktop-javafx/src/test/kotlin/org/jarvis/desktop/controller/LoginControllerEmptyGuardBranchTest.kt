package org.jarvis.desktop.controller

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
 * Third-wave synchronous-guard coverage for [LoginController].
 *
 * The sibling [LoginControllerValidationTest] / [LoginControllerEnterKeyTest]
 * already cover: the fully-empty login/register guard, the login
 * username-present-blank-password guard, and the register mismatch / too-short
 * guards. This file targets the still-uncovered *branches* of the empty-field
 * guards that those tests do not reach:
 *
 *  - [LoginController.handleRegister]'s `password.isNullOrEmpty()` operand — the
 *    SECOND arm of `username.isNullOrEmpty() || password.isNullOrEmpty()`. The
 *    existing register test supplies BOTH fields empty, so the `||`
 *    short-circuits on the first operand and the second is never evaluated true.
 *  - the `TextField.text?.trim()` → empty flow for a whitespace-only username in
 *    both handlers (existing tests use an already-empty username, so `trim()`
 *    is a no-op there).
 *
 * All paths hit the early-return guard and update the inline error label; none
 * start the background HTTP thread, touch [org.jarvis.desktop.config.AppConfig],
 * persist tokens, or open a modal.
 *
 * NOT covered here (documented, not overlooked): the async login/register
 * request thread bodies (success / non-2xx / exception) and `navigateToMainApp`.
 * Those are hard-wired to the `AppConfig.current()` singleton + a real
 * `HttpClient` with no injectable seam, and the success arm additionally
 * persists to `TokenManager` (real OS Preferences) and dereferences
 * `loginButton.scene.window` (null headless). Driving them would require
 * mutating global user Preferences / real network I/O — an unacceptable,
 * non-deterministic side effect, so they are deliberately left out.
 */
class LoginControllerEmptyGuardBranchTest {

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
    fun `handleRegister with a username but empty password hits the second guard operand`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "registerUsername") as TextField).text = "carol"
            // password + confirm left empty -> first operand false, second operand true.

            controller.handleRegister()

            val error = field(controller, "registerError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "registerButton") as Button).isDisable)
        }
    }

    @Test
    fun `handleLogin with a whitespace-only username trims to empty and hits the guard`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "loginUsername") as TextField).text = "   "
            (field(controller, "loginPassword") as PasswordField).text = "whatever"

            controller.handleLogin()

            val error = field(controller, "loginError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
            assertFalse((field(controller, "loginButton") as Button).isDisable)
        }
    }

    @Test
    fun `handleRegister with a whitespace-only username trims to empty and hits the guard`() {
        E2eFx.onFx {
            val controller = newController()
            (field(controller, "registerUsername") as TextField).text = "\t \n"
            (field(controller, "registerPassword") as PasswordField).text = "password123"
            (field(controller, "registerPasswordConfirm") as PasswordField).text = "password123"

            controller.handleRegister()

            val error = field(controller, "registerError") as Label
            assertEquals("Пожалуйста, заполните все поля", error.text)
            assertTrue(error.isVisible)
        }
    }
}
