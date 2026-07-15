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



class WindowActionsMixin:
    def create_repo(self) -> None:
        if not self.service or not self.account:
            return
        dialog = CreateRepoDialog(self.account.username, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        name, repo_type, private = dialog.values()
        if "/" not in name:
            name = f"{self.account.username}/{name}"
        self.statusBar().showMessage("Depo oluşturuluyor…")

        def success(repo_id: str) -> None:
            self.statusBar().showMessage(f"{repo_id} oluşturuldu", 4000)
            self.refresh_storage()
            QTimer.singleShot(1200, lambda: self.open_repo_in_files(repo_id))

        self.run_task(self.service.create_repository, name, repo_type, private, on_result=success)

    def upload_files(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(self, "Yüklenecek dosyaları seç")
        if paths:
            self._start_upload(paths)

    def upload_folder(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "Yüklenecek klasörü seç")
        if path:
            self._start_upload([path])

    def _start_upload(self, paths: list[str]) -> None:
        if not self.service or not self.files or not self.transfers:
            return
        current = self.files.current_repo()
        if not current:
            self.show_error("Önce bir depo seçin.")
            return
        repo_id, repo_type = current
        destination, ok = self._text_input(
            "Hedef klasör",
            "Depo içindeki hedef klasör (boş bırakılırsa kök):",
            "",
        )
        if not ok:
            return
        commit_message, ok = self._text_input(
            "Commit mesajı",
            "Commit mesajı:",
            "Batch upload with HF Storage",
        )
        if not ok:
            return
        self.transfers.start_task("Batch upload", f"{len(paths)} seçim → {repo_id}")
        self.switch_page(2)

        def success(urls: list[str]) -> None:
            self.transfers.finish_task(f"Yükleme tamamlandı • {len(urls)} commit")
            self.statusBar().showMessage("Yükleme tamamlandı", 5000)
            self.refresh_storage()
            self.refresh_files()

        self.run_task(
            self.service.upload,
            repo_id,
            repo_type,
            paths,
            destination,
            commit_message,
            with_progress=True,
            on_progress=self.transfers.update_progress,
            on_result=success,
            on_error=lambda message: (self.transfers.fail_task(message), self.show_error(message)),
        )

    def download_files(self, paths: list[str]) -> None:
        if not paths:
            self.show_error("İndirmek için en az bir dosya seçin.")
            return
        if len(paths) > 1:
            self.show_error("Bu sürümde indirme işlemini dosya başına başlatın.")
            return
        if not self.service or not self.files:
            return
        current = self.files.current_repo()
        if not current:
            return
        destination = QFileDialog.getExistingDirectory(self, "İndirme klasörü seç")
        if not destination:
            return
        repo_id, repo_type = current
        self.statusBar().showMessage("Dosya indiriliyor…")
        self.run_task(
            self.service.download_file,
            repo_id,
            repo_type,
            paths[0],
            destination,
            on_result=lambda path: self.statusBar().showMessage(f"İndirildi: {path}", 7000),
        )

    def delete_files(self, paths: list[str]) -> None:
        if not paths:
            self.show_error("Silmek için dosya veya klasör seçin.")
            return
        if not self.service or not self.files or not self.transfers:
            return
        current = self.files.current_repo()
        if not current:
            return
        total_size = self.files.selected_size(paths)
        dialog = DeleteConfirmationDialog(len(paths), total_size, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        repo_id, repo_type = current
        self.transfers.start_task("Kalıcı silme", f"{len(paths)} dosya → {repo_id}")
        self.switch_page(2)

        def success(result: dict[str, int]) -> None:
            self.transfers.finish_task(
                f"{result['deleted']} yol silindi; {result['purged']} büyük dosya nesnesi kalıcı temizlendi."
            )
            self.refresh_storage()
            self.refresh_files()

        self.run_task(
            self.service.delete_paths,
            repo_id,
            repo_type,
            paths,
            True,
            with_progress=True,
            on_progress=self.transfers.update_progress,
            on_result=success,
            on_error=lambda message: (self.transfers.fail_task(message), self.show_error(message)),
        )

    def rename_file(self, old_path: str) -> None:
        if not old_path:
            self.show_error("Yeniden adlandırmak için yalnızca bir dosya seçin.")
            return
        if not self.service or not self.files:
            return
        new_path, ok = self._text_input("Taşı / yeniden adlandır", "Yeni depo yolu:", old_path)
        if not ok or not new_path.strip():
            return
        current = self.files.current_repo()
        if not current:
            return
        self.run_task(
            self.service.rename_path,
            current[0],
            current[1],
            old_path,
            new_path,
            on_result=lambda _: (self.statusBar().showMessage("Dosya taşındı", 4000), self.refresh_files(), self.refresh_storage()),
        )

    def open_current_repo_web(self) -> None:
        if not self.service or not self.files:
            return
        current = self.files.current_repo()
        if current:
            QDesktopServices.openUrl(QUrl(self.service.repository_url(current[0], current[1])))

    def logout(self) -> None:
        answer = QMessageBox.question(
            self,
            "Çıkış yap",
            "Kayıtlı token silinsin ve oturum kapatılsın mı?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.vault.clear()
        self.service = None
        self.account = None
        self.snapshot = None
        self.refresh_timer.stop()
        self.login_page.token_input.clear()
        self.login_page.set_busy(False, "Oturum kapatıldı.")
        self.root_stack.setCurrentWidget(self.login_page)

    @staticmethod
    def _text_input(title: str, label: str, initial: str) -> tuple[str, bool]:
        dialog = QDialog()
        dialog.setWindowTitle(title)
        dialog.setMinimumWidth(480)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel(label))
        field = QLineEdit(initial)
        field.selectAll()
        layout.addWidget(field)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Ok)
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.DialogCode.Accepted
        return field.text(), accepted

    def show_error(self, message: str) -> None:
        self.statusBar().showMessage(message, 10000)
        QMessageBox.critical(self, "HF Storage", message)

    def closeEvent(self, event: QCloseEvent) -> None:
        if self.active_workers:
            answer = QMessageBox.question(
                self,
                "Aktif işlem var",
                "Devam eden işlemler var. Uygulamayı kapatmak yüklemeyi yarıda bırakabilir. Yine de çıkılsın mı?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            )
            if answer != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
        event.accept()
