#!/usr/bin/env python3
"""시연 카탈로그 생성기. k-skill sqlite 를 읽어 가상 몰 셋의 시드 JSON 을 만든다.

설계는 DESIGN.md 5.7절(2026-09-17 개정)과 ADR 0007 이 소유한다. 이 스크립트는 그 절을 구현한 것이다.

사용법
    python tools/build_demo_catalog.py \
        --sqlite ~/Downloads/korean_products_clean_bundle/korean_products_clean.sqlite \
        --out-dir src/main/resources/catalog

출력은 merchant-a.json, merchant-b.json, merchant-c.json 셋이다. 기동 시 로더가 읽는 형식이고
products 테이블의 컬럼과 variants_json 구조를 그대로 따른다.

풀은 원본 1,408건 전부이고 몰 셋이 그 풀을 나눠 갖는다. 같은 상품이 여러 몰에 다른 가격으로
있어야 가격 비교 시연이 성립하므로, 몰마다 다른 것 셋(취급 여부·가격·재고)을 결정적 해시로 뽑는다.
난수를 쓰지 않는다. 같은 입력으로 다시 돌리면 바이트 단위로 같은 파일이 나온다. 그래야
"상품이 바뀐 커밋"과 "다시 돌린 커밋"을 diff 로 구별할 수 있고, 리허설에서 본 최저가 몰이 시연
당일에도 같고, 넷째 몰을 나중에 붙여도 기존 몰의 값이 흔들리지 않는다.

생성 시각도 같은 이유로 파일에 넣지 않는다.
"""

import argparse
import hashlib
import json
import sqlite3
import uuid
from pathlib import Path

# 가상 몰 셋. 실제 상호도 우리 브랜드도 아니다(DESIGN.md 5.7절, ADR 0007).
#
# 이 모듈은 "우리 쇼핑몰"이 아니라 우리 플랫폼 바깥의 독립 사업자를 흉내 낸다(DESIGN.md 1절).
# 그래서 우리 브랜드를 붙이지 않는다. 실제 몰 이름도 쓰지 않는다 — 실제 상호를 달고 가격을
# 임의로 바꿔 놓으면 그 회사 가격을 지어낸 것이 된다. 같은 상품을 여러 몰이 파는 것은 실제
# 세상과 같다. "서로 주인이 다른 가게" 는 이름·merchant.id·서명키가 다른 것으로 지킨다.
#
# 키는 merchant.id 다. Core services.merchants[].id, Pay 토큰 JSON 의 키,
# Pay merchant_accounts.merchant_id 와 같은 문자열이어야 한다(DESIGN.md 19.5절).
MALLS = {
    "merchant-a": "한강마켓",
    "merchant-b": "노을상점",
    "merchant-c": "별빛몰",
}

# 몰마다 다른 것 셋의 기준값. 전부 u(key) 와 비교하거나 곱한다.
CARRY_RATIO = 0.78          # 이 값보다 u 가 작으면 그 몰이 그 상품을 취급한다. 몰당 약 1,100건
PRICE_MIN_FACTOR = 0.90     # 가격 배율 범위. -10% ~ +10%
PRICE_SPAN = 0.20
SOLD_OUT_RATIO = 0.05       # 이 값보다 u 가 작으면 그 몰에서 그 상품은 품절

# 원본은 재고 수량을 주지 않는다. availability 셋뿐이라 값을 정해 넣는다.
# MVP 는 재고를 차감하지 않으므로 숫자 자체에 의미가 없고 0 인지 아닌지만 본다.
STOCK_IN = 100
STOCK_OUT = 0


def u(*parts):
    """0 ≤ u < 1 인 결정적 실수. sha256 의 앞 8바이트를 부호 없는 정수로 읽어 2^64 로 나눈다.

    같은 입력이면 어디서 돌려도 같은 값이고, 입력이 한 글자만 달라도 관계없는 값이 나온다.
    접미어(carry·price·stock)를 다르게 주는 이유는 세 판단이 서로 독립이어야 하기 때문이다.
    같은 해시를 쓰면 "가격이 싼 몰은 항상 품절" 같은 상관이 생긴다.
    """
    digest = hashlib.sha256("|".join(parts).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / 2 ** 64


def deterministic_id(mall, external_id):
    """몰과 external_id 로 UUID 를 고정한다. 결정적이라 다시 돌려도 같은 행을 upsert 한다.

    393 부터 id 는 몰마다 다르다 — 한 DB 한 테이블에 같은 상품이 몰별로 한 행씩 들어가고 id 가 PK 다.
    Core 가 몰 사이에서 같은 상품으로 묶는 열쇠는 id 가 아니라 externalId(UCP 응답의 productId)다(DESIGN.md 5.4절).
    """
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"copenhagen:merchant-mock:product:{mall}:{external_id}"))


def rounding_unit(price):
    """1만원 미만은 10원, 이상은 100원 단위. 8,230원·9,240원처럼 가격표답게 보이기 위한 것이다."""
    return 10 if price < 10000 else 100


def round_to(amount, unit):
    return int(amount / unit + 0.5) * unit


def price_factor(external_id, mall):
    return PRICE_MIN_FACTOR + PRICE_SPAN * u(external_id, mall, "price")


def carrying_malls(external_id):
    """이 상품을 취급하는 몰 목록. 어느 몰에도 없으면 u 가 가장 작은 몰에 넣어 풀 전량이 어딘가엔 있게 한다."""
    scores = {mall: u(external_id, mall, "carry") for mall in MALLS}
    carrying = [mall for mall, score in scores.items() if score < CARRY_RATIO]
    if not carrying:
        carrying = [min(scores, key=scores.get)]
    return carrying


def build_variants(external_id, base_price, raw_variants, factor, unit, sold_out, max_variants):
    """원본 variant 를 products.variants_json 구조로 바꾼다.

    priceDelta 는 절대가가 아니라 base_price 와의 차액이다. 이걸 절대가로 넣으면
    체크아웃 금액이 두 배가 된다(DESIGN.md 5.7절). 차액에도 같은 factor 를 곱한다 —
    기본가만 낮추고 차액은 그대로 두면 몰마다 옵션 비율이 어긋난다.
    """
    if not raw_variants:
        # 옵션 없는 상품(다나와·다이소 전부). 단일 옵션을 합성한다. V3 의 product-104 와 같은 모양이다.
        return [{
            "sku": f"{external_id}-default",
            "optionName": "option",
            "optionValue": "기본",
            "priceDelta": 0,
            "stock": STOCK_OUT if sold_out else STOCK_IN,
            "status": "ACTIVE",
        }]

    scaled_base = round_to(base_price * factor, unit)
    variants = []
    seen = set()
    for raw in raw_variants:
        if len(variants) == max_variants:
            break
        sku = raw.get("sku")
        if not sku:
            continue
        # 같은 상품 안에서 원본 SKU 가 겹친다. "[컬리멤버스]" 멤버십 가격 옵션이 일반 옵션과
        # 같은 SKU 를 쓴다. 같은 물건의 다른 가격표이지 다른 옵션이 아니므로 첫 번째만 남긴다.
        if sku in seen:
            continue
        seen.add(sku)
        price = raw.get("sale_price")
        if price is None:
            price = base_price
        scaled_price = round_to(price * factor, unit)
        # 각각 반올림한 뒤 차액을 구해야 base + delta 가 반올림 단위에 떨어진다.
        delta = scaled_price - scaled_base
        if scaled_base + delta <= 0:
            delta = unit - scaled_base
        out_of_stock = sold_out or raw.get("availability") == "out_of_stock"
        variants.append({
            # 원본 SKU 는 서로 다른 상품 사이에서 70개가 겹친다. CatalogStore.bySku 가
            # Map 한 장이라 그대로 쓰면 나중 것이 앞의 것을 덮는다.
            "sku": f"{external_id}-{sku}",
            "optionName": "option",
            "optionValue": (raw.get("name") or "기본")[:100],
            "priceDelta": delta,
            "stock": STOCK_OUT if out_of_stock else STOCK_IN,
            "status": "ACTIVE",
        })
    return variants or build_variants(external_id, base_price, None, factor, unit, sold_out, max_variants)


def load_rows(sqlite_path):
    connection = sqlite3.connect(sqlite_path)
    query = """
        SELECT product_id, source_code, name, primary_image_url, sale_price, variants_json
          FROM clean_catalog
         ORDER BY product_id
    """
    rows = []
    for product_id, source, name, image, price, variants_json in connection.execute(query):
        if not (product_id and name and image and price):
            continue
        raw = json.loads(variants_json) if variants_json and variants_json not in ("[]", "null") else []
        rows.append({
            "source": source,
            "product_id": product_id,
            "name": name,
            "image": image,
            "price": price,
            "raw_variants": raw,
        })
    connection.close()
    return rows


def to_product(row, mall, max_variants):
    external_id = row["product_id"]
    factor = price_factor(external_id, mall)
    unit = rounding_unit(row["price"])
    sold_out = u(external_id, mall, "stock") < SOLD_OUT_RATIO
    base_price = max(round_to(row["price"] * factor, unit), unit)
    return {
        "id": deterministic_id(mall, external_id),
        "externalId": external_id,
        "title": row["name"],
        "imageUrl": row["image"],
        "basePrice": base_price,
        "currency": "KRW",
        "status": "ACTIVE",
        "variants": build_variants(
            external_id, row["price"], row["raw_variants"], factor, unit, sold_out, max_variants),
    }


def main():
    parser = argparse.ArgumentParser(description="시연 카탈로그 시드 JSON 생성")
    parser.add_argument("--sqlite", required=True, help="korean_products_clean.sqlite 경로")
    parser.add_argument("--out-dir", required=True, help="출력 디렉터리")
    parser.add_argument("--max-variants", type=int, default=10, help="상품당 옵션 상한 (기본 10)")
    args = parser.parse_args()

    rows = load_rows(args.sqlite)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    by_mall = {mall: [] for mall in MALLS}
    for row in rows:
        for mall in carrying_malls(row["product_id"]):
            by_mall[mall].append(row)

    for mall, mall_name in MALLS.items():
        products = [to_product(row, mall, args.max_variants) for row in by_mall[mall]]
        # externalId 로 정렬해 두면 다시 돌렸을 때 diff 가 내용 변경만 보여준다.
        products.sort(key=lambda p: p["externalId"])
        document = {
            "mall": mall,
            "mallName": mall_name,
            "source": "NomaDamas/k-skill · korean_products_clean.sqlite · 결정적 해시로 몰별 취급·가격·재고를 가름",
            "count": len(products),
            "products": products,
        }
        path = out_dir / f"{mall}.json"
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        skus = sum(len(product["variants"]) for product in products)
        synthesized = sum(
            1 for product in products
            if len(product["variants"]) == 1 and product["variants"][0]["sku"].endswith("-default")
        )
        sold_out = sum(
            1 for product in products
            if all(variant["stock"] == 0 for variant in product["variants"])
        )
        size_kb = round(path.stat().st_size / 1024, 1)
        print(f"{path.name:18s} 상품 {len(products):4d}  SKU {skus:5d}  단일옵션합성 {synthesized:4d}  "
              f"전옵션품절 {sold_out:3d}  {size_kb}KB")

    covered = {row["product_id"] for row in rows}
    overlap = sum(1 for row in rows if len(carrying_malls(row["product_id"])) >= 2)
    print(f"풀 {len(rows)}건, 전부 최소 한 몰에 있음: {len(covered) == len(rows)}, 두 몰 이상에 있는 상품 {overlap}건")


if __name__ == "__main__":
    main()
