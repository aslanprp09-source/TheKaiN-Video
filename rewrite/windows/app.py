"""Tkinter proof of concept; install yt-dlp first."""
import queue
import shutil
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from engine import Engine, Options, FORMAT_CHOICES, command_for


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("TheKaiN Video — Rebuild 0.1")
        self.geometry("650x330")
        self.engine = Engine()
        self.events = queue.Queue()
        self.busy = False
        self.url = tk.StringVar()
        self.folder = tk.StringVar(value=str(Path.home() / "Downloads"))
        self.quality = tk.StringVar(value=FORMAT_CHOICES[0])
        self.status = tk.StringVar(value="Вставьте ссылку и выберите качество.")
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
        ttk.Combobox(choice, textvariable=self.quality, values=FORMAT_CHOICES, width=19, state="readonly").pack(side="left", padx=9)
        ttk.Label(choice, text="Папка").pack(side="left", padx=(5, 0))
        ttk.Entry(choice, textvariable=self.folder).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(choice, text="…", command=self.choose_folder).pack(side="left")
        controls = ttk.Frame(frm)
        controls.pack(anchor="w", pady=10)
        self.start_btn = ttk.Button(controls, text="Скачать / продолжить", command=self.start)
        self.start_btn.pack(side="left")
        self.pause_btn = ttk.Button(controls, text="Пауза", command=lambda: self.stop("paused"), state="disabled")
        self.pause_btn.pack(side="left", padx=7)
        self.cancel_btn = ttk.Button(controls, text="Стоп", command=lambda: self.stop("canceled"), state="disabled")
        self.cancel_btn.pack(side="left")
        ttk.Progressbar(frm, variable=self.progress, maximum=100).pack(fill="x", pady=7)
        ttk.Label(frm, textvariable=self.status, wraplength=580).pack(anchor="w")
        self.after(100, self.pump)
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
        if self.busy:
            return
        job = Options(self.url.get(), Path(self.folder.get()), self.quality.get())
        try:
            command_for(job)
            if job.quality == "MP3" and not shutil.which("ffmpeg"):
                raise ValueError("Для MP3 требуется FFmpeg в PATH")
        except (ValueError, OSError) as exc:
            messagebox.showerror("Ошибка", str(exc))
            return
        self.busy = True
        self.progress.set(0)
        self.status.set("Скачивание началось…")
        self.start_btn.config(state="disabled")
        self.pause_btn.config(state="normal")
        self.cancel_btn.config(state="normal")
        threading.Thread(target=self.worker, args=(job,), daemon=True).start()

    def worker(self, job):
        try:
            outcome = self.engine.run(job, lambda key, val: self.events.put((key, val)))
        except Exception as exc:
            self.events.put(("log", str(exc)))
            outcome = "failed"
        self.events.put(("done", outcome))

    def stop(self, reason):
        if self.engine.stop(reason):
            self.status.set("Останавливаем…")

    def pump(self):
        try:
            while True:
                event, value = self.events.get_nowait()
                if event == "progress":
                    self.progress.set(value)
                elif event in ("log", "result"):
                    self.status.set(str(value))
                elif event == "done":
                    self.busy = False
                    self.start_btn.config(state="normal")
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
        self.engine.stop("paused")
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
