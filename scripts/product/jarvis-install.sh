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
    
    LAUNCHER_JAR="${RELEASE_DIR}/launcher.jar"
    if [[ ! -f "$LAUNCHER_JAR" ]]; then
        echo "ERROR: launcher.jar not found in release directory: $RELEASE_DIR"
        exit 1
    fi
    
    VERSION=$(cat "${RELEASE_DIR}/VERSION" 2>/dev/null || echo "unknown")
    echo "Installing version: $VERSION"
else
    # Original repo-based install
    LAUNCHER_JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
    if [[ ! -f "$LAUNCHER_JAR" ]]; then
        echo "ERROR: Launcher JAR not found: $LAUNCHER_JAR"
        echo "Please build launcher first:"
        echo "  mvn -pl apps/launcher-javafx -DskipTests clean package"
        exit 1
    fi
fi

# Copy launcher JAR
echo "Installing launcher JAR..."
cp "$LAUNCHER_JAR" "${JARVIS_APP}/launcher.jar"
echo "  ✅ ${JARVIS_APP}/launcher.jar"

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
    cp "${SCRIPT_DIR}/jarvis-launcher.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-stop.sh" "${JARVIS_BIN}/"
    cp "${SCRIPT_DIR}/jarvis-diagnostics.sh" "${JARVIS_BIN}/"
    chmod +x "${JARVIS_BIN}"/*.sh
    echo "  ✅ ${JARVIS_BIN}/jarvis-launcher.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-stop.sh"
    echo "  ✅ ${JARVIS_BIN}/jarvis-diagnostics.sh"
    
    # Copy logback config (for launcher logging)
    if [[ -f "${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
        mkdir -p "${JARVIS_APP}/config"
        cp "${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" "${JARVIS_APP}/config/"
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
        echo "  ${YELLOW}⚠️${NC}  Icon not found, desktop entry will use default icon"
        WARNINGS=$((WARNINGS + 1))
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
        
        # Create backup
        BACKUP_DIR="${JARVIS_APP}/backup/${OLD_VERSION}"
        mkdir -p "${BACKUP_DIR}"
        echo "  Creating backup in ${BACKUP_DIR}..."
        
        # Backup existing files
        if [[ -f "${JARVIS_APP}/launcher.jar" ]]; then
            cp "${JARVIS_APP}/launcher.jar" "${BACKUP_DIR}/" 2>/dev/null || true
        fi
        if [[ -d "${JARVIS_APP}/bin" ]]; then
            cp -r "${JARVIS_APP}/bin" "${BACKUP_DIR}/" 2>/dev/null || true
        fi
        if [[ -d "${JARVIS_APP}/config" ]]; then
            cp -r "${JARVIS_APP}/config" "${BACKUP_DIR}/" 2>/dev/null || true
        fi
        if [[ -f "${JARVIS_APP}/VERSION" ]]; then
            cp "${JARVIS_APP}/VERSION" "${BACKUP_DIR}/" 2>/dev/null || true
        fi
        echo "  ✅ Backup created: ${BACKUP_DIR}"
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
    # For repo-based install, write "REPO" marker
    echo "REPO" > "${JARVIS_APP}/RELEASE_SOURCE"
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
        echo "Backup location: ${JARVIS_APP}/backup/${OLD_VERSION}"
    fi
    echo "Install location: ${JARVIS_APP}"
    echo "User: $(whoami)"
    echo "=========================================="
} >> "${INSTALL_LOG}" 2>&1

# Stage 15: Create desktop file with polished metadata
DESKTOP_FILE="${JARVIS_DESKTOP}/jarvis-launcher.desktop"
ICON_PATH="\$HOME/.jarvis/app/assets/icons/jarvis.png"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=Jarvis 2.0
Comment=Local AI launcher for Jarvis stack
Exec=/usr/bin/env bash -lc "\$HOME/.jarvis/app/bin/jarvis-launcher.sh"
Icon=${ICON_PATH}
Path=${JARVIS_APP}
Terminal=false
Categories=Utility;Development;
Keywords=Jarvis;AI;Launcher;
StartupNotify=true

Actions=Start;Stop;Logs;Diagnostics;

[Desktop Action Start]
Name=Start Jarvis
Exec=/usr/bin/env bash -lc "\$HOME/.jarvis/app/bin/jarvis-launcher.sh"
Icon=${ICON_PATH}
Terminal=false

[Desktop Action Stop]
Name=Stop Jarvis
Exec=/usr/bin/env bash -lc "\$HOME/.jarvis/app/bin/jarvis-stop.sh"
Icon=${ICON_PATH}
Terminal=false

[Desktop Action Logs]
Name=View Logs
Exec=/usr/bin/env bash -lc "xdg-open \$HOME/.jarvis/logs"
Icon=${ICON_PATH}
Terminal=false

[Desktop Action Diagnostics]
Name=Diagnostics
Exec=/usr/bin/env bash -lc "\$HOME/.jarvis/app/bin/jarvis-diagnostics.sh"
Icon=${ICON_PATH}
Terminal=false
EOF

chmod +x "$DESKTOP_FILE"
echo "  ✅ ${DESKTOP_FILE}"

# Update desktop database
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${JARVIS_DESKTOP}" 2>/dev/null || true
    echo "  ✅ Desktop database updated"
fi

echo ""
echo "=========================================="
echo "Installation completed!"
echo "=========================================="
echo ""
echo "Jarvis 2.0 is now installed in: ${JARVIS_APP}"
echo ""
echo "You can now:"
echo "  1. Find 'Jarvis 2.0' in your application menu"
echo "  2. Click to launch"
echo ""
echo "To uninstall, remove: ${JARVIS_APP}"
echo ""

