#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Release Build Script
# =============================================================================
# Builds a release artifact (tar.gz) containing:
# - launcher.jar
# - unified desktop shell JAR
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

# Locate unified desktop shell JAR
DESKTOP_APP_JAR="${REPO_ROOT}/apps/desktop-app-javafx/target/desktop-app-javafx-${VERSION}.jar"
if [[ ! -f "$DESKTOP_APP_JAR" ]]; then
    echo "ERROR: Desktop shell JAR not found: $DESKTOP_APP_JAR"
    echo "Please build it first:"
    echo "  mvn -pl apps/desktop-app-javafx -am -DskipTests clean package"
    exit 1
fi
echo "  ✅ Desktop shell JAR found: $DESKTOP_APP_JAR"

# Copy JARs
echo "Copying JARs..."
cp "$LAUNCHER_JAR" "${RELEASE_DIR}/launcher.jar"
cp "$DESKTOP_APP_JAR" "${RELEASE_DIR}/desktop-app-javafx-${VERSION}.jar"
echo "  ✅ JARs copied"

# Copy core launch scripts
echo "Copying core launch scripts..."
cp "${REPO_ROOT}/jarvis-launch.sh" "${RELEASE_DIR}/"
cp "${REPO_ROOT}/jarvis-stop.sh" "${RELEASE_DIR}/"
cp "${REPO_ROOT}/jarvis-logs.sh" "${RELEASE_DIR}/"
chmod +x "${RELEASE_DIR}/jarvis-launch.sh" "${RELEASE_DIR}/jarvis-stop.sh" "${RELEASE_DIR}/jarvis-logs.sh"
echo "  ✅ Core scripts copied"

# Copy install scripts
echo "Copying install scripts..."
mkdir -p "${RELEASE_DIR}/bin"
cp "${SCRIPT_DIR}/jarvis-launcher.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-stop.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-diagnostics.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-generate-certs.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-install-tls.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-setup-hosts.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-secrets-apply.sh" "${RELEASE_DIR}/bin/"
cp "${SCRIPT_DIR}/jarvis-system-setup.sh" "${RELEASE_DIR}/bin/"
cp "${REPO_ROOT}/scripts/verify-prod.sh" "${RELEASE_DIR}/bin/"
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
fi
echo "  Desktop entry: ${JARVIS_DESKTOP}/jarvis.desktop"
echo "  Install log: ${INSTALL_LOG}"
echo ""

# Copy files
echo "Installing files..."
cp "${RELEASE_DIR}/launcher.jar" "${JARVIS_APP}/"
if [[ -f "${RELEASE_DIR}/desktop-app-javafx-${VERSION}.jar" ]]; then
    cp "${RELEASE_DIR}/desktop-app-javafx-${VERSION}.jar" "${JARVIS_APP}/"
fi
rm -f "${JARVIS_APP}"/desktop-client-javafx-*.jar 2>/dev/null || true
cp "${RELEASE_DIR}/jarvis-launch.sh" "${JARVIS_APP}/"
cp "${RELEASE_DIR}/jarvis-stop.sh" "${JARVIS_APP}/"
cp "${RELEASE_DIR}/jarvis-logs.sh" "${JARVIS_APP}/"
chmod +x "${JARVIS_APP}/jarvis-launch.sh" "${JARVIS_APP}/jarvis-stop.sh" "${JARVIS_APP}/jarvis-logs.sh"
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

# Log installation (detailed log)
{
    echo "=========================================="
    echo "Jarvis 2.0 ${INSTALL_TYPE^}"
    echo "Date: $(date -Is)"
    echo "Version: $VERSION"
    if [[ "$INSTALL_TYPE" == "upgrade" ]]; then
        echo "Previous version: $OLD_VERSION"
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
fi
echo "  Desktop entry: ${JARVIS_DESKTOP}/jarvis.desktop"
echo "  Install log: ${INSTALL_LOG}"
echo ""
echo "You can now:"
echo "  1. Find 'Jarvis' in your application menu"
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
- \`desktop-app-javafx-${VERSION}.jar\` - Unified desktop shell
- \`bin/\` - Scripts (jarvis-launcher.sh, jarvis-stop.sh, jarvis-diagnostics.sh)
- \`config/\` - Configuration files
- \`docs/\` - Documentation

## Upgrade

The install script automatically:
- Detects existing installation
- Installs new version
- Creates \`~/.local/share/applications/jarvis.desktop\` once (idempotent)

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
