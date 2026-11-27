import os
import uuid
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

IMAGE_DIR = Path(os.getenv("IMAGE_DIR", "/data/ad-images"))
IMAGE_URL_PREFIX = os.getenv("IMAGE_URL_PREFIX", "/api/v1/ad-images")

def save_ad_image(data: bytes) -> str:
    IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    name = f"{uuid.uuid4().hex}.jpg"
    tmp = IMAGE_DIR / f".{name}.tmp"
    tmp.write_bytes(data)
    tmp.replace(IMAGE_DIR / name)
    return f"{IMAGE_URL_PREFIX}/{name}"