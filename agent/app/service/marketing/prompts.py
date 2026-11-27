import json

from app.common.enum.event_type import EventType
from app.common.dto.product import Product
from app.util.db_pool import get_connection, search_vectordb
from app.util.embedding_model import embed_document

# -------------------------
# CATEGORY TONE GUIDE
# -------------------------
CATEGORY_TONE_GUIDE = {
    "일본": {
        "target":          "20~30대 / 직장인 / 첫 해외여행러",
        "tone":            "친근하고 설레는 / 가성비 강조 / 음식·감성·일상 탈출 소재",
        "hook":            "가깝고, 맛있고, 또 가고 싶은 나라",
        "location_prefix": "일본",
    },
    "동남아": {
        "target":          "20대 / 가성비 여행자 / 커플·친구 그룹",
        "tone":            "활기차고 자유로운 / 리조트·해변·야시장 소재 / 휴양 강조",
        "hook":            "지금 당장 떠나고 싶은 휴양지",
        "location_prefix": "동남아",
    },
    "유럽": {
        "target":          "30~40대 / 버킷리스트 여행자 / 신혼",
        "tone":            "프리미엄·낭만 / 평생 한 번의 경험 강조 / 예술·건축·미식 소재",
        "hook":            "언젠가 꼭 가야 할 그 도시",
        "location_prefix": "유럽",
    },
    "대만·홍콩·마카오": {
        "target":          "20~30대 / 미식·감성 여행자 / 도시 탐방객",
        "tone":            "친근하고 세련된 / 야시장·미식·야경·골목 감성 소재 / 동서양 문화 교차 강조",
        "hook":            "야시장 냄새와 화려한 야경, 한번 가면 또 가고 싶은 곳",
        "location_prefix": "대만·홍콩·마카오",
    },
    "중국": {
        "target":          "30~50대 / 역사·문화 여행자 / 가족 여행객",
        "tone":            "웅장하고 신비로운 / 역사·유적·음식 소재 / 다채로운 문화 강조",
        "hook":            "5,000년 역사가 살아 숨쉬는 나라",
        "location_prefix": "중국",
    },
    "몽골·중앙아시아": {
        "target":          "20~40대 / 오지·탐험 여행자 / 자연 애호가",
        "tone":            "광활하고 거친 / 초원·사막·유목문화 소재 / 비일상적 해방감 강조",
        "hook":            "하늘과 땅만 있는 곳, 거기서 나를 찾다",
        "location_prefix": "몽골·중앙아시아",
    },
    "미국": {
        "target":          "30~40대 / 장거리 여행 마니아 / 어학연수·워킹홀리데이 경험자",
        "tone":            "스케일 크고 자유로운 / 로드트립·자연·도시 문화 소재 / 도전적 감성",
        "hook":            "한 번쯤은 미국 횡단, 지금이 그 때",
        "location_prefix": "미국",
    },
    "호주·뉴질랜드": {
        "target":          "20~30대 / 워킹홀리데이·장기 여행자 / 자연 애호가",
        "tone":            "광활하고 청량한 / 자연·해변·액티비티 소재 / 힐링·해방감 강조",
        "hook":            "지구 반대편, 가장 맑은 공기가 있는 곳",
        "location_prefix": "호주·뉴질랜드",
    },
    "캐나다·중남미": {
        "target":          "20~40대 / 자연·모험 여행자 / 배낭여행·장기 여행객",
        "tone":            "다채롭고 역동적인 / 자연경관·라틴 문화·음식 소재 / 새로운 세계 발견 강조",
        "hook":            "오로라부터 삼바까지, 지구에서 가장 다양한 여행",
        "location_prefix": "캐나다·중남미",
    },
    "중동·아프리카·인도": {
        "target":          "20~40대 / 오지·문화 탐방객 / 인생 여행을 꿈꾸는 여행자",
        "tone":            "신비롭고 강렬한 / 사막·유적·향신료·색채 소재 / 압도적 비일상 경험 강조",
        "hook":            "한 번 가면 평생 잊을 수 없는 강렬함",
        "location_prefix": "중동·아프리카·인도",
    },
}

# 상품 이미지가 함께 주어질 때만 user 프롬프트 뒤에 덧붙임
IMAGE_INSTRUCTION = """

## 상품 이미지 활용
- 함께 첨부된 이미지는 이 상품의 대표 사진입니다.
- 사진에 보이는 풍경·분위기·색감·시간대를 body의 여행지 감각 문장에 1~2개 자연스럽게 반영하세요.
- 사진에서 확인할 수 없는 시설·서비스는 지어내지 마세요.
"""

DEFAULT_TONE = {
    "target":   "여행을 즐기는 모든 연령대",
    "tone":     "설레고 따뜻한 / 여행의 설렘 강조",
    "hook":     "새로운 곳으로 떠나는 특별한 여행",
    "location_prefix": "",
}

# -------------------------
# SYSTEM PROMPT
# -------------------------
SYSTEM_PROMPT = """
당신은 한국 여행 플랫폼을 위한 최고 수준의 SNS 카피라이터입니다.
당신의 역할은 사람들이 스크롤을 멈추고 바로 예약하고 싶어지는 페이스북 게시글을 쓰는 것입니다.

## 절대 규칙
- 금지 표현: "특별한 여행", "잊지 못할 추억", "설레는 마음으로", "아름다운 풍경", "행복한 시간"
- 허용 문자: 한글과 영어만 사용 가능. 한자, 일본어 가나, 베트남어, 아랍어, 태국어 등 사용 금지
- body의 줄바꿈은 반드시 \\n으로 표기할 것 — raw 줄바꿈 절대 금지
- 장소명과 음식명도 반드시 한글 표기로 작성 (예시: 타코야키, 하카타, 에펠탑)
- description 필드를 그대로 복사하지 말 것
- 지정된 JSON 스키마 외의 필드는 절대 출력하지 말 것
- 숫자 값(가격, 할인율, 좌석 수 등)은 입력 데이터와 반드시 정확히 일치해야 함 — 임의 수정, 반올림 금지
- 카테고리별 톤 가이드를 반드시 따를 것
- 해시태그는 반드시 #으로 시작해야 함 (예시: #오사카여행 / 금지: 오사카여행)

## 게시글 구조
- title: 15~25자, 강렬한 훅, 이모지 1~2개 
- body: 5~7문장, 감정적 언어, 가격 넛지, 이모지 4~5개 
- cta: 행동 동사 + 혜택, 이모지 1개, 40자 이내 
- hashtags: 6~10개, 반드시 # 포함, 목적지 + 테마 + 여행문화 태그 혼합

## 출력 형식
반드시 유효한 JSON 객체만 응답하세요.
마크다운 사용 금지, 설명 금지, JSON 외의 추가 텍스트 출력 금지.
스키마:
{
  "title": "string",
  "body": "string",
  "cta": "string",
  "hashtags": ["string", ...]
}
"""

# -------------------------
# URGENCY SUFFIX BUILDER  ← 후처리로 body에 붙이는 방식
# -------------------------
def urgency_instruction(quantity: int) -> str:
    if quantity < 10:
        return f"- 잔여 {quantity}석임을 body에 반드시 언급할 것 (예: '🚨 단 {quantity}자리 남았어!')"
    elif quantity < 30:
        return f"- 잔여 {quantity}석임을 body에 반드시 언급할 것 (예: '📢 {quantity}석 마감 임박!')"
    return ""

# -------------------------
# USER PROMPT BUILDER
# -------------------------
def build_user_prompt(
        event_type: EventType,
        product_new: Product,
        product_old: Product | None,
) -> str:
    try:
        conn = get_connection()
        cursor = conn.cursor()

        text, vec = embed_document(product_new)
        # 벡터 검색을 통해 데이터 조회

        result_list = search_vectordb(cursor, event_type, vec)
        # few_shot 예시 정보를 가져와 생성
        few_shot = ""

        for elem in result_list[:3]:
            input_json = elem[1]
            correct_json = elem[2]
            wrong_json = elem[3]

            few_shot += f"""
        ### 참고 예시

        입력 상품:
        {json.dumps(input_json, ensure_ascii=False, default=str)}

        좋은 광고:
        {json.dumps(correct_json, ensure_ascii=False, default=str)}

        나쁜 광고:
        {json.dumps(wrong_json, ensure_ascii=False, default=str)}
        위 광고가 잘못된 이유를 참고하세요.

        """
    finally:
        conn.close()

    # 1) 카테고리별 톤 가이드 주입
    tone = CATEGORY_TONE_GUIDE.get(product_new.category, DEFAULT_TONE)

    # 2) 가격 정보 — 원본 숫자 그대로 전달 (구어체 변환 제거, 검수는 Agent에서)
    list_price    = product_new.list_price
    current_price = product_new.price
    discount_amt  = list_price - current_price
    discount_rate = round(discount_amt / list_price * 100)

    price_context = (
        f"정가 {list_price:,}원 / 판매가 {current_price:,}원 "
        f"(할인 {discount_rate}% / {discount_amt:,}원 절약)"
    )

    if event_type == EventType.DISCOUNT and product_old:
        prev_price = product_old.price
        price_drop = prev_price - current_price
        drop_rate  = round(price_drop / prev_price * 100)
        price_context += (
            f"\n이번 추가 인하: {prev_price:,}원 → {current_price:,}원 "
            f"({drop_rate}%p 추가 인하 / {price_drop:,}원 추가 절약)"
        )

    # 3) 이벤트별 핵심 지시 (urgency 지시 제거 — 후처리로 처리)
    if event_type == EventType.NEW:
        event_rule = f"""
## 이번 요청: 신규 상품 출시 포스트
- body는 반드시 "@@야야" 또는 "@@[친구 태그 유도]" 로 시작할 것
- 이모지를 자연스럽게 섞어서 SNS 친구 말투로 쓸 것
- 여행지 감각(장소, 음식, 분위기)을 중간에 1~2문장 녹일 것
- 신규 출시 특가임을 자연스럽게 한 줄 언급할 것
- 좌석 정보: {urgency_instruction(product_new.quantity)}
"""
    elif event_type == EventType.DISCOUNT:
        event_rule = f"""
## 이번 요청: 가격 인하 할인 포스트
- body는 반드시 "@@야야" 또는 "@@[친구 태그 유도]" 로 시작할 것
- 이모지를 자연스럽게 섞어서 SNS 친구 말투로 쓸 것
- body 두 번째 줄에서 가격 인하 사실을 반드시 언급할 것
  - 형식 예시: "{prev_price:,}원짜리가 {current_price:,}원으로 할인됐대, {price_drop:,}원 절약 가능!" (DISCOUNT + product_old 있을 때)
  - 형식 예시: "{list_price:,}원짜리가 {current_price:,}원으로 출시됐대, {discount_amt:,}원 절약 가능!" (product_old 없을 때)
- 여행지 감각(장소, 음식, 분위기)을 중간에 1~2문장 녹일 것
- 좌석 정보: {urgency_instruction(product_new.quantity)}
- 숫자는 반드시 아래 계산된 값만 사용할 것:
  - 가격 정보: {price_context}
"""
    else:
        raise ValueError(f"Unsupported event_type: {event_type}")

    # 4) 최종 프롬프트 조립
    return f"""
{few_shot}

## 카테고리 톤 가이드
- 카테고리: {product_new.category}
- 주요 타깃: {tone['target']}
- 어조·소재: {tone['tone']}
- 감성 훅 레퍼런스: "{tone['hook']}"

## 상품 정보
- 상품명: {product_new.name}
- 여행지 소개 (재해석 소재로만 활용): {product_new.description}
- 가격 정보: {price_context}
- 카테고리: {product_new.category}

{event_rule}

위 정보를 바탕으로 Facebook 포스트용 JSON을 생성하세요.
"""