# Porcupine Wake Word Setup

`desktop-client-javafx` is an internal build module. The supported desktop runtime path is `launcher-javafx` / `desktop-app-javafx`.

## 1. Create Wake Word Model "Джарвис"

### Steps:

1. **Go to Picovoice Console**: https://console.picovoice.ai

2. **Login/Register** with your account

3. **Navigate to "Porcupine" → "Wake Words"**

4. **Create New Wake Word**:
   - Click "+ Create Wake Word"
   - Name: `jarvis_ru` or `dzharvis`
   - Language: **Russian (ru)**
   - Phrases to train:
     - "Джарвис"
     - "Джарвис?"
     - "Эй Джарвис"
   
5. **Train the Model**:
   - Click "Train"
   - Wait for training to complete (~5-10 minutes)
   
6. **Download Model**:
   - Download the `.ppn` file
   - Rename to: `jarvis_ru.ppn`
   - Save to: `apps/desktop-client-javafx/src/main/resources/models/`
   - This repo-local classpath location is consumed by `desktop-app-javafx` through the internal `desktop-client-javafx` dependency
   - Keep the `.ppn` major version aligned with `ai.picovoice:porcupine-java`

---

## 2. Setup Environment Variable

### Linux/macOS:

```bash
# Add to ~/.bashrc or ~/.zshrc
export PORCUPINE_ACCESS_KEY="your-picovoice-access-key"

# Apply
source ~/.bashrc
```

### Windows:

```cmd
# PowerShell
$env:PORCUPINE_ACCESS_KEY = "your-picovoice-access-key"

# Or System Environment Variables:
# Settings → System → Advanced → Environment Variables
# Add: PORCUPINE_ACCESS_KEY = your-picovoice-access-key
```

### IntelliJ IDEA Run Configuration:

1. Run → Edit Configurations
2. Select your `desktop-app-javafx` or `launcher-javafx` configuration
3. Environment variables: `PORCUPINE_ACCESS_KEY=your-picovoice-access-key`

---

## 3. Place Model File

```bash
mkdir -p apps/desktop-client-javafx/src/main/resources/models
# Move downloaded jarvis_ru.ppn to this folder
# If the repo already contains jarvis_ru.ppn, replace it only when you intentionally retrain the wake word
```

---

## 4. Run Application

```bash
# Set env var
export PORCUPINE_ACCESS_KEY="your-picovoice-access-key"

# Supported desktop path
mvn clean compile -pl apps/desktop-app-javafx -am javafx:run
```

---

## Alternative: Built-in "Jarvis" (English)

If you want to test immediately with built-in keyword:

```kotlin
// Use Porcupine's built-in "jarvis" keyword
val porcupine = Porcupine.Builder()
    .setAccessKey(accessKey)
    .setBuiltInKeyword(BuiltInKeyword.JARVIS)
    .build()
```

No need to train or download - works immediately!
