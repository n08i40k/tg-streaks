import json
import re
import traceback

from android.util import Log
from base_plugin import BasePlugin

__id__ = "dev-fastparse"
__name__ = "Dev Fastparse"
__description__ = "Заменяет квадратичный разбор входящего буфера в dev-сервере exteraGram на инкрементальный"
__author__ = "@n08i40k_extera"
__version__ = "1.0.0"
__icon__ = "tiktok_streak/4"
__min_version__ = "12.1.1"

TAG = "dev-fastparse"

# recv chunk the dev server reads with; the stock value makes multi-megabyte
# uploads take thousands of round trips through the parser
BUFFER_SIZE = 1 << 18

_TOKENS = re.compile(rb'[{}"\\]')

# fileno -> [depth, in_string, scanned, obj_start, skip]
_states = {}


def _log(message):
    Log.i(TAG, str(message))


def _process_buffer(cls, buffer, client_socket):
    try:
        key = client_socket.fileno()
    except Exception:
        key = None

    state = _states.get(key)
    if state is None or state[2] > len(buffer):
        state = [0, False, 0, 0, 0]

    depth, in_string, scanned, obj_start, skip = state
    consumed = 0

    for match in _TOKENS.finditer(buffer, scanned):
        index = match.start()
        if index < skip:
            continue

        token = buffer[index]

        if in_string:
            if token == 0x5C:  # backslash
                skip = index + 2
            elif token == 0x22:  # quote
                in_string = False
        elif token == 0x22:
            in_string = True
        elif token == 0x7B:  # {
            if depth == 0:
                obj_start = index
            depth += 1
        elif token == 0x7D:  # }
            depth -= 1
            if depth <= 0:
                depth = 0
                chunk = bytes(buffer[obj_start : index + 1])
                consumed = index + 1
                try:
                    command = json.loads(chunk.decode("utf-8"))
                except Exception as e:
                    _log(f"dropping malformed command: {e}")
                else:
                    cls._process_command(command, client_socket)

    if consumed:
        rest = buffer[consumed:]
        obj_start = max(0, obj_start - consumed)
        skip = max(0, skip - consumed)
    else:
        rest = buffer

    if key is not None:
        if rest:
            _states[key] = [depth, in_string, len(rest), obj_start, skip]
        else:
            _states.pop(key, None)

    return rest


class DevFastparsePlugin(BasePlugin):
    def on_plugin_load(self):
        try:
            import dev_server

            cls = dev_server.DevServer
            if getattr(cls._process_buffer, "__name__", "") == "_process_buffer_fast":
                _log("already patched")
                return

            self._original_process_buffer = cls.__dict__["_process_buffer"]
            self._original_buffer_size = cls.BUFFER_SIZE

            _process_buffer.__name__ = "_process_buffer_fast"
            cls._process_buffer = classmethod(_process_buffer)
            cls.BUFFER_SIZE = BUFFER_SIZE

            _log(
                f"patched: BUFFER_SIZE {self._original_buffer_size} -> {cls.BUFFER_SIZE}, "
                f"SOCKET_TIMEOUT={cls.SOCKET_TIMEOUT}"
            )
        except BaseException:
            _log("patch failed:\n" + traceback.format_exc())

    def on_plugin_unload(self):
        try:
            import dev_server

            cls = dev_server.DevServer
            original = getattr(self, "_original_process_buffer", None)
            if original is not None:
                cls._process_buffer = original
                cls.BUFFER_SIZE = self._original_buffer_size
                _states.clear()
                _log("restored the original parser")
        except BaseException:
            _log("restore failed:\n" + traceback.format_exc())
