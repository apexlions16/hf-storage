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

class FilesPage(QWidget):
    refresh_requested = Signal()
    upload_files_requested = Signal()
    upload_folder_requested = Signal()
    download_requested = Signal(list)
    delete_requested = Signal(list)
    rename_requested = Signal(str)
    open_web_requested = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.records: list[FileRecord] = []
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 24, 28, 24)
        root.setSpacing(14)
        top = QHBoxLayout()
        title_box = QVBoxLayout()
        title = QLabel("Dosya yöneticisi")
        title.setObjectName("PageTitle")
        subtitle = QLabel("Dosyaları, klasörleri ve büyük dosya arka uçlarını tek görünümde yönetin")
        subtitle.setObjectName("Muted")
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        self.repo_combo = QComboBox()
        self.repo_combo.setMinimumWidth(330)
        self.repo_combo.currentIndexChanged.connect(self.refresh_requested)
        web_button = QPushButton("↗ Hugging Face'te aç")
        web_button.clicked.connect(self.open_web_requested)
        top.addLayout(title_box)
        top.addStretch()
        top.addWidget(self.repo_combo)
        top.addWidget(web_button)

        toolbar = QFrame()
        toolbar.setObjectName("ToolbarCard")
        toolbar_layout = QHBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(12, 10, 12, 10)
        upload_files = QPushButton("↑ Dosya yükle")
        upload_files.setObjectName("PrimaryButton")
        upload_files.clicked.connect(self.upload_files_requested)
        upload_folder = QPushButton("⇧ Klasör yükle")
        upload_folder.clicked.connect(self.upload_folder_requested)
        download = QPushButton("↓ İndir")
        download.clicked.connect(lambda: self.download_requested.emit(self.selected_file_paths()))
        rename = QPushButton("✎ Taşı / yeniden adlandır")
        rename.clicked.connect(lambda: self.rename_requested.emit(self.single_selected_path()))
        delete = QPushButton("🗑 Kalıcı sil")
        delete.setObjectName("DangerButton")
        delete.clicked.connect(lambda: self.delete_requested.emit(self.expanded_selected_paths()))
        refresh = QPushButton("↻")
        refresh.setToolTip("Dosya listesini yenile")
        refresh.clicked.connect(self.refresh_requested)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Dosya ara…")
        self.search.setMaximumWidth(280)
        self.search.textChanged.connect(self._apply_filter)
        toolbar_layout.addWidget(upload_files)
        toolbar_layout.addWidget(upload_folder)
        toolbar_layout.addWidget(download)
        toolbar_layout.addWidget(rename)
        toolbar_layout.addWidget(delete)
        toolbar_layout.addStretch()
        toolbar_layout.addWidget(self.search)
        toolbar_layout.addWidget(refresh)

        self.tree = QTreeWidget()
        self.tree.setColumnCount(5)
        self.tree.setHeaderLabels(["Ad", "Boyut", "Arka uç", "Son değişiklik", "Son commit"])
        self.tree.setSelectionMode(QTreeWidget.SelectionMode.ExtendedSelection)
        self.tree.setAlternatingRowColors(True)
        self.tree.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.tree.customContextMenuRequested.connect(self._context_menu)
        self.tree.header().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for column in range(1, 5):
            self.tree.header().setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        root.addLayout(top)
        root.addWidget(toolbar)
        root.addWidget(self.tree, 1)

    def set_repositories(self, repositories: list[RepositoryRecord], selected_id: str | None = None) -> None:
        current = selected_id or self.current_repo_id()
        self.repo_combo.blockSignals(True)
        self.repo_combo.clear()
        for repo in repositories:
            self.repo_combo.addItem(f"{repo.repo_id}  ·  {repo.display_type}", (repo.repo_id, repo.repo_type))
        if current:
            for index in range(self.repo_combo.count()):
                if self.repo_combo.itemData(index)[0] == current:
                    self.repo_combo.setCurrentIndex(index)
                    break
        self.repo_combo.blockSignals(False)

    def current_repo(self) -> tuple[str, str] | None:
        data = self.repo_combo.currentData()
        return tuple(data) if data else None

    def current_repo_id(self) -> str | None:
        repo = self.current_repo()
        return repo[0] if repo else None

    def update_files(self, records: list[FileRecord]) -> None:
        self.records = records
        self.tree.clear()
        nodes: dict[str, QTreeWidgetItem] = {}
        record_by_path = {record.path: record for record in records}
        all_paths = set(record_by_path)
        for record in records:
            segments = record.path.strip("/").split("/") if record.path else []
            accumulated = ""
            parent: QTreeWidgetItem | None = None
            for index, segment in enumerate(segments):
                accumulated = f"{accumulated}/{segment}".strip("/")
                item = nodes.get(accumulated)
                if item is None:
                    actual = record_by_path.get(accumulated)
                    is_last = index == len(segments) - 1
                    is_folder = actual.is_folder if actual else not is_last or any(
                        path.startswith(accumulated + "/") for path in all_paths
                    )
                    icon = "📁" if is_folder else self._file_icon(segment)
                    item = QTreeWidgetItem(
                        [
                            f"{icon}  {segment}",
                            "—" if is_folder else format_bytes(actual.size if actual else 0),
                            "Klasör" if is_folder else (actual.storage_backend if actual else "Git"),
                            format_datetime(actual.last_modified) if actual else "—",
                            actual.last_commit_title if actual else "",
                        ]
                    )
                    item.setData(0, Qt.ItemDataRole.UserRole, accumulated)
                    item.setData(0, Qt.ItemDataRole.UserRole + 1, is_folder)
                    if parent:
                        parent.addChild(item)
                    else:
                        self.tree.addTopLevelItem(item)
                    nodes[accumulated] = item
                parent = item
        self.tree.expandToDepth(0)
        self._apply_filter(self.search.text())

    @staticmethod
    def _file_icon(name: str) -> str:
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        return {
            "mp4": "🎬", "mkv": "🎬", "mov": "🎬", "mp3": "🎵", "wav": "🎵", "flac": "🎵",
            "png": "🖼", "jpg": "🖼", "jpeg": "🖼", "webp": "🖼", "zip": "🗜", "7z": "🗜",
            "json": "{ }", "csv": "▦", "parquet": "▦", "safetensors": "◈", "bin": "⬡", "py": "🐍",
        }.get(ext, "📄")

    def selected_file_paths(self) -> list[str]:
        return [
            str(item.data(0, Qt.ItemDataRole.UserRole))
            for item in self.tree.selectedItems()
            if not bool(item.data(0, Qt.ItemDataRole.UserRole + 1))
        ]

    def expanded_selected_paths(self) -> list[str]:
        selected: set[str] = set()
        for item in self.tree.selectedItems():
            path = str(item.data(0, Qt.ItemDataRole.UserRole))
            is_folder = bool(item.data(0, Qt.ItemDataRole.UserRole + 1))
            if is_folder:
                selected.update(record.path for record in self.records if not record.is_folder and record.path.startswith(path + "/"))
            else:
                selected.add(path)
        return sorted(selected)

    def single_selected_path(self) -> str:
        paths = self.selected_file_paths()
        return paths[0] if len(paths) == 1 else ""

    def selected_size(self, paths: list[str]) -> int:
        wanted = set(paths)
        return sum(record.size for record in self.records if record.path in wanted)

    def _apply_filter(self, text: str) -> None:
        needle = text.strip().lower()

        def visit(item: QTreeWidgetItem) -> bool:
            child_match = False
            for index in range(item.childCount()):
                child_match = visit(item.child(index)) or child_match
            own_match = needle in str(item.data(0, Qt.ItemDataRole.UserRole)).lower()
            visible = not needle or own_match or child_match
            item.setHidden(not visible)
            return visible

        for index in range(self.tree.topLevelItemCount()):
            visit(self.tree.topLevelItem(index))

    def _context_menu(self, point: Any) -> None:
        item = self.tree.itemAt(point)
        if not item:
            return
        menu = QMenu(self)
        if not bool(item.data(0, Qt.ItemDataRole.UserRole + 1)):
            download = QAction("İndir", self)
            download.triggered.connect(lambda: self.download_requested.emit(self.selected_file_paths()))
            rename = QAction("Taşı / yeniden adlandır", self)
            rename.triggered.connect(lambda: self.rename_requested.emit(self.single_selected_path()))
            menu.addAction(download)
            menu.addAction(rename)
        delete = QAction("Kalıcı sil", self)
        delete.triggered.connect(lambda: self.delete_requested.emit(self.expanded_selected_paths()))
        menu.addSeparator()
        menu.addAction(delete)
        menu.exec(self.tree.viewport().mapToGlobal(point))
