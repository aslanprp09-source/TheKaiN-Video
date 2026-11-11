"""Tkinter proof of concept; install yt-dlp first."""
import queue
import shutil
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from engine import Engine, Options, command_for
from extractor import SourceAnalyzer
from domain import AnalyzeResult, DownloadError, ErrorCode


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("TheKaiN Video — Rebuild 0.1")
        self.geometry("650x330")
        self.engine = Engine()
        self.events = queue.Queue()
        self.busy = False
        self.analyzer = SourceAnalyzer()
        self.analysis_cancel = threading.Event()
        self.analysis_generation = 0
        self.analyzing = False
        self.media = None
        self.url = tk.StringVar()
        self.folder = tk.StringVar(value=str(Path.home() / "Downloads"))
        self.quality = tk.StringVar()
        self.status = tk.StringVar(value="Вставьте ссылку и определите доступные форматы.")
        self.progress = tk.DoubleVar()
        frm = ttk.Frame(self, padding=22)
        frm.pack(fill="both", expand=True)
        ttk.Label(frm, text="TheKaiN Video", font=("Segoe UI", 21, "bold")).pack(anchor="w")
        ttk.Label(frm, text="Новая тестовая версия Windows 0.1").pack(anchor="w", pady=(0, 12))
        link = ttk.Frame(frm)
        link.pack(fill="x", pady=5)
        ttk.Entry(link, textvariable=self.url).pack(side="left", fill="x", expand=True)
        ttk.Button(link, text="Вставить", command=self.paste).pack(side="left", padx=4)
        ttk.Button(link, text="×", command=lambda: self.url.set("")).pack(side="left")
        choice = ttk.Frame(frm)
        choice.pack(fill="x", pady=7)
        ttk.Label(choice, text="Формат").pack(side="left")
        self.formats = ttk.Combobox(choice, textvariable=self.quality, values=(), width=29, state="readonly")
        self.formats.pack(side="left", padx=9)
        ttk.Label(choice, text="Папка").pack(side="left", padx=(5, 0))
        ttk.Entry(choice, textvariable=self.folder, width=12).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(choice, text="…", command=self.choose_folder).pack(side="left")
        controls = ttk.Frame(frm)
        controls.pack(anchor="w", pady=10)
        self.analyze_btn = ttk.Button(controls, text="Определить видео", command=self.analyze)
        self.analyze_btn.pack(side="left", padx=(0, 7))
        self.start_btn = ttk.Button(controls, text="Скачать / продолжить", command=self.start, state="disabled")
        self.start_btn.pack(side="left")
        self.pause_btn = ttk.Button(controls, text="Пауза", command=lambda: self.stop("paused"), state="disabled")
        self.pause_btn.pack(side="left", padx=7)
        self.cancel_btn = ttk.Button(controls, text="Стоп", command=lambda: self.stop("canceled"), state="disabled")
        self.cancel_btn.pack(side="left")
        ttk.Progressbar(frm, variable=self.progress, maximum=100).pack(fill="x", pady=7)
        ttk.Label(frm, textvariable=self.status, wraplength=580).pack(anchor="w")
        self.after(100, self.pump)
        self.url.trace_add("write", self.url_changed)
        self.protocol("WM_DELETE_WINDOW", self.close)

    def paste(self):
        try:
            self.url.set(self.clipboard_get().strip())
        except tk.TclError:
            self.status.set("Буфер обмена недоступен")

    def choose_folder(self):
        folder = filedialog.askdirectory(initialdir=self.folder.get())
        if folder:
            self.folder.set(folder)

    def start(self):
        if self.busy or self.analyzing or self.media is None:
            return
        index = self.formats.current()
        if not 0 <= index < len(self.media.formats):
            return
        job = Options(self.url.get(), Path(self.folder.get()), self.media.formats[index])
        try:
            command_for(job)
            if job.quality.requires_merge and not shutil.which("ffmpeg"):
                raise ValueError("Для объединения выбранных дорожек требуется FFmpeg в PATH")
        except (ValueError, OSError) as exc:
            messagebox.showerror("Ошибка", str(exc))
            return
        self.busy = True
        self.progress.set(0)
        self.status.set("Скачивание началось…")
        self.start_btn.config(state="disabled")
        self.analyze_btn.config(state="disabled")
        self.pause_btn.config(state="normal")
        self.cancel_btn.config(state="normal")
        threading.Thread(target=self.worker, args=(job,), daemon=True).start()

    def worker(self, job):
        try:
            outcome = self.engine.run(job, lambda key, val: self.events.put((key, val)))
        except Exception:
            self.events.put(("log", DownloadError(ErrorCode.UNKNOWN).message))
            outcome = "failed"
        self.events.put(("done", outcome))

    def stop(self, reason):
        if self.engine.stop(reason):
            self.status.set("Останавливаем…")

    def url_changed(self, *_):
        self.analysis_cancel.set()
        self.analysis_generation += 1
        self.analyzing = False
        self.media = None
        self.quality.set("")
        self.formats.config(values=())
        self.start_btn.config(state="disabled")
        self.analyze_btn.config(text="Определить видео", state="disabled" if self.busy else "normal")
        if not self.busy:
            self.status.set("Определите доступные форматы для новой ссылки.")

    def analyze(self):
        if self.busy:
            return
        if self.analyzing:
            self.url_changed()
            self.status.set("Анализ отменён.")
            return
        self.analysis_cancel = threading.Event()
        self.analysis_generation += 1
        generation, url, cancel = self.analysis_generation, self.url.get(), self.analysis_cancel
        self.analyzing = True
        self.media = None
        self.formats.config(values=())
        self.quality.set("")
        self.start_btn.config(state="disabled")
        self.analyze_btn.config(text="Отменить анализ")
        self.status.set("Определяем доступные форматы…")
        threading.Thread(target=self.analyze_worker, args=(generation, url, cancel), daemon=True).start()

    def analyze_worker(self, generation, url, cancel):
        try:
            result = self.analyzer.analyze(url, cancel)
        except Exception:
            result = AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
        self.events.put(("analysis", (generation, result)))

    def apply_analysis(self, generation, result):
        if generation != self.analysis_generation:
            return
        self.analyzing = False
        self.analyze_btn.config(text="Определить видео")
        if result.error:
            self.status.set(result.error.message)
            return
        self.media = result.media
        self.formats.config(values=tuple(v.label for v in self.media.formats))
        self.formats.current(0)
        self.start_btn.config(state="normal")
        self.status.set(f"{self.media.display_title} · {self.media.source_host}. Выберите формат.")

    def pump(self):
        try:
            while True:
                event, value = self.events.get_nowait()
                if event == "analysis":
                    self.apply_analysis(*value)
                elif event == "progress":
                    self.progress.set(value)
                elif event in ("log", "result"):
                    self.status.set(str(value))
                elif event == "done":
                    self.busy = False
                    self.start_btn.config(state="normal" if self.media else "disabled")
                    self.analyze_btn.config(state="normal")
                    self.pause_btn.config(state="disabled")
                    self.cancel_btn.config(state="disabled")
                    if value == "completed":
                        self.progress.set(100)
                    elif value == "paused":
                        self.status.set("Пауза. Нажмите «Скачать / продолжить».")
                    elif value == "canceled":
                        self.status.set("Остановлено. Частичные файлы сохранены.")
        except queue.Empty:
            pass
        self.after(100, self.pump)

    def close(self):
        self.analysis_cancel.set()
        self.engine.stop("paused")
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
