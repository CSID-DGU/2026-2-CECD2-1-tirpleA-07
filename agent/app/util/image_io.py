import base64
import io
import logging

import httpx
from PIL import Image, UnidentifiedImageError

logger = logging.getLogger(__name__)

DOWNLOAD_TIMEOUT_SECONDS = 10
MAX_DOWNLOAD_BYTES = 30*1024*1024
MAX_SIDE = 1024
JPEG_QUALITY = 90
_HEADERS = {"User-Agent": "triplea-agent/1.0"}

def shrink_to_jpeg(data: bytes) -> bytes:
    with Image.open(io.BytesIO(data)) as image:
        image = image.convert("RGB")
        image.thumbnail((MAX_SIDE, MAX_SIDE))
        out = io.BytesIO()
        image.save(out, format="JPEG", quality=JPEG_QUALITY)
        return out.getvalue()

def to_data_url(data:bytes, mime: str = "image/jpeg") -> str:
    return f"data:{mime};base64," + base64.b64encode(data).decode()

# 상품 이미지 URL을 내려받아 긴 변 1024px 이하 JPEG 바이트로 반환
# 이미지가 없거나 받을 수 없으면 None (호출하는 쪽은 텍스트만으로 진행)
async def load_product_image(url: str | None) -> bytes | None:
    if not url or not url.startswith(("http://", "https://")):
        return None
    try:
        async with httpx.AsyncClient(
                timeout=DOWNLOAD_TIMEOUT_SECONDS,
                follow_redirects=True,
                headers=_HEADERS,
        ) as client:
            response = await client.get(url)
            response.raise_for_status()

        if len(response.content) > MAX_DOWNLOAD_BYTES:
            logger.warning("상품 이미지가 너무 큼 (%d bytes): %s", len(response.content), url)
            return None

        return shrink_to_jpeg(response.content)
    except (httpx.HTTPError, UnidentifiedImageError, OSError) as e:
        logger.warning("상품 이미지를 불러오지 못함 (%s): %s", type(e).__name__, url)
        return None