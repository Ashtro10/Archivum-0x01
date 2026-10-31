"""Build the canonical Windows Primus executable.

Usage (from repository root):

    python scripts/build_windows.py

Output:

    dist_windows/Primus.exe

Keep ``_internal`` next to that exe. Do not copy Primus.exe alone.
Does not bundle GGUF models, .env, user databases, or scratch data.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "dist_windows"
LEGACY_OUT = ROOT / "dist-windows"
WORK = ROOT / "build" / "primus-pyinstaller"
ICON = ROOT / "assets" / "primus.ico"

_EXCLUDE_MODULES = (
    "pytest",
    "matplotlib",
    "IPython",
    "ipykernel",
    "ipython_genutils",
    "jupyter",
    "jupyter_client",
    "jupyter_core",
    "notebook",
    "qtconsole",
    "PyQt5",
    "PyQt6",
    "PySide2",
    "PySide6",
    "tkinter",
    "turtle",
    "torch",
    "torchvision",
    "torchaudio",
    "sklearn",
    "pandas",
    "cv2",
    "nltk",
    "pygame",
    "datasets",
    "bitsandbytes",
    "timm",
    "OpenGL",
)


def _run(command: list[str]) -> None:
    print(" ".join(command), flush=True)
    subprocess.run(command, check=True, cwd=ROOT)


def _remove_decoy_exes(keep: Path) -> None:
    keep = keep.resolve()
    for candidate in (
        WORK / "Primus" / "Primus.exe",
        WORK / "Primus.exe",
    ):
        if candidate.is_file() and candidate.resolve() != keep:
            candidate.unlink()
            print(f"removed decoy {candidate}", flush=True)


def _verify_onedir(exe: Path) -> None:
    internal = exe.parent / "_internal"
    required = (
        internal / "python312.dll",
        internal / "VCRUNTIME140.dll",
        internal / "VCRUNTIME140_1.dll",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError(
            "onedir incomplete; bootloader will fail LoadLibrary:\n"
            + "\n".join(missing)
        )


def _flatten(bundle: Path) -> Path:
    """Move Primus/Primus.exe + _internal up to dist_windows/."""
    exe = bundle / "Primus.exe"
    internal = bundle / "_internal"
    dest_exe = OUT / "Primus.exe"
    dest_internal = OUT / "_internal"
    if dest_internal.exists():
        shutil.rmtree(dest_internal)
    if dest_exe.exists():
        dest_exe.unlink()
    shutil.move(str(exe), str(dest_exe))
    if internal.is_dir():
        shutil.move(str(internal), str(dest_internal))
    shutil.rmtree(bundle, ignore_errors=True)
    return dest_exe


def _write_local_model_config(dest: Path) -> None:
    """Point the packaged exe at repo GGUF files if they exist. Not bundled."""
    chat = ROOT.joinpath("benchmarks", "models", "qwen2.5-3b-instruct-q5_k_m.gguf")
    embed = ROOT.joinpath("benchmarks", "models", "nomic-embed-text-v1.5.Q8_0.gguf")
    lines: list[str] = []
    if chat.is_file():
        lines.extend(["[reasoning]", f'model_path = "{chat.as_posix()}"'])
        if embed.is_file():
            lines.append(f'embedding_model_path = "{embed.as_posix()}"')
        lines.append("")
    lines.extend(
        [
            "[memory]",
            'database_path = "astra.db"',
            'vector_index_path = "vectors.faiss"',
            "",
            "[knowledge]",
            'database_path = "knowledge.db"',
            "",
            "[planning]",
            'database_path = "planning.db"',
            "",
            "[research]",
            "allow_external = true",
        ]
    )
    (dest / "astra.toml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {dest / 'astra.toml'}", flush=True)


def _copy_runtime_corpus(dest: Path) -> None:
    """Local research corpus next to the exe. Not models. Not databases."""
    docs_src = ROOT / "docs"
    docs_dst = dest / "docs"
    if docs_src.is_dir():
        if docs_dst.exists():
            shutil.rmtree(docs_dst)
        shutil.copytree(docs_src, docs_dst)
        print(f"copied {docs_dst}", flush=True)
    readme = ROOT / "README.md"
    if readme.is_file():
        shutil.copy2(readme, dest / "README.md")


def main() -> int:
    if sys.platform != "win32":
        print("Windows build only.", file=sys.stderr)
        return 2
    sys.path.insert(0, str(ROOT))
    from scripts.write_icon import write_primus_icon

    write_primus_icon(ICON)
    preserved: dict[str, Path] = {}
    scratch = ROOT / "build" / "primus-preserve"
    if scratch.exists():
        shutil.rmtree(scratch, ignore_errors=True)
    for name in ("data", "logs"):
        src = OUT / name
        if src.exists():
            scratch.mkdir(parents=True, exist_ok=True)
            dest = scratch / name
            shutil.move(str(src), str(dest))
            preserved[name] = dest
    if OUT.exists():
        shutil.rmtree(OUT, ignore_errors=True)
    if LEGACY_OUT.exists():
        shutil.rmtree(LEGACY_OUT, ignore_errors=True)
    OUT.mkdir(parents=True, exist_ok=True)

    def _restore_preserved() -> None:
        for name, src in preserved.items():
            dest = OUT / name
            if src.exists() and not dest.exists():
                shutil.move(str(src), str(dest))
        shutil.rmtree(scratch, ignore_errors=True)
    if WORK.exists():
        shutil.rmtree(WORK, ignore_errors=True)
    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--noupx",
        "--windowed",
        "--name",
        "Primus",
        "--icon",
        str(ICON),
        "--distpath",
        str(OUT),
        "--workpath",
        str(WORK),
        "--specpath",
        str(WORK),
        "--paths",
        str(ROOT),
        "--collect-all",
        "dearpygui",
        "--collect-submodules",
        "astra",
        "--collect-submodules",
        "ui",
        "--hidden-import",
        "ui",
        "--hidden-import",
        "ui.app",
        "--hidden-import",
        "ui.themes.futuristic",
        "--hidden-import",
        "ui.web_server",
        "--add-data",
        f"{ROOT / 'ui'}{';' if sys.platform == 'win32' else ':'}ui",
        "--add-data",
        f"{ROOT / 'jarvis-main'}{';' if sys.platform == 'win32' else ':'}jarvis-main",
        "--hidden-import",
        "pydantic",
        "--hidden-import",
        "astra.product",
        "--hidden-import",
        "llama_cpp",
        "--collect-submodules",
        "llama_cpp",
        "--version-file",
        str(ROOT / "scripts" / "primus_version.txt"),
    ]
    llama_lib = (
        Path(sys.executable).resolve().parent
        / "Lib"
        / "site-packages"
        / "llama_cpp"
        / "lib"
    )
    if llama_lib.is_dir():
        command.extend(["--add-binary", f"{llama_lib};llama_cpp/lib"])
    nvidia = Path(sys.executable).resolve().parent / "Lib" / "site-packages" / "nvidia"
    for rel in ("cuda_runtime/bin", "cublas/bin", "cuda_nvrtc/bin"):
        folder = nvidia / rel
        if folder.is_dir():
            command.extend(["--add-binary", f"{folder};nvidia/{rel}"])
    for name in _EXCLUDE_MODULES:
        command.extend(["--exclude-module", name])
    command.append(str(ROOT / "scripts" / "primus_entry.py"))
    try:
        _run(command)
        bundle = OUT / "Primus"
        if not (bundle / "Primus.exe").is_file():
            print(f"missing {bundle / 'Primus.exe'}", file=sys.stderr)
            return 1
        try:
            _verify_onedir(bundle / "Primus.exe")
        except FileNotFoundError as exc:
            print(str(exc), file=sys.stderr)
            return 1
        exe = _flatten(bundle)
        _remove_decoy_exes(exe)
        _write_local_model_config(exe.parent)
        _copy_runtime_corpus(exe.parent)
        print(f"built {exe} ({exe.stat().st_size} bytes)", flush=True)
        print(
            "Launch dist_windows/Primus.exe. Keep _internal beside it.",
            flush=True,
        )
        return 0
    finally:
        _restore_preserved()


if __name__ == "__main__":
    raise SystemExit(main())
