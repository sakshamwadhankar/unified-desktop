# PRD: what we build

Working codename `unified-desktop`. **No product name yet**: the owner picks one later,
after checking domains. Never invent one.

## 1. In one sentence

Connect an Android phone to any PC, by cable or Wi-Fi, and the PC becomes a locked,
full-screen desktop that runs **Windows, Linux and Android apps**, all running on the phone,
while the phone stays usable.

## 2. The idea in plain words

The phone is the computer. The PC is only a screen, a keyboard and a mouse. Nothing is
installed on the PC except our small receiver, and nothing is left behind on it. Our desktop
and all its apps live on the phone.

## 3. Promises (hard requirements)

| # | Promise |
|---|---|
| R1 | **PC apps first.** Windows `.exe` apps and Linux apps run in our desktop. Android apps are supported too: apps already installed on the phone, or installed from Google Play. |
| R2 | **The phone does the work.** Every app runs on the phone's CPU. The PC only shows the picture and sends mouse and keyboard. |
| R3 | **No VM, no booting.** Apps start directly. |
| R4 | **Android apps run hidden:** they show on the PC desktop, not on the phone's screen. |
| R5 | **The phone stays fully usable** during a session: WhatsApp, calls, anything. |
| R6 | **PC lock.** While connected, the receiver is full screen and the rest of the PC cannot be reached, like exam software (Code Tantra, Safe Exam Browser). **One Exit button** ends it and the PC is back to normal instantly. No password. |
| R7 | **USB and Wi-Fi, both.** USB is used automatically when a cable is in (faster, and it charges the phone); Wi-Fi otherwise. |
| R8 | **No developer mode, no root, no ADB** for the user, at any step. |
| R9 | **On Google Play.** |
| R10 | **Light.** The receiver runs on old PCs (Windows 10/11 on old hardware). The phone side runs on low-end phones; the test phone is a Snapdragon 695. |
| R11 | **App Center.** Install apps by name ("VS Code", "Zoom"). It picks the version that runs best on a phone. Any `.exe` can also be opened directly. |
| R12 | **Continue where you stopped.** Exit or unplug, connect again: the same apps with the same content. |

## 4. Success measures (provisional; measured, never assumed)

| Measure | Target |
|---|---|
| Connect to usable desktop | <= 5 s |
| Typing latency, key on PC to visible on PC, over USB | <= 80 ms |
| Typing latency over 5 GHz Wi-Fi | <= 120 ms |
| Frame rate while moving windows | >= 30 fps |
| Light Windows app (Notepad++) start | <= 10 s |
| VS Code opens a small project | <= 15 s |
| Phone during a session | WhatsApp and calls work, no freezes |
| Session on charger without being killed | 2 hours |
| Receiver on an old PC (dual-core, 4 GB RAM, Windows 10) | 1080p full screen |

The manager revises targets once early phases produce real numbers.

## 5. Known limits (stated once)

- A Google Play app cannot replace the phone's operating system. "Our OS" is our own desktop,
  shown full screen on the PC.
- A copied (virtualized) Android app has its own login: the user logs in once inside it.
  Some apps (typically banking) refuse to run as copies.
- Android apps must come from Google Play (Google's rule), not random APK files.
- Not every `.exe` runs. Wine covers most programs, but some large or very new ones fail or
  are slow. App Center rates apps so users know before installing.
- Heavy games will not be smooth on a low-end phone.
- Windows protects Ctrl+Alt+Del. We hide its options the way exam software does, but the key
  itself cannot be removed.

## 6. Out of scope (for now)

- Replacing the phone's OS (custom ROM, root).
- Running apps on the PC's own CPU.
- iOS; receivers for macOS or Linux.
- Product name, branding and domain (the owner decides later).
