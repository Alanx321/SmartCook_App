[Report 2](Report2.md)  
[Install App Using APK](smartcook/app/release/app-release.apk)

# SmartCook 🍳

SmartCook is an intelligent Android cooking assistant built with **Jetpack Compose**. Unlike standard recipe apps, SmartCook focuses on the *process* of cooking, offering advanced features like a Multi-Cook Coordinator that schedules multiple recipes to finish at the same time, persistent session recovery after phone reboots, and integrated step-by-step timers.

## ✨ Key Features

### 🚀 Core Functionality
* **Recipe Management:** Create, edit, and delete recipes with specific ingredients and instructions.
* **Step-by-Step Guidance:** Interactive cooking mode with timed and non-timed (prep) steps.
* **Integrated Timers:** Built-in countdowns for specific steps that run in the background.
* **Cooking History:** Logs completed sessions with duration and date.
* **Favorites & Search:** Quickly find your go-to meals using the search bar or favorites tab.

### 🧠 Smart Features
* **🔥 Multi-Cook Coordinator:** Select up to 3 recipes, and the app generates a unified timeline. It calculates prep times, critical cooking paths, and buffers to ensure all dishes are ready simultaneously.
* **🛡️ Robust Session Recovery:** If your phone runs out of battery or crashes during a cook, `BootReceiver` and `SessionManager` automatically restore your exact step and timer state upon reboot.
* **🔔 Intelligent Notifications:** Foreground services keep you updated on timer status even when the app is closed. Multi-cook mode groups notifications to prevent clutter.
* **🌓 Dark Mode & Customization:** Fully themed support for Light/Dark modes and toggleable sound/haptics.

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI Toolkit:** Jetpack Compose (Material3)
* **Architecture:** MVVM (Model-View-ViewModel)
* **Local Storage:** Room Database (SQLite)
* **Preferences:** Jetpack DataStore
* **Concurrency:** Coroutines & Flow
* **Background Work:** Foreground Services & Broadcast Receivers

## 📸 Screenshots

| Home Screen | Recipe Detail | Single-Cook Mode | Multi-Cook Mode |
|:-----------:|:-------------:|:------------:|:-------------------:|
| ![Home Screen](images/Screenshot%202025-12-08%20124236.png) | ![Recipe Detail](images/Screenshot%202025-12-08%20124318.png) | ![Single-Cook Mode](images/Screenshot%202025-12-08%20124351.png) | ![Multi-Cook Mode](images/Screenshot%202025-12-08%20124415.png) |

<br>

| Multi-Cook Timeline | Select Recipe (Multi Mode) | Cooking History | Setting(Dark Mode) |
|:-----------:|:-------------:|:------------:|:-------------------:|
| ![Multi-Cook Timeline](images/Screenshot%202025-12-08%20124433.png) | ![Select Recipe](images/Screenshot%202025-12-08%20124449.png) | ![Cooking History](images/Screenshot%202025-12-08%20125452.png) | ![Setting Dark Mode](images/Screenshot%202025-12-08%20125558.png) |

## 🚀 Getting Started

### Prerequisites
* Android Studio Iguana or later.
* JDK 17 or higher.
* Android SDK API Level 26 (Oreo) minimum recommended (due to Notification Channels and Service logic).

### Installation
1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/yourusername/smartcook.git](https://github.com/yourusername/smartcook.git)
    ```
2.  **Open in Android Studio:**
    Select `File > Open` and navigate to the cloned directory.
3.  **Sync Gradle:**
    Allow Android Studio to download necessary dependencies.
4.  **Run:**
    Connect a physical device or start an emulator and click the green "Run" button.

## 🧠 Architectural Insights

### The Multi-Cook Coordinator
Located in `MultiCookCoordinator.kt`, this logic engine is the heart of the multi-recipe feature. It performs the following analysis:
1.  **Step Analysis:** Breaks recipes into "Critical Path" (timed cooking) and "Prep Time" (chopping/mixing).
2.  **Scheduling:** Identifies the recipe with the longest total duration.
3.  **Back-Calculation:** Calculates the `delayMinutes` for shorter recipes so that their `targetFinishTime` aligns perfectly with the longest recipe.
4.  **Action Prioritization:** A heuristic engine determines the "Next Action" (e.g., "Start Cooking Pasta" vs. "Chop Onions") based on urgency.

### Session Persistence & Recovery
The app uses a robust state machine (`SessionManager.kt` and `TimerService.kt`) to ensure no cooking progress is lost:
* **State Saving:** Every 5 seconds (and upon step changes), the current state (step index, timer remaining, etc.) is written to the Room Database.
* **Crash Handling:** If the app is killed, the `TimerService` can restore the timer state from the database.
* **Reboot Handling:** `BootReceiver.kt` listens for `android.intent.action.BOOT_COMPLETED`. It queries the database for interrupted sessions, recalculates the remaining time based on the timestamp difference, and posts a notification inviting the user to resume cooking.

## 📂 Project Structure

* `data/`: Room entities (`Recipe`, `CookingSession`), DAOs, and Repositories.
* `ui/theme/`: Compose theme definitions (Color, Type, Theme).
* `viewmodel/`: State management for UI screens.
* `session/`: Logic for persisting and recovering cooking states.
* `receiver/`: Broadcast receivers for system events (Boot).
* `SmartCookApplication.kt`: Hilt/Dependency injection entry point (or manual DI setup).
* `MainActivity.kt`: Navigation host and entry point.
