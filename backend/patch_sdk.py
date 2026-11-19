"""Fix SDK 2.4.10's binary stream callback pointer (embedded NULs are valid)."""
from pathlib import Path
import agora.rtc

path = Path(agora.rtc.__file__).parent / "_ctypes_handle" / "_local_user_observer.py"
old = "ON_STREAM_MESSAGE_CALLBACK = ctypes.CFUNCTYPE(None, AGORA_HANDLE, user_id_t, ctypes.c_int, ctypes.c_char_p, ctypes.c_size_t)"
new = old.replace("ctypes.c_char_p", "ctypes.c_void_p")
source = path.read_text()
if old not in source and new not in source:
    raise RuntimeError("SDK callback changed; review the binary stream patch")
path.write_text(source.replace(old, new))
