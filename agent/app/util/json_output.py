import json
import re

_CODE_FENCE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


# LLM 응답 문자열을 JSON 객체로 파싱 (마크다운 코드 블록으로 감싼 경우도 허용)
def parse_json_object(text: str | None) -> dict:
    if not text:
        raise ValueError("빈 응답")

    stripped = text.strip()
    fenced = _CODE_FENCE.match(stripped)
    if fenced:
        stripped = fenced.group(1)

    parsed = json.loads(stripped)
    if not isinstance(parsed, dict):
        raise ValueError(f"JSON 객체가 아님: {type(parsed).__name__}")
    return parsed
