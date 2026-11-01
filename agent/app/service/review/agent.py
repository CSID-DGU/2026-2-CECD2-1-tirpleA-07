import os
import json
from dotenv import load_dotenv

from app.common.enum.event_type import EventType
from app.common.dto.agent_result import AdContent
from app.common.dto.product import Product
from app.service.review.prompts import SYSTEM_PROMPT, build_user_prompt
from app.service.marketing.product_samples import PRODUCT_SAMPLES
from app.service.review.votes import ReviewOutcome, build_outcome
from app.util.ai_client import get_ai_client
from app.util.json_output import parse_json_object

load_dotenv()

AI_MODEL = os.getenv("AI_MODEL")

# 응답 JSON 파싱 실패 시 재호출 포함 최대 시도 횟수
MAX_PARSE_ATTEMPTS = 2

client = get_ai_client()

# 필요 시 툴 사용 (marketing/agent.py 참고)

# 심의 LLM을 호출해 투표 5개의 응답 문자열을 반환
async def request_review(
        event_type: EventType,
        is_sample: bool,
        product_new: Product,
        product_old: Product | None,
        marketing_content: str
    ) -> list[str]:
    # 파라미터에 따라 샘플 데이터로 테스트 가능
    if is_sample:
        new = PRODUCT_SAMPLES[0] if event_type == EventType.NEW else PRODUCT_SAMPLES[1]
        old = None if event_type == EventType.NEW else PRODUCT_SAMPLES[2]
    else:
        new = product_new
        old = product_old

    messages = [
            {
                "role": "system",
                "content": SYSTEM_PROMPT
            },
            {
                "role": "user",
                "content": build_user_prompt(event_type, new, old, marketing_content)
            }
        ]
    response = await client.chat.completions.create(
        model=AI_MODEL,
        messages=messages,
        temperature=0.7,
        n=5
    )

    return [choice.message.content for choice in response.choices]

async def product_reviewing(
        event_type: EventType,
        is_sample: bool,
        product_new: Product,
        product_old: Product | None,
        marketing_content: str
    ):
    contents = await request_review(event_type, is_sample, product_new, product_old, marketing_content)

    selected_idx = None
    for idx, content in enumerate(contents):
        result_json = json.loads(content)
        # is_accepted 여부 체크
        if not(result_json["is_accepted"]):
            selected_idx = idx
            break  # 가장 먼저 나온 거절 기준
        else:
            if selected_idx is None:
                selected_idx = idx

    return contents[selected_idx]

# 초안 심의 (/generate용): 투표 결과를 구조화하고, 파싱에 실패하면 1회 재호출
async def review_draft(
        event_type: EventType,
        is_sample: bool,
        product_new: Product,
        product_old: Product | None,
        draft: AdContent
    ) -> ReviewOutcome:
    marketing_content = draft.model_dump_json(exclude_none=True)

    last_error: Exception | None = None
    for _ in range(MAX_PARSE_ATTEMPTS):
        contents = await request_review(event_type, is_sample, product_new, product_old, marketing_content)
        try:
            outcome = build_outcome([parse_json_object(content) for content in contents])
        except ValueError as e:     # JSON 파싱 실패, 판정·필수 필드 누락 (ValidationError 포함)
            last_error = e
            continue

        # 심의는 문구만 수정하므로 초안의 이미지는 그대로 유지
        if outcome.content is not None and outcome.content.image_url is None and draft.image_url:
            outcome = ReviewOutcome(
                accepted=outcome.accepted,
                content=outcome.content.model_copy(update={"image_url": draft.image_url}),
                review=outcome.review,
            )
        return outcome

    raise ValueError(f"심의 응답 파싱 실패: {last_error}")
