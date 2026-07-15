from . import window as _window

_window.VERSION = "0.1.1"
AppWindow = _window.AppWindow
main = _window.main

__all__ = ["AppWindow", "main"]
