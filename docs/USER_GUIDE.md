# CLU user guide

## The window
Chat · Memory · People · Timeline · Documents · Suggestions · **Agents** · Models · Status · Settings. The pill at the top shows the model state (and the real engine, cpu/gpu).

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
