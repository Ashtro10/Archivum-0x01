# CLU user guide

## The window
Chat · Memory · People · Timeline · Documents · Suggestions · Capabilities · **Hands** · **Agents** · Models · Status · Settings. The pill at the top shows the model state (and the real engine, cpu/gpu). Behind the window runs the Primus core, the particle head from the original Primus interface: it listens, thinks and speaks with CLU and follows the pointer.

## Hands (camera gestures)
The camera picture fills the view with CLU's widgets on top (core status, weather, your applications, time). The first time, CLU shows what it needs (Google MediaPipe's hand tracking, 17.6 MB, Apache-2.0) and downloads it only when you click; it is checked file by file and then works offline. The camera runs only while Hands is open, and its picture never leaves the PC. **Point** with your index finger to move the ring; a **quick pinch** (thumb to index) taps (opens an application from the list); **pinch and move** drags a widget; **pinch with both hands** on one widget and move them apart or together to resize it. **C** calibrates (hold your fingertip on two circles), **D** shows the tracked points. Keep your hand within about arm's length of the webcam.

## Your day (phone and tablet)
* **Lists and reminders:** "add milk to my shopping list", "remind me to call the bank tomorrow at 10", "every day at 8 am…". They sync between your phone and tablet.
* **Calendar:** "what's on my calendar this week?", "when am I free tomorrow?", "schedule a meeting with Priya on Friday at 4", "move…", "cancel…". Android asks for calendar access the first time.
* **Briefings:** "brief me" in the morning; turn on *Evening summary* (Settings → Proactive) for a short wrap-up at a time you choose.
* **Notifications and calls:** "what did I miss?", "reply to Rahul saying on my way" (CLU asks first), "clear WhatsApp notifications". "Turn on notification announcements" and "turn on call announcements" make CLU say who is messaging or calling. Choose the apps it announces and the ones it ignores in Settings → Proactive.
* **Routines:** CLU may notice things you do regularly and suggest them under memory suggestions. Nothing is saved unless you approve it.
* **Images:** this build has no vision model, so CLU says so instead of guessing.

## The phone
With **Settings → Connection → Automatic** (the default), the phone is a terminal for CLU on your PC once it is paired: the PC listens, thinks and speaks for voice and typed messages, and the phone doesn't load a model of its own. Before pairing, or with **Always this device**, the phone answers on its own without any network.

## Models
*Models → Add a model*: **Find models on this PC** lists `.gguf` files in Downloads, Documents, Desktop, `models` and CLU's folder; **Register** keeps the file where it is. **Suggested downloads** show size, licence and fit; nothing downloads until you confirm; downloads can be cancelled and resumed and are verified. **Use as default**, **Load/Reload/Unload**, context/threads/GPU in *How the model is loaded* (CLU says what actually runs). **Benchmark** measures this PC. **Remove registration** never deletes the file; **Delete file…** exists only for models CLU downloaded and asks first.

## Memory, suggestions
Unchanged from earlier versions: what you state can be saved; what CLU infers waits in Suggestions for your approval.

## Assistant tasks (Agents)
1. **Tasks → New task**: describe a goal ("look through my notes on the battery and write a summary file"), or type `/task …` in chat. CLU shows its plan (steps, roles, which need approval). Nothing has happened yet.
2. **Start (ask me before each change)**, or **Start and approve this plan's changes** (still asks for deletes/replacements). You can also write the steps yourself.
3. When a step changes something CLU stops and shows an **approval card**: what, why, the tool, exactly which items, whether it can be undone, what happens if it works or fails. **Approve this once**, **Approve for this task** (just those items, until the task ends), or **Reject**. Deleting, replacing a file or running a program asks for **Confirm** with a tick, every time. Deleted/replaced files go to a `.clu-trash` folder next to them.
4. Pause, resume or cancel any time; a restart pauses tasks and repeats nothing on its own. **Suggest remembering this** offers the result as a memory suggestion.
5. **Permissions**: the folders tasks may use (default: `Documents\CLU Workspace`), what you have approved (revoke any, or everything), limited standing permissions (≤ 1 week). **Agents**: the roles and tools, with switches (running programs is off). **Activity**: everything tasks did, filterable; *Check the record is intact* verifies it.
6. Text CLU reads from files or web pages is information, never orders; if it looks like instructions you are warned.

## Settings → CLU on this PC
Keep running in the tray when the window closes; start with Windows (off by default); run the guide again; Restart; Quit. Settings → *Device control & tools* has the assistant-task limits (steps, parallelism, time) and the list of programs tasks may run (empty).

## Backups and recovery
Automatic verified backups (and before upgrades) in `%LOCALAPPDATA%\CLU\backups`; *Memory → Health & backups* shows them and how to restore. A damaged database is never opened for writing.
