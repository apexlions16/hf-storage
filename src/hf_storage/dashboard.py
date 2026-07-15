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

class DashboardPage(QWidget):
    refresh_requested = Signal()
    repo_open_requested = Signal(str)
    create_repo_requested = Signal()

    def __init__(self) -> None:
        super().__init__()
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 24, 28, 24)
        root.setSpacing(18)
        top = QHBoxLayout()
        titles = QVBoxLayout()
        self.title = QLabel("Depolama merkezi")
        self.title.setObjectName("PageTitle")
        self.subtitle = QLabel("Hugging Face depolarınızın canlı görünümü")
        self.subtitle.setObjectName("Muted")
        titles.addWidget(self.title)
        titles.addWidget(self.subtitle)
        refresh = QPushButton("↻  Yenile")
        refresh.clicked.connect(self.refresh_requested)
        create = QPushButton("＋ Yeni depo")
        create.setObjectName("PrimaryButton")
        create.clicked.connect(self.create_repo_requested)
        top.addLayout(titles)
        top.addStretch()
        top.addWidget(refresh)
        top.addWidget(create)

        hero = QFrame()
        hero.setObjectName("StorageHero")
        hero_layout = QVBoxLayout(hero)
        hero_layout.setContentsMargins(24, 22, 24, 22)
        hero_layout.setSpacing(12)
        hero_header = QHBoxLayout()
        hero_title = QLabel("Toplam Large File Storage")
        hero_title.setObjectName("SectionTitle")
        self.sync_label = QLabel("Henüz senkronize edilmedi")
        self.sync_label.setObjectName("Muted")
        hero_header.addWidget(hero_title)
        hero_header.addStretch()
        hero_header.addWidget(self.sync_label)
        self.storage_value = QLabel("—")
        self.storage_value.setObjectName("MetricValue")
        self.storage_detail = QLabel("Kullanım verileri alınıyor")
        self.storage_detail.setObjectName("Muted")
        self.storage_progress = QProgressBar()
        self.storage_progress.setRange(0, 10000)
        self.storage_progress.setValue(0)
        hero_layout.addLayout(hero_header)
        hero_layout.addWidget(self.storage_value)
        hero_layout.addWidget(self.storage_detail)
        hero_layout.addWidget(self.storage_progress)

        metrics = QHBoxLayout()
        metrics.setSpacing(14)
        self.remaining_card = MetricCard("Kalan alan")
        self.repo_card = MetricCard("Depo sayısı")
        self.largest_card = MetricCard("En büyük depo")
        metrics.addWidget(self.remaining_card)
        metrics.addWidget(self.repo_card)
        metrics.addWidget(self.largest_card)

        section = QHBoxLayout()
        label = QLabel("Depolar")
        label.setObjectName("SectionTitle")
        self.repo_search = QLineEdit()
        self.repo_search.setPlaceholderText("Depolarda ara…")
        self.repo_search.setMaximumWidth(320)
        self.repo_search.textChanged.connect(self._filter_table)
        section.addWidget(label)
        section.addStretch()
        section.addWidget(self.repo_search)

        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["Depo", "Tür", "Görünürlük", "Kullanım", "Güncelleme"])
        self.table.setAlternatingRowColors(True)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for column in range(1, 5):
            self.table.horizontalHeader().setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        self.table.doubleClicked.connect(self._open_selected)

        root.addLayout(top)
        root.addWidget(hero)
        root.addLayout(metrics)
        root.addLayout(section)
        root.addWidget(self.table, 1)

    def update_snapshot(self, snapshot: StorageSnapshot) -> None:
        self.storage_value.setText(format_bytes(snapshot.used_bytes))
        source_note = "Hugging Face oranından otomatik" if snapshot.capacity_source == "huggingface-percentage" else "yerel gösterge kapasitesi"
        self.storage_detail.setText(
            f"{format_bytes(snapshot.estimated_capacity_bytes)} kapasitenin %{snapshot.used_percent:.3f}'i kullanılıyor • {source_note}"
        )
        self.storage_progress.setValue(int(snapshot.used_percent * 100))
        self.sync_label.setText(f"Son güncelleme: {snapshot.fetched_at.strftime('%H:%M:%S')}")
        self.remaining_card.set_value(format_bytes(snapshot.remaining_bytes), "Tahmini kullanılabilir alan")
        self.repo_card.set_value(str(len(snapshot.repositories)), "Model + Dataset + Space + Bucket")
        largest = max(snapshot.repositories, key=lambda repo: repo.storage, default=None)
        self.largest_card.set_value(
            format_bytes(largest.storage) if largest else "—",
            largest.repo_id if largest else "Henüz depo yok",
        )
        self.table.setRowCount(0)
        for repo in snapshot.repositories:
            row = self.table.rowCount()
            self.table.insertRow(row)
            name = QTableWidgetItem(repo.repo_id)
            name.setData(Qt.ItemDataRole.UserRole, repo.repo_id)
            type_item = QTableWidgetItem(repo.display_type)
            visibility = QTableWidgetItem("🔒 Özel" if repo.is_private else "🌐 Public")
            size = QTableWidgetItem(format_bytes(repo.storage))
            size.setData(Qt.ItemDataRole.UserRole, repo.storage)
            updated = QTableWidgetItem(format_datetime(repo.updated_at))
            for column, item in enumerate((name, type_item, visibility, size, updated)):
                self.table.setItem(row, column, item)
        self._filter_table(self.repo_search.text())

    def _filter_table(self, text: str) -> None:
        needle = text.strip().lower()
        for row in range(self.table.rowCount()):
            value = self.table.item(row, 0).text().lower()
            self.table.setRowHidden(row, bool(needle and needle not in value))

    def _open_selected(self) -> None:
        row = self.table.currentRow()
        if row >= 0:
            self.repo_open_requested.emit(self.table.item(row, 0).text())
