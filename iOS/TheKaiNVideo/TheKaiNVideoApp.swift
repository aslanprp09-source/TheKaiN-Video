import SwiftUI
import AVKit
import Foundation

private enum Theme {
    static let bg = Color(red: 0.055, green: 0.060, blue: 0.074)
    static let panel = Color(red: 0.107, green: 0.113, blue: 0.130)
    static let red = Color(red: 0.94, green: 0.10, blue: 0.17)
    static let gray = Color(red: 0.68, green: 0.69, blue: 0.72)
}
private struct VideoItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

final class DownloadCenter: NSObject, ObservableObject, URLSessionDownloadDelegate {
    enum Phase { case ready, reading, downloading, pausing, paused, failed, completed }
    @Published var phase: Phase = .ready
    @Published var message = "Вставьте прямую ссылку на файл видео"
    @Published var progress: Double = 0
    @Published var files: [URL] = []
    @Published var remaining = 0
    private var queue: [URL] = []
    private var currentURL: URL?
    private var task: URLSessionDownloadTask?
    private var resumeData: Data?
    private var aborted = false
    private lazy var downloader = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    private var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TheKaiN Video", isDirectory: true)
    }
    override init() {
        super.init()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        refresh()
    }
    func refresh() {
        files = ((try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? [])
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
    private func directURL(_ string: String) -> URL? {
        guard let url = URL(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else { return nil }
        return url
    }
    func begin(_ text: String, playlist: Bool) {
        guard phase != .downloading && phase != .reading && phase != .pausing else { return }
        guard let url = directURL(text) else {
            message = "Введите корректную http(s) ссылку"
            phase = .failed
            return
        }
        aborted = false
        queue = []
        resumeData = nil
        remaining = 0
        progress = 0
        if playlist {
            guard ["m3u", "txt"].contains(url.pathExtension.lowercased()) else {
                phase = .failed
                message = "На iOS плейлист — это .m3u или .txt с прямыми URL на видео. Страницы YouTube/VK требуют отдельного сервера."
                return
            }
            phase = .reading
            message = "Загружаю список видео..."
            URLSession.shared.dataTask(with: url) { [weak self] data, _, error in
                DispatchQueue.main.async {
                    guard let self = self, !self.aborted else { return }
                    guard error == nil, let data = data,
                          let text = String(data: data, encoding: .utf8) else {
                        self.phase = .failed
                        self.message = "Не удалось открыть плейлист"
                        return
                    }
                    self.queue = Array(text.components(separatedBy: .newlines)
                        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                        .filter { !$0.isEmpty && !$0.hasPrefix("#") }
                        .compactMap(self.directURL).prefix(100))
                    guard !self.queue.isEmpty else {
                        self.phase = .failed
                        self.message = "В плейлисте нет прямых ссылок"
                        return
                    }
                    self.next()
                }
            }.resume()
        } else {
            queue = [url]
            next()
        }
    }
    private func next() {
        guard !aborted else { return }
        guard !queue.isEmpty else {
            phase = .completed; remaining = 0; progress = 1
            message = "Загрузка завершена"
            refresh()
            return
        }
        let url = queue.removeFirst()
        if url.pathExtension.lowercased() == "m3u8" {
            phase = .failed
            message = "HLS (.m3u8) в этой iOS-бета версии не поддерживается"
            return
        }
        currentURL = url
        remaining = queue.count + 1
        progress = 0
        phase = .downloading
        message = "Загрузка: " + (url.lastPathComponent.isEmpty ? "видео" : url.lastPathComponent)
        task = downloader.downloadTask(with: url)
        task?.resume()
    }
    func pause() {
        guard phase == .downloading, let current = task else { return }
        phase = .pausing
        message = "Сохраняю состояние..."
        current.cancel(byProducingResumeData: { [weak self] data in
            DispatchQueue.main.async {
                guard let self = self, !self.aborted else { return }
                self.resumeData = data
                self.task = nil
                self.phase = .paused
                self.message = data == nil ? "Пауза; сервер может начать файл заново" : "Пауза — данные сохранены"
            }
        })
    }
    func resume() {
        guard phase == .paused else { return }
        phase = .downloading
        message = "Продолжаю загрузку..."
        if let data = resumeData {
            task = downloader.downloadTask(withResumeData: data)
        } else if let url = currentURL {
            progress = 0
            task = downloader.downloadTask(with: url)
        }
        task?.resume()
    }
    func cancel() {
        aborted = true; task?.cancel()
        task = nil; currentURL = nil; queue = []
        resumeData = nil; remaining = 0; progress = 0
        phase = .ready; message = "Загрузка отменена"
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        DispatchQueue.main.async {
            if self.phase == .downloading && totalBytesExpectedToWrite > 0 {
                self.progress = min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
                )
            }
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        let mime = (downloadTask.response?.mimeType ?? "").lowercased()
        if mime == "text/html" || mime == "application/json" {
            DispatchQueue.main.async {
                self.phase = .failed
                self.message = "Получена страница, а не видео. Нужна прямая ссылка на медиафайл."
            }
            return
        }
        let name = currentURL?.lastPathComponent.isEmpty == false ? currentURL!.lastPathComponent : "video.mp4"
        let safe = String(name.replacingOccurrences(of: "/", with: "_").prefix(120))
        var dest = folder.appendingPathComponent(safe)
        var count = 2
        while FileManager.default.fileExists(atPath: dest.path) {
            dest = folder.appendingPathComponent("\(count)-\(safe)")
            count += 1
        }
        let result: Result<Void, Error>
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            result = .success(())
        } catch {
            result = .failure(error)
        }
        DispatchQueue.main.async {
            guard !self.aborted else { return }
            switch result {
            case .success:
                self.task = nil
                self.resumeData = nil
                self.refresh()
                self.next()
            case .failure(let error):
                self.phase = .failed
                self.message = "Ошибка сохранения: " + error.localizedDescription
            }
        }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error else { return }
        DispatchQueue.main.async {
            guard !self.aborted, self.phase != .paused, self.phase != .pausing else { return }
            self.phase = .failed
            self.message = "Ошибка загрузки: " + error.localizedDescription
        }
    }
}

@main
struct TheKaiNVideoApp: App {
    var body: some Scene {
        WindowGroup { MainScreen().preferredColorScheme(.dark) }
    }
}

struct MainScreen: View {
    @StateObject private var manager = DownloadCenter()
    @State private var link = ""
    @State private var playlist = false
    @State private var playerFile: VideoItem?
    private var isValid: Bool {
        let url = URL(string: link)
        return url?.host != nil && ["http", "https"].contains(url?.scheme?.lowercased() ?? "")
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    Image("IconPreview").resizable().frame(width: 60, height: 60)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("TheKaiN Video").font(.system(size: 26, weight: .bold, design: .rounded))
                        Text("Видео и плейлисты").font(.footnote).foregroundStyle(Theme.gray)
                    }
                }
                HStack(spacing: 10) {
                    modeButton("Видео", isActive: !playlist) { playlist = false }
                    modeButton("Плейлист", isActive: playlist) { playlist = true }
                }
                card {
                    Text("Ссылка").font(.headline)
                    HStack {
                        Image(systemName: "link").foregroundStyle(Theme.gray)
                        TextField(playlist ? "https://site.com/list.m3u" : "https://site.com/video.mp4",
                                  text: $link)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                        if !link.isEmpty {
                            Button { link = "" } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.gray)
                            }
                            .accessibilityLabel("Очистить ссылку")
                        }
                    }
                    .padding(13)
                    .background(Theme.bg, in: RoundedRectangle(cornerRadius: 12))
                }
                card {
                    Text("Качество и формат").font(.headline)
                    Menu {
                        Text("Оригинальное качество — без перекодирования")
                    } label: {
                        HStack {
                            Image(systemName: "slider.horizontal.3").foregroundStyle(Theme.red)
                            Text("Оригинал")
                            Spacer()
                            Image(systemName: "chevron.down")
                        }.padding(13).background(Theme.bg, in: RoundedRectangle(cornerRadius: 12))
                    }
                    Text("Для выбора качества из вариантов сайта необходим внешний сервер извлечения.")
                        .font(.caption).foregroundStyle(Theme.gray)
                }
                card {
                    Text("Загрузка").font(.headline)
                    Text(manager.message).font(.subheadline).foregroundStyle(Theme.gray)
                    if manager.phase == .downloading || manager.phase == .paused || manager.phase == .pausing {
                        ProgressView(value: manager.progress).tint(Theme.red)
                        HStack {
                            Text("\(Int(manager.progress * 100))%")
                            Spacer()
                            Text("Осталось: \(manager.remaining)")
                        }.font(.caption.monospacedDigit()).foregroundStyle(Theme.gray)
                        if manager.phase != .pausing {
                            HStack {
                                Button {
                                    if manager.phase == .downloading { manager.pause() }
                                    else { manager.resume() }
                                } label: {
                                    Label(manager.phase == .paused ? "Продолжить" : "Пауза",
                                          systemImage: manager.phase == .paused ? "play.fill" : "pause.fill")
                                        .frame(maxWidth: .infinity)
                                }.buttonStyle(RedButton())
                                Button("Отмена") { manager.cancel() }
                                    .buttonStyle(RedButton(secondary: true))
                            }
                        }
                    } else {
                        Button {
                            manager.begin(link, playlist: playlist)
                        } label: {
                            Label(playlist ? "Скачать плейлист" : "Скачать видео",
                                  systemImage: "arrow.down.to.line").frame(maxWidth: .infinity)
                        }.buttonStyle(RedButton()).disabled(!isValid)
                    }
                }
                card {
                    HStack {
                        Text("Сохранённые файлы").font(.headline)
                        Spacer()
                        Button { manager.refresh() } label: {
                            Image(systemName: "arrow.clockwise").foregroundStyle(Theme.gray)
                        }
                    }
                    if manager.files.isEmpty {
                        Text("Видео появятся здесь и в приложении «Файлы».")
                            .font(.subheadline).foregroundStyle(Theme.gray)
                    }
                    ForEach(manager.files, id: \.path) { file in
                        HStack(spacing: 10) {
                            Image(systemName: "film").foregroundStyle(Theme.red)
                            Text(file.lastPathComponent).font(.subheadline).lineLimit(1)
                            Spacer()
                            if ["mp4", "mov", "m4v"].contains(file.pathExtension.lowercased()) {
                                Button { playerFile = VideoItem(url: file) } label: {
                                    Image(systemName: "play.circle.fill")
                                }
                            }
                            ShareLink(item: file) {
                                Image(systemName: "square.and.arrow.up")
                            }
                        }.padding(.vertical, 6)
                    }
                }
                Text("iOS beta: прямые URL на видео и .m3u/.txt плейлисты. Страницы YouTube/VK, HLS и DRM не поддерживаются. Пауза работает, когда сервер предоставляет данные для возобновления.")
                    .font(.footnote).foregroundStyle(Theme.gray)
            }
            .padding(20)
        }
        .background(Theme.bg.ignoresSafeArea())
        .sheet(item: $playerFile) { item in
            NavigationStack {
                VideoPlayer(player: AVPlayer(url: item.url))
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Закрыть") { playerFile = nil }
                        }
                    }
            }
        }
        .onOpenURL { link = $0.absoluteString }
    }
    private func modeButton(_ text: String, isActive: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).fontWeight(.semibold).frame(maxWidth: .infinity).padding(14)
                .background(isActive ? Theme.red : Theme.panel, in: RoundedRectangle(cornerRadius: 13))
        }.buttonStyle(.plain)
    }
    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 17))
    }
}
private struct RedButton: ButtonStyle {
    var secondary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.subheadline.weight(.semibold)).padding(14)
            .background(secondary ? Theme.panel : Theme.red, in: RoundedRectangle(cornerRadius: 12))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}