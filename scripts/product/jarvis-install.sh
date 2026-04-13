#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Product Install Script
# =============================================================================
# Installs Jarvis launcher and scripts to ~/.jarvis/app/
# Makes it independent of repository location.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JARVIS_APP="${HOME}/.jarvis/app"
JARVIS_BIN="${JARVIS_APP}/bin"
JARVIS_DESKTOP="${HOME}/.local/share/applications"

# Stage 11: Parse --from-release argument
FROM_RELEASE=false
RELEASE_DIR=""
for arg in "$@"; do
    case "$arg" in
        --from-release)
            FROM_RELEASE=true
            ;;
        --from-release=*)
            FROM_RELEASE=true
            RELEASE_DIR="${arg#*=}"
            ;;
    esac
done

# Stage 11: Check environment variable
if [[ -z "$RELEASE_DIR" ]] && [[ -n "${JARVIS_RELEASE_DIR:-}" ]]; then
    FROM_RELEASE=true
    RELEASE_DIR="${JARVIS_RELEASE_DIR}"
fi

echo "=========================================="
echo "Jarvis 2.0 - Product Install"
echo "=========================================="
echo ""
if [[ "$FROM_RELEASE" == "true" ]]; then
    if [[ -z "$RELEASE_DIR" ]]; then
        echo "ERROR: --from-release requires a path"
        echo "Usage: $0 --from-release <path-to-release-dir>"
        exit 1
    fi
    RELEASE_DIR="$(cd "$RELEASE_DIR" && pwd)"
    echo "Installing from release directory: ${RELEASE_DIR}"
else
    echo "Installing from repository: ${REPO_ROOT}"
fi
echo "Installing to: ${JARVIS_APP}"
echo ""

# Create directories
mkdir -p "${JARVIS_APP}"
mkdir -p "${JARVIS_BIN}"
mkdir -p "${JARVIS_DESKTOP}"
mkdir -p "${JARVIS_APP}/assets/icons"  # Stage 15: Icons directory

# Stage 11: Install from release directory
if [[ "$FROM_RELEASE" == "true" ]]; then
    if [[ ! -d "$RELEASE_DIR" ]]; then
        echo "ERROR: Release directory not found: $RELEASE_DIR"
        exit 1
    fi

    VERSION=$(cat "${RELEASE_DIR}/VERSION" 2>/dev/null || echo "unknown")
    DESKTOP_JAR="${RELEASE_DIR}/desktop-javafx-${VERSION}.jar"
    if [[ ! -f "$DESKTOP_JAR" ]]; then
        DESKTOP_JAR="$(find "${RELEASE_DIR}" -maxdepth 1 -type f -name 'desktop-javafx-*.jar' | head -1 || true)"
    fi
    if [[ -z "${DESKTOP_JAR}" ]] || [[ ! -f "$DESKTOP_JAR" ]]; then
        echo "ERROR: desktop-javafx release JAR not found in release directory: $RELEASE_DIR"
        exit 1
    fi

    echo "Installing version: $VERSION"
else
    # Original repo-based install
    DESKTOP_JAR="${REPO_ROOT}/apps/desktop-javafx/target/desktop-javafx-0.1.0-SNAPSHOT.jar"
    if [[ ! -f "$DESKTOP_JAR" ]]; then
        echo "ERROR: Unified desktop JAR not found: $DESKTOP_JAR"
        echo "Please build desktop-javafx first:"
        echo "  mvn -pl apps/desktop-javafx -DskipTests clean package"
        exit 1
    fi
fi

echo "Installing unified desktop JAR..."
cp "$DESKTOP_JAR" "${JARVIS_APP}/desktop-javafx.jar"
echo "  ✅ ${JARVIS_APP}/desktop-javafx.jar"

# Stage 11: Copy scripts from release or repo
if [[ "$FROM_RELEASE" == "true" ]]; then
    echo "Installing scripts from release..."
    if [[ -d "${RELEASE_DIR}/bin" ]]; then
        cp "${RELEASE_DIR}/bin"/*.sh "${JARVIS_BIN}/"
        chmod +x "${JARVIS_BIN}"/*.sh
        echo "  ✅ Scripts copied from release"
    else
        echo "ERROR: bin/ directory not found in release: $RELEASE_DIR"
        exit 1
    fi
    
    # Copy config from release
    if [[ -d "${RELEASE_DIR}/config" ]]; then
        cp -r "${RELEASE_DIR}/config" "${JARVIS_APP}/"
        echo "  ✅ Config copied from release"
    fi
    
    # Stage 15: Copy icons from release
    if [[ -d "${RELEASE_DIR}/assets/icons" ]]; then
        cp -r "${RELEASE_DIR}/assets/icons" "${JARVIS_APP}/assets/"
        echo "  ✅ Icons copied from release"
    elif [[ -f "${RELEASE_DIR}/assets/icons/jarvis.png" ]]; then
        mkdir -p "${JARVIS_APP}/assets/icons"
        cp "${RELEASE_DIR}/assets/icons/jarvis.png" "${JARVIS_APP}/assets/icons/"
        echo "  ✅ Icon copied from release"
    fi
    
    # Get version from release VERSION file
    VERSION=$(cat "${RELEASE_DIR}/VERSION" 2>/dev/null || echo "unknown")
else
    # Original repo-based install
    echo "Installing scripts..."
    cp "${REPO_ROOT}/jarvis-launch.sh" "${JARVIS_APP}/"
    cp "${REPO_ROOT}/jarvis-stop.sh" "${JARVIS_APP}/"
    cp "${REPO_ROOT}/jarvis-logs.sh" "${JARVIS_APP}/"
    chmod +x "${JARVIS_APP}/jarvis-launch.sh" "${JARVIS_APP}/jarvis-stop.sh" "${JARVIS_APP}/jarvis-logs.sh"
    cp "${SCRIPT_DIR}/jarvis-launcher.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-stop.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-diagnostics.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-generate-certs.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-install-tls.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-setup-hosts.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-secrets-apply.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-system-setup.sh" "${JARVIS_BIN}/"
    cp "${REPO_ROOT}/scripts/verify-prod.sh" "${JARVIS_BIN}/"
    chmod +x "${JARVIS_BIN}"/*.sh
    echo "  ✅ ${JARVIS_APP}/jarvis-launch.sh"
    echo "  ✅ ${JARVIS_APP}/jarvis-stop.sh"
    echo "  ✅ ${JARVIS_APP}/jarvis-logs.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-launcher.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-stop.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-diagnostics.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-generate-certs.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-install-tls.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-setup-hosts.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-secrets-apply.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-system-setup.sh"
    echo "  ✅ ${JARVIS_BIN}/verify-prod.sh"
    
    # Copy logback config (for unified desktop logging)
    if [[ -f "${REPO_ROOT}/apps/desktop-javafx/src/main/resources/logback.xml" ]]; then
        mkdir -p "${JARVIS_APP}/config"
        cp "${REPO_ROOT}/apps/desktop-javafx/src/main/resources/logback.xml" "${JARVIS_APP}/config/"
        echo "  ✅ ${JARVIS_APP}/config/logback.xml"
    fi
    
    # Stage 15: Copy icons
    echo "Installing icons..."
    if [[ -f "${REPO_ROOT}/assets/icons/jarvis.png" ]]; then
        cp "${REPO_ROOT}/assets/icons/jarvis.png" "${JARVIS_APP}/assets/icons/"
        echo "  ✅ ${JARVIS_APP}/assets/icons/jarvis.png"
    elif [[ -f "${REPO_ROOT}/icons/jarvis-icon.png" ]]; then
        # Fallback to legacy icon location
        cp "${REPO_ROOT}/icons/jarvis-icon.png" "${JARVIS_APP}/assets/icons/jarvis.png"
        echo "  ✅ ${JARVIS_APP}/assets/icons/jarvis.png (from legacy location)"
    else
        echo "  WARNING: Icon not found, desktop entry will use default icon"
    fi
    if [[ -f "${REPO_ROOT}/assets/icons/jarvis.svg" ]]; then
        cp "${REPO_ROOT}/assets/icons/jarvis.svg" "${JARVIS_APP}/assets/icons/"
        echo "  ✅ ${JARVIS_APP}/assets/icons/jarvis.svg"
    fi
    
    # Stage 9: Get version from root pom.xml (single source of truth for jarvis-root, not parent)
    VERSION=$(grep -A1 "<artifactId>jarvis-root</artifactId>" "${REPO_ROOT}/pom.xml" | grep "<version>" | head -1 | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/' | tr -d ' ' || echo "0.1.0-SNAPSHOT")
fi

# Stage 9: Check if upgrade (existing installation)
INSTALL_TYPE="fresh"
OLD_VERSION=""
if [[ -f "${JARVIS_APP}/VERSION" ]]; then
    OLD_VERSION=$(cat "${JARVIS_APP}/VERSION" 2>/dev/null || echo "")
    if [[ -n "$OLD_VERSION" ]] && [[ "$OLD_VERSION" != "$VERSION" ]]; then
        INSTALL_TYPE="upgrade"
        echo "Upgrading from $OLD_VERSION to $VERSION..."
    elif [[ "$OLD_VERSION" == "$VERSION" ]]; then
        INSTALL_TYPE="reinstall"
        echo "Reinstalling version $VERSION..."
    fi
fi

# Write VERSION file
echo "$VERSION" > "${JARVIS_APP}/VERSION"
echo "  ✅ ${JARVIS_APP}/VERSION (version: $VERSION)"

# Stage 12: Write RELEASE_SOURCE if installing from release
if [[ "$FROM_RELEASE" == "true" ]]; then
    echo "${RELEASE_DIR}" > "${JARVIS_APP}/RELEASE_SOURCE"
    echo "  ✅ Release source recorded: ${JARVIS_APP}/RELEASE_SOURCE"
else
    # For repo-based install, persist the real workspace path so GUI launch can
    # delegate back to the latest repo sources instead of drifting on a stale
    # ~/.jarvis/app copy.
    echo "${REPO_ROOT}" > "${JARVIS_APP}/RELEASE_SOURCE"
    echo "  ✅ Release source recorded: ${JARVIS_APP}/RELEASE_SOURCE"
fi

# Log installation/upgrade
JARVIS_HOME="${HOME}/.jarvis"
INSTALL_LOG="${JARVIS_HOME}/logs/install.log"
mkdir -p "${JARVIS_HOME}/logs"
{
    echo "=========================================="
    echo "Jarvis 2.0 ${INSTALL_TYPE^}"
    echo "Date: $(date -Is)"
    echo "Version: $VERSION"
    if [[ "$INSTALL_TYPE" == "upgrade" ]]; then
        echo "Previous version: $OLD_VERSION"
    fi
    echo "Install location: ${JARVIS_APP}"
    echo "User: $(whoami)"
    echo "=========================================="
} >> "${INSTALL_LOG}" 2>&1

# Stage 15: Desktop entry install/cleanup (install-only, idempotent)
install_desktop_entry() {
    local desktop_dir="${JARVIS_DESKTOP}"
    local desktop_file="${desktop_dir}/jarvis.desktop"
    local icon_path="${JARVIS_APP}/assets/icons/jarvis.png"
    local logs_dir="${JARVIS_HOME}/logs"
    local system_db_update=false
    local removed_any=false
    local updated_any=false
    local -a user_dirs=(
        "${HOME}/.local/share/applications"
        "${HOME}/.config/autostart"
        "${HOME}/.local/share/flatpak/exports/share/applications"
    )
    local -a system_dirs=(
        "/usr/share/applications"
        "/usr/local/share/applications"
        "/var/lib/snapd/desktop/applications"
        "/var/lib/flatpak/exports/share/applications"
    )

    desktop_log() {
        echo "  $*"
        echo "$*" >> "${INSTALL_LOG}" 2>/dev/null || true
    }

    desktop_entry_content() {
        cat <<EOF
[Desktop Entry]
Type=Application
Name=Jarvis
Comment=Jarvis launcher (kubernetes-first stack)
Exec=${JARVIS_APP}/bin/jarvis-launcher.sh
Icon=${icon_path}
Path=${JARVIS_APP}
Terminal=false
Categories=Utility;AI;
Keywords=Jarvis;AI;Launcher;
StartupNotify=true
StartupWMClass=jarvis-launcher

Actions=Start;Stop;Logs;Diagnostics;

[Desktop Action Start]
Name=Start Jarvis
Exec=${JARVIS_APP}/bin/jarvis-launcher.sh
Icon=${icon_path}
Terminal=false

[Desktop Action Stop]
Name=Stop Jarvis
Exec=${JARVIS_APP}/bin/jarvis-stop.sh
Icon=${icon_path}
Terminal=false

[Desktop Action Logs]
Name=View Logs
Exec=xdg-open ${logs_dir}
Icon=${icon_path}
Terminal=false

[Desktop Action Diagnostics]
Name=Diagnostics
Exec=${JARVIS_APP}/bin/jarvis-diagnostics.sh
Icon=${icon_path}
Terminal=false
EOF
    }

    local pkexec_available=false
    if command -v pkexec >/dev/null 2>&1; then
        pkexec_available=true
    fi

    mkdir -p "${desktop_dir}"

    desktop_log "Desktop cleanup: scanning for legacy entries..."
    for dir in "${user_dirs[@]}"; do
        if [[ -d "${dir}" ]]; then
            while IFS= read -r -d '' candidate; do
                if [[ "${candidate}" == "${desktop_file}" ]]; then
                    continue
                fi
                rm -f "${candidate}" 2>/dev/null || true
                desktop_log "Removed user entry: ${candidate}"
                removed_any=true
            done < <(find "${dir}" -maxdepth 1 -type f -iname "*jarvis*.desktop" -print0 2>/dev/null || true)
        fi
    done

    local -a system_candidates=()
    for dir in "${system_dirs[@]}"; do
        if [[ -d "${dir}" ]]; then
            while IFS= read -r -d '' candidate; do
                system_candidates+=("${candidate}")
            done < <(find "${dir}" -maxdepth 1 -type f -iname "*jarvis*.desktop" -print0 2>/dev/null || true)
        fi
    done

    if (( ${#system_candidates[@]} > 0 )); then
        if [[ "${EUID}" -eq 0 ]]; then
            for candidate in "${system_candidates[@]}"; do
                rm -f "${candidate}" 2>/dev/null || true
                desktop_log "Removed system entry: ${candidate}"
                removed_any=true
                system_db_update=true
            done
        elif [[ "${pkexec_available}" == "true" ]]; then
            if pkexec /usr/bin/env rm -f -- "${system_candidates[@]}" >/dev/null 2>&1; then
                for candidate in "${system_candidates[@]}"; do
                    desktop_log "Removed system entry: ${candidate}"
                    removed_any=true
                    system_db_update=true
                done
            else
                for candidate in "${system_candidates[@]}"; do
                    desktop_log "Skipped system entry (pkexec canceled or failed): ${candidate}"
                done
            fi
        else
            for candidate in "${system_candidates[@]}"; do
                desktop_log "Skipped system entry (pkexec unavailable): ${candidate}"
            done
        fi
    fi

    if [[ -f "${desktop_file}" ]]; then
        if command -v sha256sum >/dev/null 2>&1; then
            local expected_checksum existing_checksum
            expected_checksum="$(desktop_entry_content | sha256sum | awk '{print $1}')"
            existing_checksum="$(sha256sum "${desktop_file}" | awk '{print $1}')"
            if [[ "${expected_checksum}" != "${existing_checksum}" ]]; then
                desktop_entry_content > "${desktop_file}"
                chmod 644 "${desktop_file}"
                desktop_log "Updated desktop entry: ${desktop_file}"
                updated_any=true
            else
                desktop_log "Desktop entry up-to-date: ${desktop_file}"
            fi
        else
            if command -v mktemp >/dev/null 2>&1 && command -v cmp >/dev/null 2>&1; then
                local tmp_file
                tmp_file="$(mktemp)"
                desktop_entry_content > "${tmp_file}"
                if cmp -s "${tmp_file}" "${desktop_file}"; then
                    desktop_log "Desktop entry up-to-date: ${desktop_file}"
                    rm -f "${tmp_file}"
                else
                    mv "${tmp_file}" "${desktop_file}"
                    chmod 644 "${desktop_file}"
                    desktop_log "Updated desktop entry: ${desktop_file}"
                    updated_any=true
                fi
            else
                desktop_log "Desktop entry exists (checksum skipped): ${desktop_file}"
            fi
        fi
    else
        desktop_entry_content > "${desktop_file}"
        chmod 644 "${desktop_file}"
        desktop_log "Created desktop entry: ${desktop_file}"
        updated_any=true
    fi

    if [[ "${removed_any}" == "true" || "${updated_any}" == "true" ]]; then
        if command -v update-desktop-database >/dev/null 2>&1; then
            update-desktop-database "${desktop_dir}" 2>/dev/null || true
            desktop_log "Updated desktop database: ${desktop_dir}"
            if [[ "${system_db_update}" == "true" ]]; then
                if [[ "${EUID}" -eq 0 ]]; then
                    update-desktop-database /usr/share/applications 2>/dev/null || true
                    update-desktop-database /usr/local/share/applications 2>/dev/null || true
                elif command -v pkexec >/dev/null 2>&1; then
                    pkexec /usr/bin/env update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
                    pkexec /usr/bin/env update-desktop-database /usr/local/share/applications >/dev/null 2>&1 || true
                else
                    desktop_log "Skipped system desktop database update (pkexec unavailable)"
                fi
            fi
        fi

        if command -v gtk-update-icon-cache >/dev/null 2>&1; then
            if [[ -d "${HOME}/.local/share/icons/hicolor" ]]; then
                gtk-update-icon-cache -f -t "${HOME}/.local/share/icons/hicolor" >/dev/null 2>&1 || true
            fi
            if [[ -d "${HOME}/.icons/hicolor" ]]; then
                gtk-update-icon-cache -f -t "${HOME}/.icons/hicolor" >/dev/null 2>&1 || true
            fi
            if [[ "${system_db_update}" == "true" && -d "/usr/share/icons/hicolor" ]]; then
                if [[ "${EUID}" -eq 0 ]]; then
                    gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true
                elif command -v pkexec >/dev/null 2>&1; then
                    pkexec /usr/bin/env gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true
                fi
            fi
        fi

        if command -v xdg-desktop-menu >/dev/null 2>&1; then
            xdg-desktop-menu forceupdate >/dev/null 2>&1 || true
        fi
    fi
}

install_desktop_entry

echo ""
echo "=========================================="
echo "Installation completed!"
echo "=========================================="
echo ""
echo "Jarvis 2.0 is now installed in: ${JARVIS_APP}"
echo ""
echo "You can now:"
echo "  1. Find 'Jarvis' in your application menu"
echo "  2. Click to launch"
echo ""
echo "To uninstall, remove: ${JARVIS_APP}"
echo ""
