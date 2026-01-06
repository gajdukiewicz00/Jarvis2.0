#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Release Build Script
# =============================================================================
# Builds a release artifact (tar.gz) containing:
# - launcher.jar
# - desktop-client JAR
# - install scripts
# - desktop file template
# - documentation
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Get version from root pom.xml (jarvis-root, not parent Spring Boot)
VERSION=$(grep -A1 "<artifactId>jarvis-root</artifactId>" "${REPO_ROOT}/pom.xml" | grep "<version>" | head -1 | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/' | tr -d ' ' || echo "0.1.0-SNAPSHOT")

RELEASE_NAME="jarvis-release-${VERSION}"
RELEASE_DIR="${REPO_ROOT}/target/release/${RELEASE_NAME}"
RELEASE_ARCHIVE="${REPO_ROOT}/target/release/${RELEASE_NAME}.tar.gz"

echo "=========================================="
echo "Jarvis 2.0 - Release Build"
echo "=========================================="
echo ""
echo "Version: $VERSION"
echo "Release directory: ${RELEASE_DIR}"
echo "Release archive: ${RELEASE_ARCHIVE}"
echo ""

# Clean previous release
if [[ -d "${RELEASE_DIR}" ]]; then
    echo "Cleaning previous release..."
    rm -rf "${RELEASE_DIR}"
fi
mkdir -p "${RELEASE_DIR}"

# NOTE: We assume JARs are already built via Maven (developer run).
# This script will fail fast with a clear message if they are missing.

# Locate launcher JAR
cd "${REPO_ROOT}"
LAUNCHER_JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-${VERSION}.jar"
if [[ ! -f "$LAUNCHER_JAR" ]]; then
    echo "ERROR: Launcher JAR not found: $LAUNCHER_JAR"
    echo "Please build it first:"
    echo "  mvn -pl apps/launcher-javafx -DskipTests clean package"
    exit 1
fi
echo "  ✅ Launcher JAR found: $LAUNCHER_JAR"

# Locate desktop-client JAR
DESKTOP_JAR="${REPO_ROOT}/apps/desktop-client-javafx/target/desktop-client-javafx-${VERSION}.jar"
if [[ ! -f "$DESKTOP_JAR" ]]; then
    echo "ERROR: Desktop client JAR not found: $DESKTOP_JAR"
    echo "Please build it first:"
    echo "  mvn -pl apps/desktop-client-javafx -DskipTests clean package"
    exit 1
fi
echo "  ✅ Desktop client JAR found: $DESKTOP_JAR"

# Copy JARs
echo "Copying JARs..."
cp "$LAUNCHER_JAR" "${RELEASE_DIR}/launcher.jar"
cp "$DESKTOP_JAR" "${RELEASE_DIR}/desktop-client-javafx-${VERSION}.jar"
echo "  ✅ JARs copied"

# Copy install scripts
echo "Copying install scripts..."
mkdir -p "${RELEASE_DIR}/bin"
cp "${SCRIPT_DIR}/jarvis-launcher.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-stop.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-diagnostics.sh" "${RELEASE_DIR}/bin/"
chmod +x "${RELEASE_DIR}/bin"/*.sh
echo "  ✅ Install scripts copied"

# Copy config
echo "Copying config..."
mkdir -p "${RELEASE_DIR}/config"
if [[ -f "${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
    cp "${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" "${RELEASE_DIR}/config/"
    echo "  ✅ Config copied"
fi

# Stage 15: Copy icons
echo "Copying icons..."
mkdir -p "${RELEASE_DIR}/assets/icons"
if [[ -f "${REPO_ROOT}/assets/icons/jarvis.png" ]]; then
    cp "${REPO_ROOT}/assets/icons/jarvis.png" "${RELEASE_DIR}/assets/icons/"
    echo "  ✅ Icon copied (jarvis.png)"
elif [[ -f "${REPO_ROOT}/icons/jarvis-icon.png" ]]; then
    # Fallback to legacy icon location
    cp "${REPO_ROOT}/icons/jarvis-icon.png" "${RELEASE_DIR}/assets/icons/jarvis.png"
    echo "  ✅ Icon copied (from legacy location)"
else
    echo "  ${YELLOW}⚠️${NC}  Icon not found, release will have default icon"
fi
if [[ -f "${REPO_ROOT}/assets/icons/jarvis.svg" ]]; then
    cp "${REPO_ROOT}/assets/icons/jarvis.svg" "${RELEASE_DIR}/assets/icons/"
    echo "  ✅ Icon copied (jarvis.svg)"
fi

# Create install.sh wrapper (simplified, calls jarvis-install.sh logic)
echo "Creating install.sh wrapper..."
cat > "${RELEASE_DIR}/install.sh" <<'INSTALL_EOF'
#!/usr/bin/env bash
# Jarvis 2.0 - Self-Contained Installer
# This script installs Jarvis from a release archive
# Stage 11: Includes integrity checks (SHA256SUMS)

set -euo pipefail

RELEASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="${HOME}/.jarvis"
JARVIS_APP="${JARVIS_HOME}/app"
JARVIS_BIN="${JARVIS_APP}/bin"
JARVIS_DESKTOP="${HOME}/.local/share/applications"
INSTALL_LOG="${JARVIS_HOME}/logs/install.log"

echo "=========================================="
echo "Jarvis 2.0 - Installation"
echo "=========================================="
echo ""

# Ensure logs directory exists
mkdir -p "${JARVIS_HOME}/logs"

# Stage 12: Strict integrity check (FAIL on mismatch if SHA256SUMS exists)
SHA256SUMS_FILE="${RELEASE_DIR}/SHA256SUMS"
if [[ -f "$SHA256SUMS_FILE" ]]; then
    echo "Verifying release integrity..."
    if command -v sha256sum >/dev/null 2>&1; then
        cd "${RELEASE_DIR}"
        # Stage 12: Check only files that exist in release folder (launcher.jar, install.sh)
        # Archive checksum is for external verification, skip it here
        if sha256sum -c SHA256SUMS 2>&1 | grep -v "\.tar\.gz" | grep -q "FAILED\|WARNING"; then
            echo "  ❌ Integrity check FAILED (checksums do not match)"
            echo "  ❌ Installation aborted for security reasons"
            echo "  Action: Re-download release archive or verify file integrity"
            exit 1
        elif sha256sum -c SHA256SUMS >/dev/null 2>&1; then
            echo "  ✅ Integrity check passed"
        else
            # Check if only archive check failed (acceptable, archive is external)
            CHECK_OUTPUT=$(sha256sum -c SHA256SUMS 2>&1 || true)
            if echo "$CHECK_OUTPUT" | grep -q "\.tar\.gz.*FAILED" && ! echo "$CHECK_OUTPUT" | grep -qE "(launcher\.jar|install\.sh).*FAILED"; then
                echo "  ✅ Integrity check passed (release files OK, archive check skipped)"
            else
                echo "  ❌ Integrity check FAILED (checksums do not match)"
                echo "  ❌ Installation aborted for security reasons"
                echo "  Action: Re-download release archive or verify file integrity"
                exit 1
            fi
        fi
    else
        echo "  ⚠️  sha256sum not found, skipping integrity check"
        echo "  ⚠️  Continuing installation without integrity verification"
    fi
else
    echo "  ⚠️  SHA256SUMS not found, skipping integrity check"
    echo "  ⚠️  Continuing installation without integrity verification"
fi

# Create directories
mkdir -p "${JARVIS_APP}"
mkdir -p "${JARVIS_BIN}"
mkdir -p "${JARVIS_DESKTOP}"
mkdir -p "${JARVIS_APP}/assets/icons"  # Stage 15: Icons directory

# Get version from VERSION file
VERSION=$(cat "${RELEASE_DIR}/VERSION" 2>/dev/null || echo "unknown")

# Check if upgrade
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
        echo "  ✅ Backup created"
    fi
fi

# Stage 12: Human-readable output
echo ""
echo "Installation Details:"
echo "  Release version: $VERSION"
echo "  Install path: ${JARVIS_APP}"
echo "  Install type: ${INSTALL_TYPE}"
if [[ "$INSTALL_TYPE" == "upgrade" ]]; then
    echo "  Previous version: $OLD_VERSION"
    echo "  Backup path: ${JARVIS_APP}/backup/${OLD_VERSION}"
else
    echo "  Backup path: none (fresh install)"
fi
echo "  Desktop entry: ${JARVIS_DESKTOP}/jarvis-launcher.desktop"
echo "  Install log: ${INSTALL_LOG}"
echo ""

# Copy files
echo "Installing files..."
cp "${RELEASE_DIR}/launcher.jar" "${JARVIS_APP}/"
cp -r "${RELEASE_DIR}/bin"/* "${JARVIS_BIN}/"
if [[ -d "${RELEASE_DIR}/config" ]]; then
    cp -r "${RELEASE_DIR}/config" "${JARVIS_APP}/"
fi
# Stage 15: Copy icons
if [[ -d "${RELEASE_DIR}/assets/icons" ]]; then
    cp -r "${RELEASE_DIR}/assets/icons"/* "${JARVIS_APP}/assets/icons/"
    echo "  ✅ Icons installed"
fi
cp "${RELEASE_DIR}/VERSION" "${JARVIS_APP}/"

# Stage 12: Write RELEASE_SOURCE
echo "${RELEASE_DIR}" > "${JARVIS_APP}/RELEASE_SOURCE"
echo "  ✅ Release source recorded: ${JARVIS_APP}/RELEASE_SOURCE"

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

# Update desktop database
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${JARVIS_DESKTOP}" 2>/dev/null || true
fi

# Log installation (detailed log)
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
    echo "Release directory: ${RELEASE_DIR}"
    echo "User: $(whoami)"
    if [[ -f "${RELEASE_DIR}/SHA256SUMS" ]]; then
        echo "Integrity check: SHA256SUMS found"
    else
        echo "Integrity check: SHA256SUMS missing"
    fi
    echo "=========================================="
} >> "${INSTALL_LOG}" 2>&1

echo ""
echo "=========================================="
echo "Installation completed!"
echo "=========================================="
echo ""
echo "Jarvis 2.0 version $VERSION is now installed in: ${JARVIS_APP}"
echo ""
echo "Installation Summary:"
echo "  Version: $VERSION"
echo "  Install path: ${JARVIS_APP}"
echo "  Install type: ${INSTALL_TYPE}"
if [[ "$INSTALL_TYPE" == "upgrade" ]]; then
    echo "  Previous version: $OLD_VERSION"
    echo "  Backup location: ${JARVIS_APP}/backup/${OLD_VERSION}"
fi
echo "  Desktop entry: ${JARVIS_DESKTOP}/jarvis-launcher.desktop"
echo "  Install log: ${INSTALL_LOG}"
echo ""
echo "You can now:"
echo "  1. Find 'Jarvis 2.0' in your application menu"
echo "  2. Click to launch"
echo ""
INSTALL_EOF

chmod +x "${RELEASE_DIR}/install.sh"
echo "  ✅ install.sh wrapper created"

# Create VERSION file
echo "$VERSION" > "${RELEASE_DIR}/VERSION"
echo "  ✅ VERSION file created"

# Copy documentation
echo "Copying documentation..."
mkdir -p "${RELEASE_DIR}/docs"
if [[ -f "${REPO_ROOT}/docs/ITERATION_1.5_STAGE8_ACCEPTANCE.md" ]]; then
    cp "${REPO_ROOT}/docs/ITERATION_1.5_STAGE8_ACCEPTANCE.md" "${RELEASE_DIR}/docs/ACCEPTANCE.md"
fi
if [[ -f "${REPO_ROOT}/README.md" ]]; then
    cp "${REPO_ROOT}/README.md" "${RELEASE_DIR}/docs/"
fi
echo "  ✅ Documentation copied"

# Create README for release
cat > "${RELEASE_DIR}/README.md" <<EOF
# Jarvis 2.0 Release ${VERSION}

## Installation

Extract this archive and run:

\`\`\`bash
cd jarvis-release-${VERSION}
./install.sh
\`\`\`

Or use the install script from the repository:

\`\`\`bash
./scripts/product/jarvis-install.sh
\`\`\`

## Contents

- \`launcher.jar\` - Launcher application
- \`desktop-client-javafx-${VERSION}.jar\` - Desktop client
- \`bin/\` - Scripts (jarvis-launcher.sh, jarvis-stop.sh, jarvis-diagnostics.sh)
- \`config/\` - Configuration files
- \`docs/\` - Documentation

## Upgrade

The install script automatically:
- Detects existing installation
- Creates backup in \`~/.jarvis/app/backup/<old-version>/\`
- Installs new version
- Updates desktop file

## Uninstall

Remove \`~/.jarvis/app/\` directory.

EOF
echo "  ✅ README.md created"

# Create tar.gz archive
echo "Creating release archive..."
cd "${REPO_ROOT}/target/release"
tar -czf "${RELEASE_ARCHIVE}" "${RELEASE_NAME}"
echo "  ✅ Release archive created: ${RELEASE_ARCHIVE}"

# Stage 11: Generate SHA256SUMS for integrity checks
echo "Generating SHA256SUMS..."
SHA256SUMS_FILE="${REPO_ROOT}/target/release/SHA256SUMS"
cat > "${SHA256SUMS_FILE}" <<EOF
# Jarvis 2.0 Release ${VERSION} - SHA256 Checksums
# Generated: $(date -Is)
#
# Verify checksums:
#   sha256sum -c SHA256SUMS
#
EOF

# Calculate checksums (Stage 12: use relative paths inside release folder)
cd "${RELEASE_DIR}"
LAUNCHER_SHA256=$(sha256sum "launcher.jar" | cut -d' ' -f1)
INSTALL_SHA256=$(sha256sum "install.sh" | cut -d' ' -f1)
cd "${REPO_ROOT}/target/release"
ARCHIVE_SHA256=$(sha256sum "${RELEASE_NAME}.tar.gz" | cut -d' ' -f1)

# Write checksums (Stage 12: relative paths only, no absolute paths)
cat >> "${SHA256SUMS_FILE}" <<EOF
${LAUNCHER_SHA256}  launcher.jar
${INSTALL_SHA256}  install.sh
${ARCHIVE_SHA256}  ${RELEASE_NAME}.tar.gz
EOF

# Copy SHA256SUMS into release directory (for inclusion in archive)
cp "${SHA256SUMS_FILE}" "${RELEASE_DIR}/SHA256SUMS"

# Recreate archive with SHA256SUMS included
echo "Recreating archive with SHA256SUMS..."
tar -czf "${RELEASE_ARCHIVE}" "${RELEASE_NAME}"
echo "  ✅ Archive updated with SHA256SUMS"

echo "  ✅ SHA256SUMS generated: ${SHA256SUMS_FILE}"

# Show archive info
ARCHIVE_SIZE=$(du -h "${RELEASE_ARCHIVE}" | cut -f1)
echo ""
echo "=========================================="
echo "Release build completed!"
echo "=========================================="
echo ""
echo "Release archive: ${RELEASE_ARCHIVE}"
echo "Size: ${ARCHIVE_SIZE}"
echo "SHA256SUMS: ${SHA256SUMS_FILE}"
echo ""
echo "To install from release:"
echo "  tar -xzf ${RELEASE_ARCHIVE}"
echo "  cd ${RELEASE_NAME}"
echo "  ./install.sh"
echo ""
echo "To verify integrity:"
echo "  cd ${RELEASE_NAME}"
echo "  sha256sum -c SHA256SUMS"
echo ""

