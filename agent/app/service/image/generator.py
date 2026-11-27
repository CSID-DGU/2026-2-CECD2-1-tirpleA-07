import base64
import logging
import os

import httpx
from dotenv import load_dotenv

from app.util.image_io import shrink_to_jpeg, to_data_url

load_dotenv()
logger = logging.getLogger(__name__)

# 호출마다 크레딧이 들기 때문에 기본은 끔
IMAGE_GENERATION_ENABLED = os.getenv("IMAGE_GENERATION_ENABLED", "false").lower() == "true"

GATEWAY_BASE_URL = os.getenv("GATEWAY_BASE_URL", "https://factchat-cloud.mindlogic.ai/v1/gateway")
GATEWAY_API_KEY = os.getenv("GATEWAY_API_KEY")
IMAGE_MODEL = os.getenv("IMAGE_MODEL", "gpt-image-2.5-sunburst")
IMAGE_QUALITY = os.getenv("IMAGE_QUALITY", "low")
GENERATE_TIMEOUT_SECONDS = 90

MOOD_PROMPT = (
    "Turn this travel product photo into a premium social media advertisement image. "
    "Keep the main landmark, scenery and composition of the original photo recognizable and undistorted. "
    "Only enhance lighting, color grading and mood to feel inviting and aspirational. "
    "Do not add any text, letters, logos, watermarks or people's faces."
)

def _model_params(model: str) -> dict:
    if model.startswith("gpt-image"):
        return {"n": 1, "size": "1024x1024", "quality": IMAGE_QUALITY}
    if model.startswith("gemini"):
        return {"number_of_images": 1, "image_size": "1K", "aspect_ratio": "1:1"}
    return {"num_outputs": 1}

async def generate_ad_image(product_image: bytes) -> bytes:
    # GATEWAY_API_KEY가 없으면 RuntimeError
    if not GATEWAY_API_KEY:
        raise RuntimeError("GATEWAY_API_KEY가 설정되지 않음")
    # 요청: 공통 필드 + 모델별 파라미터
    body = {
        "model": IMAGE_MODEL,
        "prompt": MOOD_PROMPT,
        "input_images": [to_data_url(product_image)],
        **_model_params(IMAGE_MODEL),
    }
    # 게이트웨이 호출
    async with httpx.AsyncClient(timeout=GENERATE_TIMEOUT_SECONDS) as client:
        response = await client.post(
            f"{GATEWAY_BASE_URL}/images/generate/",
            headers={"Authorization": f"Bearer {GATEWAY_API_KEY}"},
            json=body,
        )
    # 실패 응답
    if response.status_code != 200:
        raise RuntimeError(f"이미지 생성 실패 HTTP {response.status_code}: {response.text[:300]}")
    # 응답에서 이미지 꺼내기
    result = response.json()
    items = result.get("data") or []
    url = items[0].get("url") if items else None
    if not url or not url.startswith("data:image"):
        raise RuntimeError(f"이미지 생성 응답에 이미지 없음: {str(result)[:300]}")
    #크레딧 기록 및 디코딩
    logger.info("이미지 생성 완료: model=%s, credits=%s", IMAGE_MODEL, result.get("credits_charged"))
    return shrink_to_jpeg(base64.b64decode(url.split(",",1)[1]))