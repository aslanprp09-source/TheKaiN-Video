"""TheKaiN Video desktop entry point. The download engine stays Qt-independent."""
import sys
from PySide6.QtWidgets import QApplication
from download_queue import default_data_root
from desktop.window import MainWindow


def main():
    application = QApplication(sys.argv)
    application.setApplicationName('TheKaiN Video')
    application.setOrganizationName('TheKaiN')
    application.setStyle('Fusion')
    window = MainWindow(default_data_root())
    window.show()
    return application.exec()


if __name__ == '__main__':
    raise SystemExit(main())
