package com.capstone.triplea.advertisement.value;

import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 이벤트 시점의 상품 정보 (AD_EVENT.product_snapshot에 JSON으로 저장)
// 재시도·복구 시 agent 요청에 그대로 사용
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(
        ProductInfo productNew,
        ProductInfo productOld  // DISCOUNT일 때 변경 전 상품 정보 (NEW면 null)
) {

    // 신상품 등록
    public static ProductSnapshot ofNew(ProductInfo productNew) {
        return new ProductSnapshot(productNew, null);
    }

    // 가격 인하
    public static ProductSnapshot ofDiscount(ProductInfo productNew, ProductInfo productOld) {
        return new ProductSnapshot(productNew, productOld);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductInfo(
            String name,
            String description,
            int listPrice,
            int price,
            int quantity,
            String category,
            String imageUrl
    ) {
        public static ProductInfo from(Product product) {
            return new ProductInfo(
                    product.getName(),
                    product.getDescription(),
                    product.getListPrice(),
                    product.getPrice(),
                    product.getQuantity(),
                    product.getCategory(),
                    product.getImageUrl()
            );
        }
    }
}
