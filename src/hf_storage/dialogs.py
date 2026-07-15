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


class LoginPage(QWidget):
    login_requested = Signal(str, bool)

    def __init__(self, initial_token: str | None = None) -> None:
        super().__init__()
        root = QHBoxLayout(self)
        root.setContentsMargins(70, 50, 70, 50)
        root.setSpacing(60)

        hero = QVBoxLayout()
        hero.setSpacing(18)
        brand = QLabel("🤗")
        brand.setObjectName("BrandMark")
        title = QLabel("Hugging Face depolamanız,\nWindows'ta gerçek bir disk gibi.")
        title.setObjectName("PageTitle")
        title.setWordWrap(True)
        title.setStyleSheet("font-size: 34px; line-height: 1.2;")
        subtitle = QLabel(
            "Model, dataset ve Space depolarınızı tek ekrandan yönetin. Çoklu yüklemeler batch commit olarak gider; "
            "token yalnızca bu bilgisayarda Windows DPAPI ile şifrelenir."
        )
        subtitle.setObjectName("Muted")
        subtitle.setWordWrap(True)
        subtitle.setMaximumWidth(600)
        privacy = QFrame()
        privacy.setObjectName("Card")
        privacy_layout = QVBoxLayout(privacy)
        privacy_layout.setContentsMargins(18, 16, 18, 16)
        privacy_layout.addWidget(QLabel("🔒  Telemetri yok • Sunucu yok • Aracı hesap yok"))
        privacy_note = QLabel("Dosyalar ve token doğrudan Hugging Face ile iletişim kurar; uygulama geliştiricisine gönderilmez.")
        privacy_note.setObjectName("Muted")
        privacy_note.setWordWrap(True)
        privacy_layout.addWidget(privacy_note)
        hero.addStretch()
        hero.addWidget(brand)
        hero.addWidget(title)
        hero.addWidget(subtitle)
        hero.addWidget(privacy)
        hero.addStretch()

        card = QFrame()
        card.setObjectName("LoginCard")
        card.setFixedWidth(440)
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(30, 30, 30, 30)
        card_layout.setSpacing(15)
        card_title = QLabel("Hesabınıza bağlanın")
        card_title.setObjectName("SectionTitle")
        card_note = QLabel("Read + write yetkili User Access Token girin.")
        card_note.setObjectName("Muted")
        self.token_input = QLineEdit(initial_token or "")
        self.token_input.setPlaceholderText("hf_••••••••••••••••••••")
        self.token_input.setEchoMode(QLineEdit.EchoMode.Password)
        self.token_input.returnPressed.connect(self._submit)
        self.show_token = QCheckBox("Tokenı göster")
        self.show_token.toggled.connect(
            lambda checked: self.token_input.setEchoMode(
                QLineEdit.EchoMode.Normal if checked else QLineEdit.EchoMode.Password
            )
        )
        self.remember = QCheckBox("Bu bilgisayarda güvenli şekilde sakla")
        self.remember.setChecked(True)
        permissions = QLabel(
            "Tam erişim gereklidir: depoları listelemek, dosya yüklemek, silmek ve kalıcı büyük dosya temizliği yapmak için."
        )
        permissions.setObjectName("Muted")
        permissions.setWordWrap(True)
        self.login_button = QPushButton("Hugging Face ile giriş yap")
        self.login_button.setObjectName("PrimaryButton")
        self.login_button.clicked.connect(self._submit)
        token_link = QPushButton("Token oluşturma sayfasını aç ↗")
        token_link.setObjectName("GhostButton")
        token_link.clicked.connect(lambda: QDesktopServices.openUrl(QUrl("https://huggingface.co/settings/tokens")))
        self.status_label = QLabel("")
        self.status_label.setObjectName("Muted")
        self.status_label.setWordWrap(True)
        card_layout.addWidget(card_title)
        card_layout.addWidget(card_note)
        card_layout.addSpacing(5)
        card_layout.addWidget(self.token_input)
        card_layout.addWidget(self.show_token)
        card_layout.addWidget(self.remember)
        card_layout.addWidget(permissions)
        card_layout.addSpacing(4)
        card_layout.addWidget(self.login_button)
        card_layout.addWidget(token_link)
        card_layout.addWidget(self.status_label)
        card_layout.addStretch()

        root.addLayout(hero, 1)
        root.addWidget(card)

    def _submit(self) -> None:
        token = self.token_input.text().strip()
        if not token:
            self.status_label.setText("Token alanı boş bırakılamaz.")
            return
        self.login_requested.emit(token, self.remember.isChecked())

    def set_busy(self, busy: bool, message: str = "") -> None:
        self.login_button.setDisabled(busy)
        self.token_input.setDisabled(busy)
        self.status_label.setText(message)
        self.login_button.setText("Bağlanıyor…" if busy else "Hugging Face ile giriş yap")


class CreateRepoDialog(QDialog):
    def __init__(self, username: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Yeni depo")
        self.setMinimumWidth(420)
        layout = QVBoxLayout(self)
        form = QFormLayout()
        self.name = QLineEdit()
        self.name.setPlaceholderText(f"{username}/arsivim veya arsivim")
        self.repo_type = QComboBox()
        self.repo_type.addItem("Dataset", "dataset")
        self.repo_type.addItem("Model", "model")
        self.repo_type.addItem("Space", "space")
        self.private = QCheckBox("Özel depo")
        self.private.setChecked(True)
        form.addRow("Depo adı", self.name)
        form.addRow("Tür", self.repo_type)
        form.addRow("Görünürlük", self.private)
        note = QLabel("Kişisel dosya depolaması için Dataset türü önerilir.")
        note.setObjectName("Muted")
        note.setWordWrap(True)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Ok)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Oluştur")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addLayout(form)
        layout.addWidget(note)
        layout.addWidget(buttons)

    def values(self) -> tuple[str, str, bool]:
        return self.name.text().strip(), str(self.repo_type.currentData()), self.private.isChecked()


class DeleteConfirmationDialog(QDialog):
    def __init__(self, count: int, total_size: int, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Kalıcı silme onayı")
        self.setMinimumWidth(520)
        layout = QVBoxLayout(self)
        card = QFrame()
        card.setObjectName("DangerCard")
        card_layout = QVBoxLayout(card)
        title = QLabel(f"⚠  {count} dosya kalıcı olarak silinecek")
        title.setObjectName("SectionTitle")
        detail = QLabel(
            f"Seçili veri: {format_bytes(total_size)}. Önce ana daldan silme commit'i oluşturulur; ardından LFS/Xet büyük dosya "
            "nesneleri geçmiş referansları yeniden yazılarak temizlenir. Bu işlem geri alınamaz ve eski commit'lerdeki dosyaları da bozar."
        )
        detail.setWordWrap(True)
        detail.setObjectName("Muted")
        card_layout.addWidget(title)
        card_layout.addWidget(detail)
        self.permanent = QCheckBox("Büyük dosya nesnelerini Storage Usage alanından da kalıcı temizle")
        self.permanent.setChecked(True)
        self.permanent.setDisabled(True)
        prompt = QLabel("Devam etmek için aşağıya SİL yazın:")
        self.phrase = QLineEdit()
        self.phrase.setPlaceholderText("SİL")
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Ok)
        self.ok_button = buttons.button(QDialogButtonBox.StandardButton.Ok)
        self.ok_button.setText("Kalıcı olarak sil")
        self.ok_button.setObjectName("DangerButton")
        self.ok_button.setEnabled(False)
        self.phrase.textChanged.connect(lambda text: self.ok_button.setEnabled(text.strip().upper() == "SİL"))
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(card)
        layout.addWidget(self.permanent)
        layout.addWidget(prompt)
        layout.addWidget(self.phrase)
        layout.addWidget(buttons)
