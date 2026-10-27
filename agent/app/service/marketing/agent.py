import os
import json
from dotenv import load_dotenv
from openai.types.chat import ChatCompletion

from app.common.enum.event_type import EventType
from app.common.dto.agent_result import AdContent
from app.common.dto.product import Product
from app.service.marketing.prompts import SYSTEM_PROMPT, build_user_prompt
from app.service.marketing.tools import TOOLS, TOOL_MAP
from app.service.marketing.product_samples import PRODUCT_SAMPLES
from app.util.ai_client import get_ai_client
from app.util.json_output import parse_json_object

load_dotenv()

AI_MODEL = os.getenv("AI_MODEL")

POKE_API_URL = os.getenv("POKE_API_URL")

# 응답 JSON 파싱 실패 시 재호출 포함 최대 시도 횟수
MAX_PARSE_ATTEMPTS = 2

client = get_ai_client()

async def run_with_tools(response: ChatCompletion, messages: list):
    message = response.choices[0].message

    # assistant 메시지를 저장
    messages.append(message.model_dump())

    # tool call 없으면 종료
    if not message.tool_calls:
        return response

    for tool_call in message.tool_calls:
        name = tool_call.function.name
        args = json.loads(tool_call.function.arguments or "{}")

        result = TOOL_MAP[name](**args)

        messages.append({
            "role": "tool",
            "tool_call_id": tool_call.id,
            "content": json.dumps(result, ensure_ascii=False)
        })

    # 2차 호출
    response = await client.chat.completions.create(
        model=AI_MODEL,
        messages=messages,
        tools=TOOLS
    )

    return response

async def product_marketing(event_type: EventType, is_sample: bool, product_new: Product, product_old: Product | None):
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
                "content": build_user_prompt(event_type, new, old)
            }
        ]
    response = await client.chat.completions.create(
        model=AI_MODEL,
        messages=messages,
        tools=TOOLS
    )

    response = await run_with_tools(response, messages)

    return response.choices[0].message.content


# 마케팅 초안 생성 (/generate용): 응답을 AdContent로 파싱하고, 실패하면 1회 재호출
async def generate_draft(event_type: EventType, is_sample: bool, product_new: Product, product_old: Product | None) -> AdContent:
    last_error: Exception | None = None
    for _ in range(MAX_PARSE_ATTEMPTS):
        raw = await product_marketing(event_type, is_sample, product_new, product_old)
        try:
            return AdContent.model_validate(parse_json_object(raw))
        except ValueError as e:     # JSON 파싱 실패, 필수 필드 누락 (ValidationError 포함)
            last_error = e
    raise ValueError(f"마케팅 응답 파싱 실패: {last_error}")
