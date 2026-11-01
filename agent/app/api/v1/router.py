import json
import logging

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.common.enum.event_type import EventType
from app.common.dto.agent_result import AgentResult, FailedStage, GenerateRequest
from app.common.dto.product import Product
from app.service.marketing import generate_draft, product_marketing
from app.service.review import product_reviewing, review_draft
from app.util.discord_logger import discord_send_message
from app.util.sns_adapter import call_facebook_api

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/agent",
    tags=["v1"],
)

class AgentEventRequest(BaseModel):
    # 이벤트 유형
    event_type: EventType = Field(alias="eventType")

    # 상품 ID
    product_id: int = Field(alias="productId")

    # 신규 상품 정보
    product_new: Product = Field(alias="productNew")
    # 기존 상품 정보 (DISCOUNT의 경우)
    product_old: Product | None = Field(alias="productOld", default=None)

    # 샘플 적용 여부
    is_sample: bool = Field(alias="isSample", default=False)

class ReviewRequest(AgentEventRequest):
    # 마케팅 json 데이터
    marketing_json: str = Field(alias="marketingJson")

@router.post("/marketing")
async def start_agent_flow(body: AgentEventRequest):
    # 이벤트 수신 메시지 출력
    discord_send_message(
        "🚀 Agent Automation Flow Started",
        f"- {body.event_type} 유형 이벤트 수신\n- productId: {body.product_id}",
        None,
        3447003
    )
    ai_response: str = await product_marketing(body.event_type, body.is_sample, body.product_new, body.product_old)
    # 생성 완료 메시지 출력
    discord_send_message(
        "✨ Marketing Content Generated",
        ai_response,
        None,
        9109759
    )
    ai_response = await product_reviewing(body.event_type, body.is_sample, body.product_new, body.product_old, ai_response)
    # 검수 완료 메시지 출력
    discord_send_message(
        "🔍 Content reviewed and re-generated",
        ai_response,
        None,
        16753920
    )
    ai_response_json = json.loads(ai_response)
    is_accepted = ai_response_json.get("is_accepted", "")
    if is_accepted:
        content = "\n".join([
                ai_response_json.get("title", ""),
                ai_response_json.get("body", ""),
                ai_response_json.get("cta", ""),
                " ".join(ai_response_json.get("hashtags", []))
            ])
        post_url = call_facebook_api(content)
        # SNS 발행 메시지 출력
        discord_send_message(
            "📢 SNS Publishing Completed",
            ai_response_json.get("title", ""),
            post_url,
            7855479
        )
    else:
        post_url = None
        # 심의 거절 메시지 출력
        discord_send_message(
            "🚨 Content Rejected",
            ai_response_json.get("reason", ""),
            post_url,
            15548997
        )
    # 임시 return 값
    return {
        "eventType": body.event_type,
        "productId": body.product_id,
        "productNew": body.product_new,
        "productOld": body.product_old,
        "aiResponse": ai_response,
        "post_url": post_url, # 네이밍 오류, 백엔드 대응 시 제거
        "postUrl": post_url
    }

# 테스트를 위한 엔드포인트
@router.post("/review")
async def start_review_flow(body: ReviewRequest):
    ai_response: str = await product_reviewing(body.event_type, body.is_sample, body.product_new, body.product_old, body.marketing_json)
    # 임시 return 값
    return {
        "eventType": body.event_type,
        "productId": body.product_id,
        "productNew": body.product_new,
        "productOld": body.product_old,
        "aiResponse": ai_response
    }

# 생성 + 심의 (발행은 api가 담당)
# 처리 결과와 무관하게 항상 200으로 AgentResult를 반환하고, 실패하면 실패 단계와 메시지만 담는다
@router.post("/generate", response_model=AgentResult)
async def generate(body: GenerateRequest) -> AgentResult:
    tag = f"eventId: {body.event_id} / attemptId: {body.attempt_id}"
    _notify(
        "🚀 Agent Generation Started",
        f"- {body.event_type.value} 유형 이벤트 수신\n- {tag}",
        3447003
    )

    try:
        draft = await generate_draft(body.event_type, body.is_sample, body.product_new, body.product_old)
    except Exception as e:
        logger.exception("마케팅 단계 실패 (%s)", tag)
        return AgentResult.failed(body.attempt_id, FailedStage.MARKETING, _describe(e))

    _notify("✨ Marketing Content Generated", draft.model_dump_json(exclude_none=True), 9109759)

    try:
        outcome = await review_draft(body.event_type, body.is_sample, body.product_new, body.product_old, draft)
    except Exception as e:
        logger.exception("심의 단계 실패 (%s)", tag)
        return AgentResult.failed(body.attempt_id, FailedStage.REVIEW, _describe(e))

    _notify(
        "🔍 Content Reviewed",
        f"- 판정: {'승인' if outcome.accepted else '거절'}\n- {tag}\n\n{outcome.review.reason}",
        16753920
    )

    if outcome.accepted:
        return AgentResult.approved(body.attempt_id, draft, outcome.content, outcome.review)
    return AgentResult.rejected(body.attempt_id, draft, outcome.review)


# Discord 전송 실패가 처리 결과에 영향을 주지 않도록 예외를 기록만 함
def _notify(title: str, description: str, color: int):
    try:
        discord_send_message(title, description, None, color)
    except Exception:
        logger.exception("Discord 전송 실패: %s", title)


def _describe(e: Exception) -> str:
    return f"{type(e).__name__}: {e}"
