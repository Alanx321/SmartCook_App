# 1. Introduction
**SmartCook** is an innovative Android-based cooking assistant that aims to change the home cooking experience to one of an active and synchronised kitchen management rather than a passive one where one reads recipes. As opposed to the classical recipe applications that only act as a kind of digital cookbook, SmartCook is an intelligent process orchestrator and puts significant emphasis on the temporal and logistical considerations of meal preparation.

**The Problem**

The major difficulty with cooking at home- particularly among intermediate cooks- is the issue of synchronous timing. Arranging a complex meal in which protein is to be passively roasted, starch actively boiled, and vegetables sauteed is a heavy cognitive task. Also, the standard mobile timers do not work when dealing with high endurance tasks because Android has excessive battery optimisations (Doze mode) that may end background timers or fail to restore state altogether should the device reboot during a long cook.

**Target Audience & Objectives**

SmartCook will target multitasking home cooks who need accuracy and consistency. The key idea of the application is to resolve the issue of synchronisation with the help of its Multi-Cook Mode that aligns the recipes that do not match each other algorithmically to complete at the same time. Second, it focuses on absolute reliability by providing a powerful "Session Recovery" system whereby cooking timers and step progress are retained despite termination of the app or crashes, or even restart of the device.

<br>

# 2. Technical Design and Implementation Details
## 2.1 System Architecture Overview
SmartCook is designed to follow the Guide to App Architecture by Google, as the application is based on the `Model-View-ViewModel` (MVVM) architectural pattern. The structure provides a strict separation of concerns and decouples the User Interface (UI) from the data processing logic and making it testable.

**View Layer (UI):** The interface is built entirely with Jetpack Compose, the modern toolkit for building native UI in Android. It is also reactive and, therefore, other components, such as `MultiCookModeScreen` and `SmartCookHomePage`, automatically recompose when the state changes, as opposed to being manually manipulated through the UI.

**ViewModel Layer (State Holder):** The `RecipeViewModel` is the intermediary one. It contains the UI state (e.g. `allRecipes`, `favoriteRecipes`) and makes it available to the View by using observable data holders (LiveData and StateFlow). It is not lost when the configuration changes, so there is no loss of cooking information when the screen is rotated.

**Repository (Data):** The `RecipeRepository` is the single source of truth. It is the abstraction of the data sources: it handles the data flow of the local SQLite database through the Room Persistence Library (RecipeDatabase) and user preferences through Jetpack DataStore (SettingsDataStore).

**Service Layer:** Most importantly, the application has a special Service Layer called `TimerService`, which exists outside of the normal UI lifecycle. This makes sure that even when the application is minimised, or the screen is switched off, the fundamental domain logic timer countdowns and alarm triggering are still running.

## 2.2 Key Modules and Components
**A. The Multi-Cook Coordinator (Scheduling Algorithm)**

The recipe-synchronisation logic has been summarised in the `MultiCookCoordinator` singleton. The module deals with the issue of matching recipes to extremely disparate temporal structures.

- **Critical Path Analysis:** The `generateSchedule` function takes the recipes that have been selected and differentiates between Critical Path time (timed) and Prep Time (untimed) steps (such as boiling and chopping). It determines the recipe that has the greatest total length of duration to be used as the schedule anchor.

- **Dynamic Calculation of Delays:** The coordinator computes a `delayMinutes` offset for every other recipe. This produces a staggered start schedule such that all of the independent timers meet at the same target finish time.

- **Action Heuristics:** This determines that the `NextAction` function uses a priority-based state machine. It keeps all the active recipes under evaluation, ensuring that the most important task to the user is surfaced, with the most important being Critical alarming, followed by Start Needed alarming.

**B. High-Reliability Timer Service.**

The application makes use of Foreground Service (`TimerService`) to avoid the battery limitations of Android.

- **Wake Locks:** The service takes a `PowerManager.PARTIALWAKELOCK`, to ensure that the CPU is kept active on the countdown logic even when the screen is off. This avoids the timer drift that other ordinary apps experience.

- **Thread-Safe Concurrency:** SmartCook implements a `ConcurrentHashMap` where Timer Instance objects are stored in the service. This is because it can safely allow two or more timers to run simultaneously with each thread (UI thread vs. Service thread) without the threat of `ConcurrentModificationException`.

- **Notification Management:** The service delegates notification construction to a dedicated `TimerNotificationManager` helper class. This ensures strict compliance with Android's Notification Channel requirements and manages the 'High Priority' alarm interrupts for expired timers.

- **Bound Communication:** The application uses the `Bound Service` design. `CookingTimerState` wrapper enables Compose UI to connect to the running service and be updated on real-time using a callback interface (`onTimerUpdate`).

**C. Session Persistence and Recovery Engine.**

The `SessionManager` is a persistent state, which is stored as the CookingSessionEntity table in the Room database as a guarantee of data safety.

- **Continuous Snapshots:** The system takes a snapshot of the cooking session (current step, remaining time) whenever a step transition occurs and other times when active timers occur.

- **Reboot Resurrection:** This resets the machine by monitoring the BOOT completed broadcast. In case of a reboot, the `SessionManager` requests interrupted sessions.

- **Time catch-up logic:** The manager determines the time elapsed (`Current Time- Last Update Time`). It mathematically then fast-forwards the timers. When a timer runs out when the phone is switched off, it automatically rings an alarm; otherwise, it continues with the countdown at the adjusted time.

## 2.3 Design Patterns Used
- **Repository Pattern:** `RecipeRepository` provides an abstraction of the data layer, which means that the `ViewModel` is not tied to the chosen clean storage method (Room/DataStore).

- **Observer Pattern:** It is used through `Kotlin Flows` and `LiveData`. `RecipeViewModel` data streams are observed by the UI, such that when the database underlying the interface changes, the interface does not have to be called to refresh; it automatically updates.

- **Singleton Pattern:** `RecipeDatabase` is a `Singleton` with synchronised locking to ensure that there are not many costly concurrent database connections.

- **Factory Pattern:** `RecipeViewModelFactory` pattern is applied to inject dependencies (such as the Repository) into the `ViewModel` because standard `ViewModel` providers do not support construction injection.

- **State Machine Pattern:** The `MultiCookCoordinator` is a state machine that decides which of the following states to switch to start and end the UI suggestions, such as `WAITING`, `UPCOMING`, `START_NEEDED` and `CRITICAL`, based on the time elapsed before the next step.

## 2.4 Technical Diagrams
**Diagram 1: System Architecture (Class Diagram)**

This diagram illustrates the separation of layers and the specific classes used in your implementation, matching the MVVM structure described in 2.1.

<div align="center">
  <img src="images\2.1.2.png" alt="Figure 2.1" title="Figure 2.1">
  <p><strong>Figure 2.1</strong> System Architecture Diagram</p>
</div>

**Diagram 2: Multi-Cook Algorithm (Logic Flowchart)**

This flowchart details the scheduling logic in MultiCookCoordinator, showing how "Critical" vs "Prep" time is handled.

<div align="center">
  <img src="images\2.2 Logic FlowChart.png" alt="Figure 2.2" title="Figure 2.2">
  <p><strong>Figure 2.2</strong> Logic FlowChart</p>
</div>

**Diagram 3: Session Recovery Sequence**

This diagram illustrates the "Reboot Resurrection" process handled by BootReceiver and SessionManager when the phone restarts.

<div align="center">
  <img src="images\SequenceDiagram.png" alt="Figure 2.3" title="Figure 2.3">
  <p><strong>Figure 2.3</strong> Sequence Diagram</p>
</div>

<div align="center">
  <img src="images\UML Class Diagram.png" alt="Figure 2.4" title="Figure 2.4">
  <p><strong>Figure 2.4</strong> UML Class Diagram</p>
</div>

## 2.5 Implementation Details

This section outlines the specific Android frameworks and Kotlin language features used to realise the architecture described above.

**Declarative UI with Jetpack Compose**

The user interface is built using Jetpack Compose, implementing a fully reactive paradigm.

- **State Hoisting:** The app extensively uses State Hoisting, where state (such as `timeInSeconds` or `currentStep`) is moved up to parent composables like `EnhancedSingleRecipeCooking`. This ensures the UI is stateless and purely a reflection of the current data model.

- **Animation APIs:** `AnimatedVisibility` and `animateScrollToPage` are used in `MultiCookModeScreen` and `SmartCookHomePage` to provide fluid visual feedback when recipes are added or steps are completed, enhancing the user experience without complex imperative animation code.

**Asynchronous Programming with Kotlin Coroutines** 

To ensure the main thread remains unblocked (preventing "Application Not Responding" errors), Kotlin Coroutines are used for all database and heavy computational tasks.

- **ViewModelScope:** In `RecipeViewModel`, database insertions and updates are launched within the `viewModelScope`. This automatically cancels pending operations if the `ViewModel` is cleared, preventing memory leaks.

- **Structured Concurrency in Services:** The `TimerService` manages its own `CoroutineScope` with a `SupervisorJob`. This ensures that if one timer coroutine fails, it does not crash the entire service or affect other running timers.

**Local Data Persistence Strategy**

The data layer leverages Room, an abstraction layer over SQLite, to provide compile-time verification of SQL queries.

- **Reactive Data Streams:** The `RecipeDao` returns `Flow < List < RecipeEntity>>` instead of static lists. This allows the UI to automatically update in real-time. For example, when a user "favourites" a recipe, the `SmartCookHomePage` updates immediately because it is observing the Flow emitted by the  Room.

- **Preference Management:** For lightweight data like "Dark Mode" or "Sound Settings," Jetpack DataStore is used. Unlike SharedPreferences, DataStore is safe to call from the UI thread as it uses Coroutines and Flow to perform disk I/O asynchronously.

**Background Execution Management**

Reliable background execution is achieved through a hybrid approach of **Foreground Services and Broadcast Receivers**.

- **Binder Pattern:** The `TimerService` defines a custom `TimerBinder` class that returns the service instance. This allows the Activity to bind to the service and call public methods (like `setTimer` or `stopSound`) directly, enabling tight integration between the background timer logic and the foreground UI.

- **System Broadcasts:** The `BootReceiver` is registered in the manifest to receive the `BOOT_COMPLETED` intent. This entry point is critical for the app's recovery logic, allowing code execution to resume immediately after the OS boots without requiring user interaction.

**Type-Safe Navigation**

The app utilises Jetpack Navigation with arguments to pass complex data between screens. This is essential for the notification deep-linking feature. When a user taps a "Timer Expired" notification, the Intent passes the `recipeId` and `sessionId` as arguments, which the `NavHost` in `MainActivity` parses to route the user directly to the correct active session.








<br>

# 3. Analysis of Challenges and Solutions
## 3.1 Technical Challenges
### 3.1.1 Ensuring Timer Continuity and Precision in Background States
A critical requirement for a cooking application is the reliability of the timer. A significant technical challenge was Android's aggressive power management strategy (Doze mode and App Standby buckets), which frequently kill background thread or pause execution when the device screen is off. Initial attempts at using standard Kotlin coroutines within the ViewModel failed because the timer would pause when the application lost focus or the device went to sleep, resulting in inaccurate cooking timer which is unacceptable for a cooking app. 

**Solution:**  
We implemented a **Foreground Service** architecture ([TimerService.kt](smartcook/app/src/main/java/com/example/smartcook/TimerService.kt)) to elevate the process priority.  
- **Foreground Service & WakeLocks:** The service runs as a foreground process with a persistent notification ([TimerNotificationManager.kt](smartcook/app/src/main/java/com/example/smartcook/TimerNotificationManager.kt)), signalling to the Android OS that the user is actively aware of the running process. To prevent CPU from sleeping during the timer countdowns, We utilized a partial `PowerManager.WakeLock`.  
- **Binder Pattern:** We implemented a `Binder` interface to allow UI ([CookingTimer.kt](smartcook/app/src/main/java/com/example/smartcook/CookingTimer.kt)) to bind to the service. This decouples the UI lifecucle from the timer logic. The UI essentially becomes a "viewer" of the service's state. When the UI is destroyed (e.g., user minizme the app), the service continues running, when user returns, the UI reconnects and sync with the service's current `timeInSeconds`.
- **Concurrent Management:** To support Multi-Cook Mode, the service was desinged to manage multiple timer instances simultaneously using a `ConcurrentHashMap`, ensuring thread safety when multiple recipes trigger timer updatets concurrently.  

### 3.1.2 Handling Process Death and Session Recovery (Device Reboots)
User might run out of battery or restart their phone while an hour roast is cooking. A standard in-memory timer would lose all progress in these scenarios. The challenge was to ensure that the cooking session would presist through an application crash or a full device reboot "catch up" to the correct time when restore.

**Solution:**  
We developed a comprehensive persistence layer using **Room Database** and a timestamp based recovery logic.
- **State Persistence:** The `TimerService` persists the current session state (current step, remaining time, lastUpdateTimestamp) to the `CookingSessionEntity` every 5 seconds.
- **Boot Receiver:** We implemented a `BroadcastReceiver` ([BootReceiver.kt](smartcook/app/src/main/java/com/example/smartcook/receiver/BootReceiver.kt)) that listens for `Intent.ACTION_BOOT_COMPLETED`. Upon reboot, the app queries the database for active but interrupted sessions.
- **Delta Calculation Logic:** In ([SessionManager.kt](smartcook/app/src/main/java/com/example/smartcook/session/SessionManager.kt)), the app calculates the time elapsed since the lastUpdateTimestamp. Logic: `NewRemainingTime = SaveRemainingTime - (CurrentTime - LastSavedTime)`. If the result is zero or negative, the app triggers an immediate "Timer Expired" notification. If positive, it restarts the timer serrvice with adjusted time, ensuring the user's cooking is not ruined by a reboot.

### 3.1.3 Orchestrating the Multi-Cook Coordination Algorithm 
The Multi-Cook Mode required the app to coordinate multiple recipes with varying durations (e.g., 1-hour roast vs 20-minute stir fry) so that they finish simultaneously. The technical difficulty lay in algorithmically determining the Critical Path and generating a dynamic schedule that accounts for both active cooking time (timer-based) abd passive prep time (untimed).

**Solution:**
We implemented a dedicated **Coordination Engine** ([MultiCookCoordination.kt](smartcook/app/src/main/java/com/example/smartcook/MultiCookCoordinator.kt)).
- **Schedule Generation:** The coordinator analyzes selected recipes to find the one with the longest total duration (Prep + Cook). This sets the `targetFinishTime`.
- **Back Calculations:** For every other recipe, the coordinator calculates a `delayMinutes` offset. `Recipe Start Time = Target Finish Time - (Recipe Prep Time + REcipe Cook Time)`. 
- **Priority Action Queue:** To guide the user, we implemented a priority logic (`determineNextAction`) that scans all active recipes and returns a single, clear instruction. It prioritizes events in this order:
    1. **Critical:** Alarms that are currently ringing.
    2. **Start Needed:** Timed steps that must begin immediately to meet the deadline.
    3. **Prep Actions:** Untimed steps that can be done during downtime.
    4. **Wait:** If all timers are running and non prep is needed. This logic ensures the user is never overwhelmed by conflicting instructions for multiple recipes.

### 3.1.4 Navigation State Restoration
When user clicks a notification (e.g., Timer Finished), they expect to be taken directly to thte specific cooking screen for the recipe or the specific Multi-Cook group dashboard. However, passing complex object like whole `Recipe` entities through `Intent` extras is inefficient and prone to `TransactionTooLargeException`. Furthermore, `MainActivity` needs to handle these intents both on a fresh launch and when the activity is already running (`onNewIntent`).

**Solution:**
We utilized a specialized Deep Linking strategy using ID references.
- **Intent Extras:** The `TimerNotificationManager` embeds minimal data: `recipe_id`, `resume_session_id` and `multi_cook_group_id` into the `PendingIntent`.
- **Route Handling:** [MainActivity.kt](smartcook/app/src/main/java/com/example/smartcook/MainActivity.kt) contains logic in a `LaunchedEffect` to parse these IDs. It prioritizes `multi_cook_group_id`. If present, it bypasses the home screen and navigates directly to the `multiCookRestore/{groupID} route.
- **Database Re-hydration:** Instead of passing data payloads, the destination screens (`EnhancedSingleRecipeCooking` or `MultiCookModeScreen`) use the passed IDs to re-fetch the fresh state from the `RecipeDatabase`. This ensures that the UI always displays the most current state of the timer and session, resolving potential synchronization issues between the Notification and the Activity.

## 3.2 Design and Logic Challenges
### 3.2.1 State Management and Architectural Scalability
Developing a feature-rich application like SmartCook requires managing various interconnected states, user-created recipes, application settings and the live state of a running timer (potentially for multiple recipes). The primary challenge was ensuring the application logic was **testable**, **maintainable** and **scalable** within the modern **Jetpack Compose** framework, which demands a robust, unidirectional data flow.
**Solution:**
To address this, the application strictly adheres to the **Model-View-ViewModel (MVVM)** architectural pattern.
- **View Layer (Screens):** Composables ([AddRecipeScreen.kt](smartcook/app/src/main/java/com/example/smartcook/AddRecipeScreen.kt) and [RecipeDetailPage.kt](smartcook/app/src/main/java/com/example/smartcook/RecipeDetailPage.kt)) are purely declarative, observing state exposed as Kotlin `StateFlow` from the ViewModel. This ensures the UI is loosely coupled from the business logic.
- **ViewModelLayer:** Classes like `RecipeViewModel` use Coroutines to ineract with the repository, abstracting asynchronous operations (database access, service interaction). They expose **Immutable State**, guaranteeing thread safety and predictable UI updates.
- **Dependency Injection:** **Hilt** was integrated to manage dependencies, specifically injecting the `RecipeRepository`, `SettingsDataStore` and other components. This simplified construction and enhanced testability by allowing easy mock-up of data sources for unit testing. The use of Dependency Injection is foundational to the application's long-term maintenance and modularity.

### 3.2.2 User Experience Design for Dynamic Recipe Creation
The core functionality of the app, creating custom recipes, requires the user to input dynamic, structured data, a sequence of steps, each with potentially different properties (a simple "prep" step versus a "timed cooking" step). The design challenge was to create an input screen ([AddRecipeScreen.kt](smartcook/app/src/main/java/com/example/smartcook/AddRecipeScreen.kt)) that was **intuitive, prevented errors and clearly represented the sequential nature of a recipe** without cluttering the mobile screen.

**Solution:**
Iterative Step Modeling and Visual Cues
1. **Data Modeling:** The `Recipe` entity was designed with a clear list of `RecipeStep` objects, enabling the UI to easily iterate and display them.
2. **Dynamic Input List:** The ([AddRecipeScreen.kt](smartcook/app/src/main/java/com/example/smartcook/AddRecipeScreen.kt)) manages a dynamic `mutableStateListOf` steps. Crucially, the UI provides **distinct visual templates** for adding a Prep Step (untimed instruction) versusu a Cooking Step (require timers). This immediately clarifies the purpose of the input to the user.
3. **Inline Validation:** Input fields for time are validated in real-time to ensure only positive integer values are accepted, providing immediate feedback and preventing the saving of invalid recipes, thereby improving data reliability.

### 3.2.3 Simplifying Multi-Cook Coordination Visualization
The **Mutli-Cook Mode** is technically complex, involving parallel timers and back-calculated start times managed by [MultiCookCoordination.kt](smartcook/app/src/main/java/com/example/smartcook/MultiCookCoordinator.kt). The mahor design challenge was transforming this **complex parallel scheduling logic** into a **simple, sequential and actionable workflow** for the end user on the [MultiCookModeScreen.kt](smartcook/app/src/main/java/com/example/smartcook/ui/theme/MultiCookModeScreen.kt). Presenting multiple active timers would lead to cognitive overload and errors.

**Solution:**
**Prioritized, Task-Oriented Interface**. The solution was to focus the UI on the single most critical action the user needs to take at any given moment.
- **The "Next Action" Block:** The [MultiCookModeScreen.kt](smartcook/app/src/main/java/com/example/smartcook/ui/theme/MultiCookModeScreen.kt) has a large card displaying the output of the coordinator's `determineNextAction()` method. This action is categorized into **WAIT**, **PREP** and **START TIMER** and clearly specifies the recipe and step. This design choice minimizes user error by providing a singular, authoritative instruction.
- **Sequential Timeline:** On top right corner of the screen there is a button that can show the full schedule as a sequential timeline. This visualization shows when Recipe A will finish, followed by Recipe B ensuring the user understands the overall plan and the coordinated **target finish time**. This successful asbtraction of complexity is key to the application's unique value proposition and user-friendliness.

<br>

# 4. Testing and Validation
## 4.1 Testing Strategy
To ensure the reliability and robustness of the application, we adopted a comprehensive testing strategy based on the standard **Android Testing Pyramid**. This approach balances fidelity, execution speed and isolation by dividing tests into three distinct layers:
1. **Unit Tests (Logic & Data):** These tests validate the correctness of individual classes and algorithms in isolation from the Android framework. They run locally on the development machine (JVM), ensuring fast feedback loops for complex logic like the multi-cook scheduler.
2. **Integration Tests (Persistence):** These tests verify how different components interact, specifically focusing on the data persistence layer. We utilized the Android Instrumentation framework to test the Room Database on an emulated device to ensure data integrity.
3. **UI Tests (Interface & Validation):** These tests simulate user interactions to verify the graphical user interface. We used the **Jetpack Compose Test Rule** to ensure that input validation and state changes (e.g., enabling/disabling button) function as expected.

## 4.2 Unit Testing Evidence: Scheduling Algorithm
The core innovation of this application is the `MultiCookCoordinator`, which calculates optimal start times for parallel cooking. Since the logic is algorithmic and does not require Android dependencies, it was tested via local unit tests.  
**Test Class:** [MultiCookCoordinatorTest.kt](smartcook/app/src/test/java/com/example/smartcook/MultiCookCoordinatorTest.kt)  
**Scenario:** `generateSchedule` calculates correct delay for shoter recipe  
**Objective:** To verify that when scheduling two recipes of different durations (e.g., 30-minute Roast Chicken and 10-minute Salad), the application correctly assigns a delay to the shorter recipe so that both finish simultaneously.  
**Outcome:** The test confirmed that the algorithm correctly calculated a 20-minute delay for the shorter recipe. This ensures users can serve all dishes hot at the same time.  

<div align="center">
  <img src="images/Test Pass/Screenshot 2025-12-09 003118.png" alt="Figure 5.1" title="Figure 5.1">
  <p><strong>Figure 5.1</strong> Unit test result confirming the correct calculation of cooking delays.</p>
</div>

## 4.3 Unit Testing Evidence: Data Logic
To ensure that raw data stored in the database is presented meaningfully to the user, we implemented unit tests for data entities.  
**Test Class:** [RecipeEntityTest.kt](smartcook/app/src/test/java/com/example/smartcook/RecipeEntityTest.kt)  
**Scenario:** `getTimingSummary` generates correct string for mixed steps  
**Objective:** To validate the extension function that parses raw step strings into a user-friendly summary. The test verified that "untimed" steps are correctly categorized as Prep Time and timed steps as Cook Time.  
**Outcome:** The tesst successfully asserted that a recipe with 10 minutes of prep and 20 minutes of cooking produced the formatted string "Prep: 10m Cook: 20m Total: 30m".  

<div align="center">
  <img src="images/Test Pass/Screenshot 2025-12-09 003701.png" alt="Figure 5.2" title="Figure 5.2">
  <p><strong>Figure 5.2</strong> Unit test result validating the data parsing and formatting logic.</p>
</div>

## 4.4 Integration Testing Evidence: Database Persistence
The application relies heavily on the `CookingSession` entity to restore state after app closures or device reboots. Integration testing was performed to ensure the Room Database correctly hanldes data persistence.  
**Test Class:** [CookingSessionDaoTest.kt](smartcook/app/src/androidTest/java/com/example/smartcook/CookingSessionDaoTest.kt)  
**Scenario:** `writeSessionAndReadInList`  
**Objective:** To verify that a `CookingSessionEntity` object can be written to the database and retrieved intact. This test used an in-memory version of Room database (`Room.inMemoryDatabaseBuilder`) to perform a hermetic test that does not affect the actual device storage.  
**Outcome:** The test successfully inserted a dummy session and retrieved it by ID, confirming that the `RecipeName` and `RecipeId` matched the original input. This guarantees that user pgorgess is safe against data loss.  

<div align="center">
  <img src="images/Test Pass/Screenshot 2025-12-09 004200.png" alt="Figure 5.3" title="Figure 5.3">
  <p><strong>Figure 5.3</strong> Integration test result confirming database read/write operations.</p>
</div>

## 4.5 UI Testing Evidence: Button disable/enable test
The User interface tests focues on input validation to prevent crashes caused by invalid data.  
**Test Class:** [AddRecipeScreenTest.kt](smartcook/app/src/androidTest/java/com/example/smartcook/AddRecipeScreenTest.kt)  
**Scenario:** `addRecipeButton_Disabled_WhenInputsEmpty`  
**Objective:** To ensure that the Save Recipe button remains disabled until the user enters the required fields.   
**Outcome:** The test passed, confirming that the button state correctly reacts to user input, preventing the submission of incomplete forms.  

<div align="center">
  <img src="images/Test Pass/Screenshot 2025-12-09 004341.png" alt="Figure 5.4" title="Figure 5.4">
  <p><strong>Figure 5.4</strong> UI test result validating input forms and demonstrating the successful use of the Fake Object pattern.</p>
</div>

<br>

# 5. Conclusion
SmartCook application successfully achieves its primary objective: transforming the mobile cookign experience from a passive recipe reader into an active, intelligent kitchen assistant. By addressing the common difficulty of timing multiple dishes, the app provides a tangible solution to a real-world problem through the implementation of the **Multi-Cook Coordinator**.

The adaption of **Jetpack Compose** allowed for a responsive and intuitive user interface, while the **Room Database** and **Foreground Services** ensured that user progress is preserved reliably across app closures and devices restarts. The successful implementation of the scheduling algorithm validates the core technical hypothesis that complex parallel cooking timelines can be automated effectively on a mobile device.

In summary, SmartCook is not just a functional prototype but a resilient application that fulfills its design requirements. It lays a solid foundation for future enhancements, such as cloud synchronization or API integration and stands as a testament to the effective application of software engineering principles in mobile device programming.