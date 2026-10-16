# Fix Form UI/UX Layout, Keyboard Jumping, and Focus Behavior

Fix jarring screen jumps, scrolling bugs, and focus loss when tapping form input fields (such as email/password) on auth screens (`LoginScreen` and `RegisterScreen`).

## User Review & Critical Decisions

> [!IMPORTANT]
> - **Modifier Reordering**: Placing `.imePadding()` *after* `.verticalScroll(...)` (or inside the scroll container padding) prevents the viewport from resizing abruptly and fighting with the keyboard when focused.

- **Confirmed Decision 1**: Correct modifier order in `LoginScreen` and `RegisterScreen`: `.verticalScroll(...)` -> `.imePadding()` -> inner padding.
- **Confirmed Decision 2**: Ensure smooth scrolling and stable focus retention across form inputs.

## 1. Overview & Core Concept

- **What It Does**: Corrects Jetpack Compose layout modifier ordering on scrollable authentication screens so that opening the software keyboard does not cause abrupt screen resizing, focus jumping, or erratic scrolling.
- **Target Value**: Seamless, polished UX when interacting with form input fields.

## 2. User Experience & Visual Design

- **Visual Polish**: Tapping on email or password fields smoothly reveals the keyboard without shifting or resetting scroll/focus state.
- **Consistency**: Applied to both Login and Register screens.

## 3. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────┐
│                      Scaffold                           │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                       Column                            │
│  - .fillMaxSize()                                       │
│  - .padding(innerPadding)                               │
│  - .verticalScroll(rememberScrollState())  <-- FIRST    │
│  - .imePadding()                           <-- SECOND   │
│  - .padding(horizontal = 24.dp, vertical = 20.dp)       │
└─────────────────────────────────────────────────────────┘
```

- **Files to Modify**:
  - `/app/src/main/java/com/ammalfarm/adusanthai/ui/screens/auth/LoginScreen.kt`
  - `/app/src/main/java/com/ammalfarm/adusanthai/ui/screens/auth/RegisterScreen.kt`
