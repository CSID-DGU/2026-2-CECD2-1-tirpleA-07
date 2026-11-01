from enum import Enum

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from app.common.dto.product import Product
from app.common.enum.event_type import EventType

ERROR_MESSAGE_MAX_LENGTH = 1000


class CamelModel(BaseModel):
    # api와 주고받는 JSON은 camelCase
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class AgentStatus(str, Enum):
    APPROVED = "APPROVED"   # 심의 승인
    REJECTED = "REJECTED"   # 심의 거절
    FAILED = "FAILED"       # 생성·심의 단계 실패


class FailedStage(str, Enum):
    MARKETING = "MARKETING"
    REVIEW = "REVIEW"


# 광고 콘텐츠 (초안, 승인 콘텐츠 공통)
class AdContent(CamelModel):
    title: str
    body: str
    cta: str
    hashtags: list[str] = []
    image_url: str | None = None


# 투표 1개의 판정과 사유
class Vote(CamelModel):
    is_accepted: bool
    reason: str


# 심의가 찾아낸 위반 사항
class Issue(CamelModel):
    field: str | None = None    # title, body, cta, hashtags, image
    phrase: str | None = None   # draft 원문의 부분 문자열
    reason: str


# 심의 결과
class Review(CamelModel):
    reason: str                     # 채택된 투표의 사유
    selected_vote: int | None = None
    votes: list[Vote] = []
    issues: list[Issue] = []


# /generate 요청
class GenerateRequest(CamelModel):
    attempt_id: int
    event_id: int
    event_type: EventType
    product_new: Product
    product_old: Product | None = None
    is_sample: bool = False


# /generate 응답 (처리 결과와 무관하게 항상 200으로 반환)
class AgentResult(CamelModel):
    attempt_id: int
    status: AgentStatus
    failed_stage: FailedStage | None = None
    error_message: str | None = None
    draft: AdContent | None = None
    content: AdContent | None = None
    review: Review | None = None

    @classmethod
    def approved(cls, attempt_id: int, draft: AdContent, content: AdContent, review: Review) -> "AgentResult":
        return cls(attempt_id=attempt_id, status=AgentStatus.APPROVED, draft=draft, content=content, review=review)

    @classmethod
    def rejected(cls, attempt_id: int, draft: AdContent, review: Review) -> "AgentResult":
        return cls(attempt_id=attempt_id, status=AgentStatus.REJECTED, draft=draft, review=review)

    # 실패하면 실패 단계와 메시지만 반환 (초안도 싣지 않음)
    @classmethod
    def failed(cls, attempt_id: int, failed_stage: FailedStage, error_message: str) -> "AgentResult":
        return cls(
            attempt_id=attempt_id,
            status=AgentStatus.FAILED,
            failed_stage=failed_stage,
            error_message=error_message[:ERROR_MESSAGE_MAX_LENGTH],
        )
