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



from .dialogs import CreateRepoDialog, DeleteConfirmationDialog, LoginPage
from .dashboard import DashboardPage
from .files_page import FilesPage
from .settings_pages import SettingsPage, TransfersPage
from .workers import TaskWorker


from .window_actions import WindowActionsMixin


class AppWindow(WindowActionsMixin, QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {VERSION}")
        self.resize(1450, 900)
        self.setMinimumSize(1120, 720)
        self.settings = QSettings(ORG_NAME, APP_NAME)
        self.vault = TokenVault()
        self.thread_pool = QThreadPool.globalInstance()
        self.service: HuggingFaceService | None = None
        self.account: AccountInfo | None = None
        self.snapshot: StorageSnapshot | None = None
        self.active_workers: set[TaskWorker] = set()

        self.root_stack = QStackedWidget()
        self.root_stack.setObjectName("AppRoot")
        self.setCentralWidget(self.root_stack)
        initial_token = None
        try:
            initial_token = self.vault.load()
        except TokenVaultError:
            self.vault.clear()
        self.login_page = LoginPage(initial_token)
        self.login_page.login_requested.connect(self.login)
        self.root_stack.addWidget(self.login_page)

        self.shell: QWidget | None = None
        self.dashboard: DashboardPage | None = None
        self.files: FilesPage | None = None
        self.transfers: TransfersPage | None = None
        self.settings_page: SettingsPage | None = None
        self.page_stack: QStackedWidget | None = None
        self.refresh_timer = QTimer(self)
        self.refresh_timer.timeout.connect(self.refresh_storage)
        self.statusBar().showMessage("Hazır")

        if initial_token:
            QTimer.singleShot(150, lambda: self.login(initial_token, True))

    def run_task(
        self,
        function: Callable[..., Any],
        *args: Any,
        on_result: Callable[[Any], None] | None = None,
        on_error: Callable[[str], None] | None = None,
        on_progress: Callable[[str, int, int, str], None] | None = None,
        on_finished: Callable[[], None] | None = None,
        with_progress: bool = False,
        **kwargs: Any,
    ) -> TaskWorker:
        worker = TaskWorker(function, *args, with_progress=with_progress, **kwargs)
        self.active_workers.add(worker)
        if on_result:
            worker.signals.result.connect(on_result)
        worker.signals.error.connect(on_error or self.show_error)
        if on_progress:
            worker.signals.progress.connect(on_progress)
        if on_finished:
            worker.signals.finished.connect(on_finished)
        worker.signals.finished.connect(lambda: self.active_workers.discard(worker))
        self.thread_pool.start(worker)
        return worker

    @Slot(str, bool)
    def login(self, token: str, remember: bool) -> None:
        self.login_page.set_busy(True, "Token doğrulanıyor ve hesap bilgileri alınıyor…")
        high_performance = self.settings.value("high_performance_xet", True, bool)
        service = HuggingFaceService(token, high_performance_xet=high_performance)

        def success(account: AccountInfo) -> None:
            self.service = service
            self.account = account
            if remember:
                try:
                    self.vault.save(token)
                except TokenVaultError as exc:
                    self.statusBar().showMessage(str(exc), 8000)
            else:
                self.vault.clear()
            self._build_shell()
            self.root_stack.setCurrentWidget(self.shell)
            self.login_page.set_busy(False)
            self.refresh_storage()

        def failed(message: str) -> None:
            self.login_page.set_busy(False, message)

        self.run_task(service.authenticate, on_result=success, on_error=failed)

    def _build_shell(self) -> None:
        if self.shell:
            return
        self.shell = QWidget()
        self.shell.setObjectName("AppRoot")
        layout = QHBoxLayout(self.shell)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        sidebar = QWidget()
        sidebar.setObjectName("Sidebar")
        sidebar.setFixedWidth(240)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(18, 24, 18, 18)
        side.setSpacing(9)
        brand_row = QHBoxLayout()
        mark = QLabel("🤗")
        mark.setObjectName("BrandMark")
        brand = QLabel("HF Storage")
        brand.setObjectName("BrandTitle")
        brand_row.addWidget(mark)
        brand_row.addWidget(brand)
        brand_row.addStretch()
        account_name = QLabel(f"@{self.account.username}" if self.account else "")
        account_name.setObjectName("Muted")
        account_name.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        side.addLayout(brand_row)
        side.addWidget(account_name)
        side.addSpacing(18)

        self.page_stack = QStackedWidget()
        self.dashboard = DashboardPage()
        self.files = FilesPage()
        self.transfers = TransfersPage()
        self.settings_page = SettingsPage(self.settings)
        pages = [
            ("⌂  Genel bakış", self.dashboard),
            ("▦  Dosyalar", self.files),
            ("⇅  Aktarımlar", self.transfers),
            ("⚙  Ayarlar", self.settings_page),
        ]
        self.nav_buttons: list[QPushButton] = []
        for index, (text, page) in enumerate(pages):
            button = QPushButton(text)
            button.setObjectName("NavButton")
            button.setCheckable(True)
            button.clicked.connect(lambda checked=False, i=index: self.switch_page(i))
            side.addWidget(button)
            self.nav_buttons.append(button)
            self.page_stack.addWidget(page)
        self.nav_buttons[0].setChecked(True)
        side.addStretch()
        privacy = QLabel("● Doğrudan HF bağlantısı\nTelemetri kapalı")
        privacy.setObjectName("Muted")
        privacy.setStyleSheet("color: #79d6a7;")
        side.addWidget(privacy)
        side.addWidget(QLabel(f"v{VERSION}"))

        layout.addWidget(sidebar)
        layout.addWidget(self.page_stack, 1)
        self.root_stack.addWidget(self.shell)

        self.dashboard.refresh_requested.connect(self.refresh_storage)
        self.dashboard.repo_open_requested.connect(self.open_repo_in_files)
        self.dashboard.create_repo_requested.connect(self.create_repo)
        self.files.refresh_requested.connect(self.refresh_files)
        self.files.upload_files_requested.connect(self.upload_files)
        self.files.upload_folder_requested.connect(self.upload_folder)
        self.files.download_requested.connect(self.download_files)
        self.files.delete_requested.connect(self.delete_files)
        self.files.rename_requested.connect(self.rename_file)
        self.files.open_web_requested.connect(self.open_current_repo_web)
        self.settings_page.logout_requested.connect(self.logout)
        self.settings_page.settings_changed.connect(self.apply_settings)
        self.apply_settings()

    def switch_page(self, index: int) -> None:
        if not self.page_stack:
            return
        self.page_stack.setCurrentIndex(index)
        for i, button in enumerate(self.nav_buttons):
            button.setChecked(i == index)
        if index == 1 and self.files and self.files.current_repo():
            self.refresh_files()

    def fallback_capacity_bytes(self) -> int:
        return int(float(self.settings.value("fallback_capacity_tb", 10.0)) * 1_000_000_000_000)

    def apply_settings(self) -> None:
        minutes = float(self.settings.value("refresh_interval_minutes", 1.0))
        self.refresh_timer.setInterval(max(int(minutes * 60_000), 15_000))
        self.refresh_timer.start()
        self.statusBar().showMessage("Ayarlar kaydedildi", 3000)
        if self.service:
            self.refresh_storage()

    def refresh_storage(self) -> None:
        if not self.service or not self.dashboard:
            return
        self.statusBar().showMessage("Hugging Face depolama verileri yenileniyor…")

        def success(snapshot: StorageSnapshot) -> None:
            self.snapshot = snapshot
            self.dashboard.update_snapshot(snapshot)
            selected = self.files.current_repo_id() if self.files else None
            self.files.set_repositories(snapshot.repositories, selected) if self.files else None
            self.statusBar().showMessage("Depolama verileri güncel", 3000)

        self.run_task(
            self.service.get_storage_snapshot,
            self.fallback_capacity_bytes(),
            on_result=success,
            on_error=self.show_error,
        )

    def open_repo_in_files(self, repo_id: str) -> None:
        if not self.files:
            return
        self.files.set_repositories(self.snapshot.repositories if self.snapshot else [], repo_id)
        self.switch_page(1)
        self.refresh_files()

    def refresh_files(self) -> None:
        if not self.service or not self.files:
            return
        current = self.files.current_repo()
        if not current:
            self.files.update_files([])
            return
        repo_id, repo_type = current
        self.statusBar().showMessage(f"{repo_id} dosyaları alınıyor…")
        self.run_task(
            self.service.list_files,
            repo_id,
            repo_type,
            on_result=lambda records: (self.files.update_files(records), self.statusBar().showMessage("Dosyalar güncel", 2500)),
            on_error=self.show_error,
        )



def main() -> int:
    os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "1")
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName(ORG_NAME)
    app.setApplicationVersion(VERSION)
    app.setStyle("Fusion")
    app.setStyleSheet(APP_STYLESHEET)
    font = QFont("Segoe UI Variable", 10)
    app.setFont(font)
    window = AppWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
