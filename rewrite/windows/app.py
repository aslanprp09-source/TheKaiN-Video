"""Tkinter proof of concept; install yt-dlp first."""
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from engine import Options, command_for
from download_queue import DownloadQueue, default_data_root
from jobs import JobStatus as S, ResumeCapability as R, STATUS_RU
from extractor import SourceAnalyzer
from domain import AnalyzeResult, DownloadError, ErrorCode


class App(tk.Tk):
    def __init__(self, download_queue=None):
        super().__init__()
        self.title("TheKaiN Video — Rebuild 0.1")
        self.geometry("800x540")
        self.events = queue.Queue()
        self.downloads = download_queue or DownloadQueue(default_data_root())
        self.downloads.callback = lambda job: self.events.put(('job', job))
        self.jobs = {}
        self.selected_job = None
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
        self.task_status = tk.StringVar(value="Очередь готова: до двух одновременных задач.")
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
        self.start_btn = ttk.Button(controls, text="Добавить в очередь", command=self.start, state="disabled")
        self.start_btn.pack(side="left")
        self.pause_btn = ttk.Button(controls, text="Пауза", command=self.pause, state="disabled")
        self.pause_btn.pack(side="left", padx=7)
        self.resume_btn = ttk.Button(controls, text="Продолжить", command=self.resume, state="disabled")
        self.resume_btn.pack(side="left", padx=(0, 7))
        self.retry_btn = ttk.Button(controls, text="Повторить", command=self.retry, state="disabled")
        self.retry_btn.pack(side="left", padx=(0, 7))
        self.cancel_btn = ttk.Button(controls, text="Отменить", command=self.cancel, state="disabled")
        self.cancel_btn.pack(side="left")
        self.progressbar = ttk.Progressbar(frm, variable=self.progress, maximum=100)
        self.progressbar.pack(fill="x", pady=7)
        ttk.Label(frm, textvariable=self.status, wraplength=740).pack(anchor="w")
        ttk.Label(frm, textvariable=self.task_status, wraplength=740).pack(anchor="w", pady=(4, 0))
        self.tasks = ttk.Treeview(frm, columns=('title', 'state', 'bytes'), show='headings', height=8, selectmode='browse')
        for key, text, width in (('title', 'Задача', 260), ('state', 'Состояние', 240), ('bytes', 'Получено, байт', 130)):
            self.tasks.heading(key, text=text)
            self.tasks.column(key, width=width)
        self.tasks.pack(fill='both', expand=True, pady=(10, 0))
        self.tasks.bind('<<TreeviewSelect>>', self.select_job)
        self.after(100, self.pump)
        self.url.trace_add("write", self.url_changed)
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.downloads.start()

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
            queued = self.downloads.enqueue(job, title=self.media.display_title)
        except (ValueError, OSError) as exc:
            messagebox.showerror("Ошибка", str(exc))
            return
        self.selected_job = queued.id
        self.progress.set(0)
        self.status.set("Добавлено в очередь. Можно определить следующую ссылку.")

    def pause(self):
        if self.selected_job:
            self.downloads.pause(self.selected_job)

    def resume(self):
        if self.selected_job:
            self.downloads.resume(self.selected_job)

    def retry(self):
        if self.selected_job:
            self.downloads.retry(self.selected_job)

    def cancel(self):
        if self.selected_job:
            self.downloads.cancel(self.selected_job)

    def select_job(self, *_):
        selected = self.tasks.selection()
        if selected:
            self.selected_job = selected[0]
            self.render_job(self.jobs[self.selected_job])

    def render_job(self, job):
        self.progressbar.stop()
        self.progressbar.config(mode='determinate')
        self.progress.set(job.percent or 0)
        if job.state in (S.VERIFYING, S.POSTPROCESSING, S.PUBLISHING) or job.state == S.DOWNLOADING and job.percent is None:
            self.progressbar.config(mode='indeterminate')
            self.progressbar.start(30)
        self.task_status.set(job.error.message if job.error else STATUS_RU[job.state])
        self.pause_btn.config(state='normal' if job.state == S.DOWNLOADING and job.resume == R.YES else 'disabled')
        self.resume_btn.config(state='normal' if job.state == S.PAUSED else 'disabled')
        self.retry_btn.config(state='normal' if job.state == S.FAILED else 'disabled')
        self.cancel_btn.config(state='disabled' if job.state in (S.COMPLETED, S.CANCELED, S.FAILED, S.PUBLISHING) else 'normal')

    def apply_job(self, job):
        self.jobs[job.id] = job
        values = (job.title, STATUS_RU[job.state], str(job.progress_bytes))
        if self.tasks.exists(job.id):
            self.tasks.item(job.id, values=values)
        else:
            self.tasks.insert('', 'end', iid=job.id, values=values)
        if self.selected_job is None:
            self.selected_job = job.id
            self.tasks.selection_set(job.id)
        if job.id == self.selected_job:
            if self.tasks.selection() != (job.id,):
                self.tasks.selection_set(job.id)
            self.render_job(job)

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
                elif event == 'job':
                    self.apply_job(value)
        except queue.Empty:
            pass
        self.after(100, self.pump)

    def close(self):
        self.analysis_cancel.set()
        threading.Thread(target=self.downloads.close, daemon=False).start()
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
