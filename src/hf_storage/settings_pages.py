from __future__ import annotations

import os
import sys
import traceback
import webbrowser
from collections.abc import Callable
from pathlib import Path
from typing import Any

from PySide6.QtCore import QObject, QRunnable, QSettings, Qt, QThreadPool, QTimer, Signal, Slot
from PySide6.QtGui import QAction, QCloseEvent, QDesktopServices, QFont, QIcon
from PySide6.QtCore import QUrl
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QListWidget,
    QMainWindow,
    QMenu,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSizePolicy,
    QSpacerItem,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from .models import AccountInfo, FileRecord, RepositoryRecord, StorageSnapshot, format_bytes, format_datetime
from .security import TokenVault, TokenVaultError
from .service import HFStorageError, HuggingFaceService
from .theme import APP_STYLESHEET

APP_NAME = "HF Storage"
ORG_NAME = "ApexLions"
VERSION = "0.1.0"



from .workers import MetricCard

class TransfersPage(QWidget):
    def __init__(self) -> None:
        super().__init__()
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 24, 28, 24)
        root.setSpacing(16)
        title = QLabel("Aktarımlar")
        title.setObjectName("PageTitle")
        subtitle = QLabel("Batch upload, commit ve silme görevlerinin canlı ilerlemesi")
        subtitle.setObjectName("Muted")
        self.card = QFrame()
        self.card.setObjectName("Card")
        card_layout = QVBoxLayout(self.card)
        card_layout.setContentsMargins(22, 20, 22, 20)
        self.task_title = QLabel("Aktif görev yok")
        self.task_title.setObjectName("SectionTitle")
        self.task_detail = QLabel("Yeni bir yükleme veya silme işlemi burada görünür.")
        self.task_detail.setObjectName("Muted")
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setValue(0)
        self.log = QTextEdit()
        self.log.setReadOnly(True)
        self.log.setMinimumHeight(240)
        card_layout.addWidget(self.task_title)
        card_layout.addWidget(self.task_detail)
        card_layout.addWidget(self.progress)
        card_layout.addWidget(self.log)
        root.addWidget(title)
        root.addWidget(subtitle)
        root.addWidget(self.card)
        root.addStretch()

    def start_task(self, title: str, detail: str) -> None:
        self.task_title.setText(title)
        self.task_detail.setText(detail)
        self.progress.setValue(0)
        self.log.clear()
        self.log.append(f"Başlatıldı: {detail}")

    def update_progress(self, stage: str, current: int, total: int, message: str) -> None:
        value = int((current / max(total, 1)) * 1000)
        if stage in {"committing", "purging"} and current >= total:
            value = 920
        self.progress.setValue(min(value, 990))
        self.task_detail.setText(message)
        self.log.append(f"[{stage}] {message}")

    def finish_task(self, message: str) -> None:
        self.progress.setValue(1000)
        self.task_detail.setText(message)
        self.log.append(message)

    def fail_task(self, message: str) -> None:
        self.task_detail.setText("İşlem başarısız")
        self.log.append(f"HATA: {message}")


class SettingsPage(QWidget):
    logout_requested = Signal()
    settings_changed = Signal()

    def __init__(self, settings: QSettings) -> None:
        super().__init__()
        self.settings = settings
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 24, 28, 24)
        root.setSpacing(18)
        title = QLabel("Ayarlar")
        title.setObjectName("PageTitle")
        subtitle = QLabel("Depolama göstergesi, yenileme ve güvenlik tercihleri")
        subtitle.setObjectName("Muted")
        card = QFrame()
        card.setObjectName("Card")
        form = QFormLayout(card)
        form.setContentsMargins(22, 22, 22, 22)
        form.setSpacing(16)
        self.capacity = QDoubleSpinBox()
        self.capacity.setRange(0.1, 1000.0)
        self.capacity.setDecimals(1)
        self.capacity.setSuffix(" TB")
        self.capacity.setValue(float(settings.value("fallback_capacity_tb", 10.0)))
        self.interval = QDoubleSpinBox()
        self.interval.setRange(0.25, 60)
        self.interval.setDecimals(2)
        self.interval.setSuffix(" dk")
        self.interval.setValue(float(settings.value("refresh_interval_minutes", 1.0)))
        self.xet = QCheckBox("HF_XET_HIGH_PERFORMANCE kullan")
        self.xet.setChecked(settings.value("high_performance_xet", True, bool))
        self.capacity_note = QLabel(
            "Hugging Face storagePercent döndürürse kapasite otomatik hesaplanır. Dönmezse bu yerel değer progress bar için kullanılır."
        )
        self.capacity_note.setObjectName("Muted")
        self.capacity_note.setWordWrap(True)
        save = QPushButton("Ayarları kaydet")
        save.setObjectName("PrimaryButton")
        save.clicked.connect(self._save)
        form.addRow("Yedek kapasite", self.capacity)
        form.addRow("Otomatik yenileme", self.interval)
        form.addRow("Yükleme performansı", self.xet)
        form.addRow("", self.capacity_note)
        form.addRow("", save)

        security = QFrame()
        security.setObjectName("DangerCard")
        security_layout = QVBoxLayout(security)
        security_layout.setContentsMargins(22, 20, 22, 20)
        security_title = QLabel("Oturum ve token")
        security_title.setObjectName("SectionTitle")
        security_note = QLabel(
            "Kayıtlı token Windows DPAPI ile yalnızca mevcut Windows kullanıcısının çözebileceği biçimde saklanır. Çıkış, yerel token dosyasını siler."
        )
        security_note.setObjectName("Muted")
        security_note.setWordWrap(True)
        logout = QPushButton("Çıkış yap ve tokenı sil")
        logout.setObjectName("DangerButton")
        logout.clicked.connect(self.logout_requested)
        security_layout.addWidget(security_title)
        security_layout.addWidget(security_note)
        security_layout.addWidget(logout, 0, Qt.AlignmentFlag.AlignLeft)

        root.addWidget(title)
        root.addWidget(subtitle)
        root.addWidget(card)
        root.addWidget(security)
        root.addStretch()

    def _save(self) -> None:
        self.settings.setValue("fallback_capacity_tb", self.capacity.value())
        self.settings.setValue("refresh_interval_minutes", self.interval.value())
        self.settings.setValue("high_performance_xet", self.xet.isChecked())
        self.settings.sync()
        self.settings_changed.emit()
