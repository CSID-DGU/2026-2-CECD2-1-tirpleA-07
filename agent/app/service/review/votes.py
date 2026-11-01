from dataclasses import dataclass

from app.common.dto.agent_result import AdContent, Review, Vote


@dataclass(frozen=True)
class ReviewOutcome:
    accepted: bool
    content: AdContent | None   # 채택된 투표의 수정본 (거절이면 None)
    review: Review


# 채택할 투표: 가장 먼저 나온 거절, 거절이 없으면 첫 번째 투표 (1개라도 거절이면 거절)
def select_vote(votes: list[Vote]) -> int:
    for idx, vote in enumerate(votes):
        if not vote.is_accepted:
            return idx
    return 0


# 파싱된 심의 응답 목록으로 결과를 만든다
# votes에는 판정과 사유만 담고, 채택되지 않은 투표의 수정본은 버린다
def build_outcome(parsed: list[dict]) -> ReviewOutcome:
    if not parsed:
        raise ValueError("심의 응답이 없음")

    votes = [_to_vote(item) for item in parsed]
    selected = select_vote(votes)
    accepted = votes[selected].is_accepted
    content = AdContent.model_validate(parsed[selected]) if accepted else None

    review = Review(
        reason=votes[selected].reason,
        selected_vote=selected,
        votes=votes,
        issues=[],  # 심의 프롬프트가 위반 사항을 출력하도록 바뀌면 채움 (Track D-1)
    )
    return ReviewOutcome(accepted=accepted, content=content, review=review)


def _to_vote(item: dict) -> Vote:
    is_accepted = item.get("is_accepted")
    if not isinstance(is_accepted, bool):
        raise ValueError(f"is_accepted가 bool이 아님: {is_accepted!r}")
    return Vote(is_accepted=is_accepted, reason=str(item.get("reason") or ""))
