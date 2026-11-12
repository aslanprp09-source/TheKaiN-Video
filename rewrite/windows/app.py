"""TheKaiN Video desktop entry point. The download engine stays Qt-independent."""
import sys


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--packaging-smoke':
        from release_smoke import main as smoke
        return smoke(sys.argv[2])
    from PySide6.QtWidgets import QApplication
    from download_queue import default_data_root
    from desktop.window import MainWindow
    application = QApplication(sys.argv)
    application.setApplicationName('TheKaiN Video')
    application.setOrganizationName('TheKaiN')
    application.setStyle('Fusion')
    window = MainWindow(default_data_root())
    window.show()
    return application.exec()


if __name__ == '__main__':
    raise SystemExit(main())
