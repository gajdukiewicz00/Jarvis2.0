Feature: Linux desktop control

  Scenario: Open installed application by discovered name
    Given a Linux system with desktop entries available
    And an application named "Firefox" is discovered
    When Jarvis receives a request to open application "Firefox"
    Then the desktop control adapter launches the application
    And returns a successful result

  Scenario: Open URL in default browser
    Given a valid HTTPS URL
    When Jarvis receives a request to open that URL
    Then the default system browser is invoked
    And the operation result is marked successful

  Scenario: Read current output volume
    Given an available Linux audio backend
    When Jarvis requests the current volume
    Then the adapter returns the current level and mute state

  Scenario: Fallback to alternative audio backend
    Given wpctl is unavailable
    And pactl is available
    When Jarvis requests the current volume
    Then the adapter uses pactl
    And returns a successful result

  Scenario: Reject unsafe application execution input
    Given a malformed or unsafe application request
    When Jarvis attempts to launch it
    Then the adapter rejects the request
    And returns a validation error without executing a shell command

  # --- Slice 2: open_file ---

  Scenario: Open file with default application
    Given a Linux system with xdg-open available
    When Jarvis receives a request to open file "/home/user/document.pdf"
    Then xdg-open is invoked with the file path
    And the operation result includes "open_file" and the path

  Scenario: Reject blank file path
    When Jarvis receives a request to open file with a blank path
    Then the adapter rejects the request with a validation error

  # --- Slice 2: window management ---

  Scenario: Focus window by ID using wmctrl
    Given wmctrl is available
    When Jarvis receives a request to focus window "0x04000003"
    Then wmctrl is invoked with the window ID
    And the operation result is marked successful with backend "wmctrl"

  Scenario: Focus window by name using xdotool fallback
    Given wmctrl is unavailable
    And xdotool is available
    When Jarvis receives a request to focus window named "Firefox"
    Then xdotool search and windowactivate are invoked
    And the operation result is marked successful with backend "xdotool"

  Scenario: Get active window information
    Given xdotool is available
    When Jarvis requests the active window
    Then the adapter returns the window ID, title, and WM_CLASS

  Scenario: List open windows
    Given wmctrl is available
    When Jarvis requests the list of open windows
    Then the adapter returns a list of window IDs, titles, and desktop numbers

  Scenario: Reject window focus without identifier
    When Jarvis receives a window focus request with no ID or name
    Then the adapter rejects the request with a validation error

  # --- Slice 2: send_keys ---

  Scenario: Send keyboard shortcut to active window
    Given xdotool is available
    When Jarvis receives a request to send keys "ctrl+shift+t"
    Then xdotool key is invoked with the key combination
    And the operation result is marked successful

  Scenario: Send keys to specific window
    Given xdotool is available
    When Jarvis receives a request to send keys "Return" to window "12345"
    Then xdotool key is invoked with --window 12345
    And the operation result is marked successful

  Scenario: Reject unsafe key sequence
    When Jarvis receives a request to send keys containing shell metacharacters
    Then the adapter rejects the request with a validation error

  # --- Slice 2: mouse actions ---

  Scenario: Click at screen coordinates
    Given xdotool is available
    When Jarvis receives a request to click at x=100 y=200 button=1
    Then xdotool mousemove and click are invoked
    And the operation result is marked successful

  Scenario: Move mouse to coordinates
    Given xdotool is available
    When Jarvis receives a request to move mouse to x=500 y=300
    Then xdotool mousemove is invoked
    And the operation result is marked successful

  Scenario: Scroll down
    Given xdotool is available
    When Jarvis receives a request to scroll down 3 clicks
    Then xdotool click --repeat 3 with button 5 is invoked
    And the operation result is marked successful

  Scenario: Scroll up
    Given xdotool is available
    When Jarvis receives a request to scroll up 5 clicks
    Then xdotool click --repeat 5 with button 4 is invoked
    And the operation result is marked successful

  Scenario: Reject negative mouse coordinates
    When Jarvis receives a request to click at x=-1 y=100
    Then the adapter rejects the request with a validation error

  Scenario: Reject invalid scroll direction
    When Jarvis receives a request to scroll with direction "diagonal"
    Then the adapter rejects the request with a validation error

  Scenario: Reject excessive scroll amount
    When Jarvis receives a request to scroll 101 clicks
    Then the adapter rejects the request with a validation error

  Scenario: Reject input when xdotool is unavailable
    Given xdotool is unavailable
    When Jarvis receives a request to send keys "Return"
    Then the adapter reports the tool is unavailable

  # --- Slice 3: capabilities and error handling ---

  Scenario: Request desktop capabilities on X11
    Given the system is running X11
    And all requisite tools are available
    When Jarvis requests the desktop capabilities
    Then the adapter reports full support for all operations

  Scenario: Request desktop capabilities on Wayland
    Given the system is running Wayland
    And all requisite tools are available
    When Jarvis requests the desktop capabilities
    Then the adapter reports Wayland environment
    And marks window and input operations as degraded

  Scenario: Request desktop capabilities with missing tools
    Given the system is missing xdotool and wmctrl
    When Jarvis requests the desktop capabilities
    Then the adapter reports those tools as unavailable
    And marks window and input operations as unsupported

  Scenario: Attempt operation without requisite tools
    Given xdotool is unavailable
    When Jarvis requests an xdotool operation
    Then a MissingToolException is thrown
    And the api returns a 503 response code indicating the missing tool

  Scenario: Attempt operation on headless display server
    Given the system has no display server
    When Jarvis requests an input or window operation
    Then an UnsupportedDisplayServerException is thrown
    And the api returns a 503 response code indicating the unsupported display server

  Scenario: Open file when xdg-open is missing
    Given xdg-open is unavailable
    When Jarvis receives a request to open a file
    Then a MissingToolException is thrown for xdg-open
    And the api returns a 503 response code indicating the missing tool

  Scenario: Attempt window operation for non-existent window
    Given a valid request for a non-existent window
    When Jarvis attempts the operation
    Then a WindowNotFoundException is thrown
    And the api returns a 404 response code
