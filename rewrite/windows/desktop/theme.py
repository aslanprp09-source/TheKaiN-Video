"""Approved TheKaiN palettes, focus rings and logical-pixel control sizes."""
from pathlib import Path
PALETTES = {
    'dark': dict(bg='#0D1016', surface='#171C25', raised='#222936', text='#F5F6F8',
                 muted='#AEB7C5', border='#303846', accent='#E5484D', hover='#F06467',
                 good='#36B37E', warning='#E5AD46', tint='#302027'),
    'light': dict(bg='#F6F7F9', surface='#FFFFFF', raised='#EDF0F4', text='#1A202B',
                  muted='#586477', border='#DCE1E8', accent='#C52A38', hover='#A91E2B',
                  good='#17794F', warning='#8A5B00', tint='#FCEDEF'),
}


def stylesheet(mode, reduced_motion=False):
    c = PALETTES[mode]
    chevron = (Path(__file__).with_name('assets') / 'chevron.svg').as_posix()
    check = (Path(__file__).with_name('assets') / 'check.svg').as_posix()
    return f'''
    * {{ font-family: "Segoe UI", "DejaVu Sans", sans-serif; font-size: 14px;
         color: {c['text']}; selection-background-color: {c['accent']};
         selection-color: white; widget-animation-duration: {0 if reduced_motion else 120}; }}
    QMainWindow, QDialog, QWidget#shell, QWidget#page {{ background: {c['bg']}; }}
    QWidget {{ background: transparent; }}
    QFrame#sidebar {{ background: {c['surface']}; border-right: 1px solid {c['border']}; }}
    QFrame#card {{ background: {c['surface']}; border: 1px solid {c['border']}; border-radius: 16px; }}
    QFrame#emptyCard {{ background: {c['surface']}; border: 1px dashed {c['border']}; border-radius: 16px; }}
    QLabel[role="muted"] {{ color: {c['muted']}; }}
    QLabel[role="eyebrow"] {{ color: {c['muted']}; font-size: 12px; font-weight: 600; }}
    QLabel[role="heading"] {{ font-size: 28px; font-weight: 700; }}
    QLabel[role="section"] {{ font-size: 17px; font-weight: 600; }}
    QLabel[role="brand"] {{ font-size: 17px; font-weight: 700; }}
    QLabel[role="error"] {{ color: {c['accent']}; }}
    QLabel[role="status"] {{ background: {c['raised']}; color: {c['muted']}; padding: 5px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; }}
    QLabel[tone="good"] {{ color: {c['good']}; }}
    QLabel[tone="error"] {{ color: {c['accent']}; }}
    QLabel[tone="warning"] {{ color: {c['warning']}; }}
    QLabel#thumbnail {{ background: {c['raised']}; border-radius: 12px; color: {c['muted']}; }}
    QLineEdit, QComboBox {{ background: {c['surface']}; border: 1px solid {c['border']};
        border-radius: 10px; padding: 0px 12px; min-height: 44px; }}
    QLineEdit:focus, QComboBox:focus {{ border: 2px solid {c['accent']}; }}
    QLineEdit:disabled, QComboBox:disabled {{ color: {c['muted']}; background: {c['raised']}; }}
    QComboBox {{ padding-right: 30px; }}
    QComboBox::drop-down {{ border: none; width: 28px; }}
    QComboBox::down-arrow {{ image: url("{chevron}"); width: 12px; height: 12px; }}
    QComboBox QAbstractItemView {{ background: {c['surface']}; color: {c['text']}; selection-background-color: {c['raised']}; border: 1px solid {c['border']}; padding: 8px; }}
    QComboBox QAbstractItemView::item {{ min-height: 36px; }}
    QPushButton, QToolButton {{ background: {c['surface']}; border: 1px solid {c['border']};
        border-radius: 10px; padding: 0px 14px; min-height: 44px; font-weight: 600; }}
    QPushButton:hover, QToolButton:hover {{ background: {c['raised']}; }}
    QPushButton:pressed, QToolButton:pressed {{ border-color: {c['accent']}; }}
    QPushButton:focus, QToolButton:focus, QCheckBox:focus {{ border: 2px solid {c['accent']}; }}
    QPushButton:disabled, QToolButton:disabled {{ color: {c['muted']}; background: {c['raised']}; border-color: {c['border']}; }}
    QPushButton[primary="true"] {{ background: {c['accent']}; border-color: {c['accent']}; color: white; }}
    QPushButton[primary="true"]:hover {{ background: {c['hover']}; }}
    QPushButton[primary="true"]:disabled {{ background: {c['raised']}; color: {c['muted']}; border-color: {c['border']}; }}
    QPushButton[nav="true"] {{ border: none; text-align: left; background: transparent; min-height: 48px; padding: 0px 14px; }}
    QPushButton[nav="true"]:checked {{ background: {c['tint']}; color: {c['accent']}; }}
    QPushButton[nav="true"]:focus {{ border: 2px solid {c['accent']}; }}
    QTabBar::tab {{ color: {c['muted']}; background: transparent; border-bottom: 2px solid transparent; min-height: 44px; padding: 0px 14px; }}
    QTabBar::tab:selected {{ color: {c['accent']}; border-bottom: 2px solid {c['accent']}; }}
    QTabBar::tab:focus {{ background: {c['raised']}; }}
    QProgressBar {{ min-height: 6px; max-height: 6px; border: none; background: {c['raised']}; border-radius: 3px; text-align: center; }}
    QProgressBar::chunk {{ background: {c['accent']}; border-radius: 3px; }}
    QScrollArea {{ border: none; background: transparent; }}
    QScrollBar:vertical {{ background: transparent; width: 8px; margin: 0px; }}
    QScrollBar::handle:vertical {{ background: {c['border']}; border-radius: 4px; min-height: 32px; }}
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
    QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{ background: transparent; }}
    QCheckBox {{ min-height: 44px; spacing: 10px; }}
    QCheckBox::indicator {{ width: 20px; height: 20px; border: 1px solid {c['border']}; border-radius: 5px; background: {c['surface']}; }}
    QCheckBox::indicator:checked {{ background: {c['accent']}; border-color: {c['accent']}; image: url("{check}"); }}
    QToolTip {{ background: {c['surface']}; color: {c['text']}; border: 1px solid {c['border']}; padding: 8px; }}
    '''
