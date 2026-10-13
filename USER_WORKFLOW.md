# USER WORKFLOW: what the user does and sees

Plain steps, from the user's side. The user never sees developer mode, a VM, a Linux boot,
or a command line (unless they open the terminal themselves).

## 1. Install (once)

1. **Phone:** install our app from **Google Play**.
2. **PC:** download our small **receiver** program and install it. The installer asks for
   administrator rights once, for the lock features and the USB driver.

## 2. First connection (once per PC)

1. Connect the phone to the PC: a **USB cable**, or both on the **same Wi-Fi**.
2. The PC and the phone both show the **same 6-digit code**. The user checks they match and
   taps **Yes** on the phone. From now on this PC is trusted; a stranger's PC cannot connect
   without this step.
3. On USB, Android may ask "Open with [our app]?". The user ticks **Always**.

## 3. One-time phone setup

The app asks for a few normal permissions, each with a one-line reason:

- **Notifications:** to show that a session is running.
- **Allow background activity:** some phone makers (OnePlus/OPPO/Realme/Xiaomi) pause apps
  in the background. Our app opens the exact settings screen and shows what to tap.
- No developer mode. No root. Nothing else.

## 4. Android apps (optional, once per app)

1. In our desktop, open **App Center > Android apps**. It lists the apps installed on the
   phone.
2. Tick the ones you want on the PC.
3. The first time you open each one, **log in once**. It is a separate copy, so it keeps its
   own login. After that it stays logged in.
4. An app not on the phone yet: App Center opens its **Google Play** page and the user taps
   **Install** on the phone, or installs it without touching the phone from
   **play.google.com** in our desktop's browser.

## 5. Every day

1. Plug in the cable, or just be on the same Wi-Fi.
2. The receiver opens by itself on USB, or the user opens it on Wi-Fi.
3. Within about 5 seconds the PC goes **full screen and locked**, and our desktop appears:
   taskbar, start menu, the user's apps.
4. Work with the **PC's mouse and keyboard**: Windows apps, Linux apps and Android apps, side
   by side, in movable windows.
5. Meanwhile the **phone works normally**: WhatsApp, calls, camera, anything.
6. Press **Exit** on the PC: the PC is back to normal instantly. The apps stay open on the
   phone, so next time everything is where you left it.

## 6. Installing apps (App Center)

App Center is a store for PC apps inside our desktop: a search box and an **Install** button.
For each app it picks the version that runs best on a phone, in this order:

1. a **full-speed version** built into our app (VS Code, terminal, Python, Git, browser)
2. the **Android version** from Google Play (made for phone chips, fast)
3. a good **web version** (nothing to install)
4. the **Linux version**, through the translator
5. the **Windows version** (`.exe`), through Wine and the translator

Each app shows a rating: **Works**, **Works, slow**, or **Not yet**.

### Example: VS Code
App Center > search "VS Code" > **Install** > the icon appears in the start menu > click it.
It is the real VS Code: files, extensions, terminal. Its engine runs at full phone speed; its
screen shows in a desktop window.

### Example: Zoom (not on the phone)
- **Quickest, nothing to install:** open the meeting link in our desktop's browser and choose
  "Join from your browser" (the host may need to allow it).
- **Best quality:** App Center > Zoom > installs the Android Zoom app from Google Play >
  Zoom appears in the desktop > log in once.
- At first Zoom uses the phone's camera and mic. Using the PC's webcam and mic comes later.

### Any other `.exe`
Download it in our desktop's browser, or use **Open any .exe**, and double-click it. It
installs like on a normal Windows PC.

## 7. Files

- The desktop has its own **Files** app (a folder on the phone).
- Bring a file from the PC: drag it onto the receiver window, or use **Import from this PC**
  (built in a later phase).

## 8. When something happens

| Situation | What the user sees |
|---|---|
| Cable pulled / Wi-Fi drops | "Reconnecting...". The Exit button still works. Reconnect resumes where it was. |
| A call comes in on the phone | The desktop keeps running; a small "phone in call" notice appears on the PC. |
| Phone battery low | A warning on the PC. On USB the phone is charging. |
| Phone gets hot | The desktop lowers its quality a little to cool down, automatically. |
| Phone restarts | Next connection starts the desktop again and reopens the apps where possible. |
| A copied Android app refuses to run | App Center says so and explains why (for example a banking app). |
