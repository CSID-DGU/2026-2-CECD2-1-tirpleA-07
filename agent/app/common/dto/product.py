from pydantic import BaseModel, Field, field_validator

class Product(BaseModel):
    name: str
    # 선택값: api에서 null로 올 수 있음 (프롬프트에 "None"이 찍히지 않도록 빈 문자열로 변환)
    description: str = ""
    list_price: int = Field(alias="listPrice")
    price: int
    quantity: int
    category: str = ""
    image_url: str | None = Field(alias="imageUrl", default=None)

    @field_validator("description", "category", mode="before")
    @classmethod
    def none_to_empty(cls, value):
        return "" if value is None else value
