from dataclasses import dataclass
import os
from urllib.parse import urlparse


@dataclass(frozen=True)
class Settings:
    access_token: str = ""
    agora_app_id: str = ""
    agora_certificate: str = ""
    agora_customer_id: str = ""
    agora_customer_secret: str = ""
    murf_key: str = ""
    public_url: str = ""
    voice: str = "Jaya"
    voice_rate: int = 15
    voice_provider: str = "murf-direct"
    auto_languages: tuple = ("en-IN", "hi-IN", "ta-IN", "te-IN")
    max_seconds: int = 900

    @classmethod
    def from_env(cls):
        return cls(
            auto_languages=tuple(x.strip() for x in os.getenv("AUTO_SOURCE_LANGUAGES", "en-IN,hi-IN,ta-IN,te-IN").split(",") if x.strip()),
            access_token=os.getenv("ANYVOICE_ACCESS_TOKEN", ""),
            agora_app_id=os.getenv("AGORA_APP_ID", ""),
            agora_certificate=os.getenv("AGORA_APP_CERTIFICATE", ""),
            agora_customer_id=os.getenv("AGORA_CUSTOMER_ID", ""),
            agora_customer_secret=os.getenv("AGORA_CUSTOMER_SECRET", ""),
            murf_key=os.getenv("MURF_API_KEY", ""),
            public_url=os.getenv("ANYVOICE_PUBLIC_URL", "").rstrip("/"),
            voice=os.getenv("MURF_VOICE_ID", "Jaya"),
            voice_rate=max(-50, min(50, int(os.getenv("MURF_SPEECH_RATE", "15")))),
            voice_provider=os.getenv("VOICE_PROVIDER", "murf-direct"),
            max_seconds=min(900, max(60, int(os.getenv("MAX_SESSION_SECONDS", "900")))),
        )

    def missing(self):
        values = {
            "ANYVOICE_ACCESS_TOKEN": self.access_token, "AGORA_APP_ID": self.agora_app_id,
            "AGORA_APP_CERTIFICATE": self.agora_certificate, "AGORA_CUSTOMER_ID": self.agora_customer_id,
            "AGORA_CUSTOMER_SECRET": self.agora_customer_secret, "MURF_API_KEY": self.murf_key,
            "ANYVOICE_PUBLIC_URL": self.public_url,
        }
        return [key for key, value in values.items() if not value or value.startswith("replace-with-")]

    def validate(self):
        if not 2 <= len(self.auto_languages) <= 4 or len(set(self.auto_languages)) != len(self.auto_languages) or "te-IN" not in self.auto_languages:
            raise ValueError("AUTO_SOURCE_LANGUAGES needs 2-4 unique languages including te-IN")
        if self.voice_provider not in ("murf-direct", "agora-engine"):
            raise ValueError("VOICE_PROVIDER must be murf-direct or agora-engine")
        if self.missing():
            raise ValueError("Configure: " + ", ".join(self.missing()))
        for value in (self.agora_app_id, self.agora_certificate):
            if len(value) != 32 or any(c not in "0123456789abcdefABCDEF" for c in value):
                raise ValueError("Agora App ID and Certificate must be 32 hexadecimal characters")
        url = urlparse(self.public_url)
        if url.scheme != "https" or not url.netloc or url.username or url.password or url.query or url.fragment:
            raise ValueError("ANYVOICE_PUBLIC_URL must be this backend's public HTTPS address")
        if len(self.access_token) < 6:
            raise ValueError("ANYVOICE_ACCESS_TOKEN must contain at least 6 characters")
