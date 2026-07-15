APP_STYLESHEET = r"""
* {
    font-family: "Segoe UI Variable", "Segoe UI", sans-serif;
    font-size: 13px;
    color: #e8edf7;
}
QMainWindow, QWidget#AppRoot {
    background: #080d18;
}
QWidget#Sidebar {
    background: #0b1220;
    border-right: 1px solid #1d293b;
}
QLabel#BrandMark {
    color: #ffd45a;
    font-size: 26px;
    font-weight: 800;
}
QLabel#BrandTitle {
    color: #ffffff;
    font-size: 18px;
    font-weight: 750;
}
QLabel#Muted, QLabel.muted {
    color: #8f9bb0;
}
QLabel#PageTitle {
    font-size: 25px;
    font-weight: 750;
    color: #ffffff;
}
QLabel#SectionTitle {
    font-size: 16px;
    font-weight: 700;
    color: #ffffff;
}
QLabel#MetricValue {
    font-size: 24px;
    font-weight: 800;
    color: #ffffff;
}
QLabel#MetricLabel {
    color: #94a3b8;
    font-size: 12px;
}
QFrame#Card, QFrame#MetricCard, QFrame#ToolbarCard, QFrame#LoginCard {
    background: #0e1625;
    border: 1px solid #1d2a3d;
    border-radius: 16px;
}
QFrame#StorageHero {
    background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #111b2c, stop:0.55 #101827, stop:1 #17172b);
    border: 1px solid #2c3650;
    border-radius: 20px;
}
QFrame#DangerCard {
    background: #1a1017;
    border: 1px solid #5a2534;
    border-radius: 16px;
}
QPushButton {
    min-height: 38px;
    padding: 0 16px;
    border-radius: 10px;
    border: 1px solid #2a3850;
    background: #141f31;
    color: #e8edf7;
    font-weight: 600;
}
QPushButton:hover {
    background: #1a2940;
    border-color: #3b4d6c;
}
QPushButton:pressed {
    background: #0f1928;
}
QPushButton:disabled {
    color: #5c687a;
    background: #101621;
    border-color: #202938;
}
QPushButton#PrimaryButton {
    background: #ffcf4a;
    color: #15120a;
    border: 1px solid #ffdb72;
    font-weight: 800;
}
QPushButton#PrimaryButton:hover {
    background: #ffda70;
}
QPushButton#DangerButton {
    background: #d34b65;
    color: white;
    border: 1px solid #ef6a82;
}
QPushButton#DangerButton:hover {
    background: #e65b74;
}
QPushButton#GhostButton {
    background: transparent;
    border-color: #26354b;
}
QPushButton#NavButton {
    min-height: 44px;
    text-align: left;
    padding-left: 16px;
    border-radius: 11px;
    background: transparent;
    border: 1px solid transparent;
    color: #9eabc0;
    font-weight: 650;
}
QPushButton#NavButton:hover {
    color: #ffffff;
    background: #111c2d;
}
QPushButton#NavButton:checked {
    color: #ffffff;
    background: #18243a;
    border-color: #30415c;
}
QLineEdit, QComboBox, QSpinBox, QDoubleSpinBox {
    min-height: 40px;
    border-radius: 10px;
    border: 1px solid #283850;
    background: #0b1321;
    padding: 0 12px;
    selection-background-color: #806b26;
}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus, QDoubleSpinBox:focus {
    border: 1px solid #d6b540;
}
QComboBox::drop-down {
    width: 28px;
    border: none;
}
QComboBox QAbstractItemView {
    background: #101a2a;
    border: 1px solid #2a3b56;
    selection-background-color: #283a57;
    outline: 0;
}
QCheckBox {
    spacing: 8px;
    color: #c9d3e4;
}
QCheckBox::indicator {
    width: 17px;
    height: 17px;
    border-radius: 5px;
    border: 1px solid #3b4c67;
    background: #0b1321;
}
QCheckBox::indicator:checked {
    background: #ffcf4a;
    border-color: #ffdf79;
}
QProgressBar {
    min-height: 12px;
    max-height: 12px;
    border: none;
    border-radius: 6px;
    background: #202b3d;
    text-align: center;
    color: transparent;
}
QProgressBar::chunk {
    border-radius: 6px;
    background: qlineargradient(x1:0,y1:0,x2:1,y2:0,stop:0 #f4be35,stop:0.65 #ffd968,stop:1 #7dd3fc);
}
QTableWidget, QTreeWidget, QListWidget, QTextEdit {
    background: #0b1320;
    alternate-background-color: #0e1827;
    border: 1px solid #223149;
    border-radius: 12px;
    gridline-color: #1d2a3d;
    outline: none;
}
QTableWidget::item, QTreeWidget::item, QListWidget::item {
    min-height: 34px;
    padding: 4px 8px;
}
QTableWidget::item:selected, QTreeWidget::item:selected, QListWidget::item:selected {
    background: #243653;
    color: white;
}
QHeaderView::section {
    background: #101a2a;
    color: #94a3b8;
    border: none;
    border-bottom: 1px solid #27364d;
    padding: 10px 8px;
    font-weight: 700;
}
QScrollBar:vertical {
    width: 10px;
    background: transparent;
}
QScrollBar::handle:vertical {
    min-height: 30px;
    border-radius: 5px;
    background: #33445f;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0;
}
QTabWidget::pane {
    border: 1px solid #223149;
    border-radius: 12px;
    background: #0e1625;
}
QToolTip {
    background: #111c2c;
    color: white;
    border: 1px solid #3b4c67;
    padding: 6px;
}
QStatusBar {
    background: #0b1220;
    color: #91a0b6;
    border-top: 1px solid #1d293b;
}
"""
