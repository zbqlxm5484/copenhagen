#!/usr/bin/env python3
"""시연 카탈로그 생성기. k-skill sqlite 를 읽어 몰별 시드 JSON 을 만든다.

설계는 DESIGN.md 5.7절이 소유한다. 이 스크립트는 그 절을 구현한 것이다.

사용법
    python tools/build_demo_catalog.py \
        --sqlite ~/Downloads/korean_products_clean_bundle/korean_products_clean.sqlite \
        --out-dir src/main/resources/catalog

출력은 kurly.json, danawa.json, daiso.json 셋이다. 기동 시 로더가 읽는 형식이고
products 테이블의 컬럼과 variants_json 구조를 그대로 따른다.

생성 시각을 파일에 넣지 않는다. 넣으면 같은 입력으로 다시 돌려도 diff 가 생겨
"상품이 바뀐 커밋"과 "다시 돌린 커밋"을 구별할 수 없다.
"""

import argparse
import json
import sqlite3
import uuid
from collections import defaultdict
from pathlib import Path

# 몰 셋. 원본 데이터의 출처가 곧 몰이다.
#
# 이 모듈은 "우리 쇼핑몰"이 아니라 우리 플랫폼 바깥의 독립 사업자를 흉내 낸다(DESIGN.md 1절).
# Core 의 merchants 는 UCP Business Profile 주소로 등록하는 외부 사업자 등록부이고
# cart_items 에 merchant_id 가 붙어 장바구니 하나가 여러 사업자를 가로지른다.
# 그래서 몰 이름에 우리 브랜드를 붙이지 않고 상품이 실제로 나온 곳의 상호를 쓴다.
#
# 이 셋은 UCP 를 지원하지 않고 우리와 제휴하지도 않았다. 이 프로젝트 전체가 목이고
# 그 전제를 팀과 시연이 이미 깔고 가므로 상호에 따로 표시를 붙이지 않는다(2026-09-16 사용자 결정).
MALLS = {
    "kurly": "컬리",
    "danawa": "다나와",
    "daiso": "다이소",
}

# 원본은 재고 수량을 주지 않는다. availability 셋뿐이라 값을 정해 넣는다.
# MVP 는 재고를 차감하지 않으므로 숫자 자체에 의미가 없고 0 인지 아닌지만 본다.
STOCK_IN = 100
STOCK_OUT = 0


def level1(category_path):
    return (category_path or "").split(">")[0].strip()


def deterministic_id(external_id):
    """external_id 로 UUID 를 고정한다. 다시 돌려도 같은 id 라 upsert 가 중복을 안 만든다."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"copenhagen:merchant-mock:product:{external_id}"))


def build_variants(external_id, base_price, raw_variants, max_variants):
    """원본 variant 를 products.variants_json 구조로 바꾼다.

    priceDelta 는 절대가가 아니라 base_price 와의 차액이다. 이걸 절대가로 넣으면
    체크아웃 금액이 두 배가 된다(DESIGN.md 5.7절).
    """
    if not raw_variants:
        # 옵션 없는 상품(다나와·다이소 전부). 단일 옵션을 합성한다. V3 의 product-104 와 같은 모양이다.
        return [{
            "sku": f"{external_id}-default",
            "optionName": "option",
            "optionValue": "기본",
            "priceDelta": 0,
            "stock": STOCK_IN,
            "status": "ACTIVE",
        }]

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
        variants.append({
            # 원본 SKU 는 서로 다른 상품 사이에서 70개가 겹친다. CatalogStore.bySku 가
            # Map 한 장이라 그대로 쓰면 나중 것이 앞의 것을 덮는다.
            "sku": f"{external_id}-{sku}",
            "optionName": "option",
            "optionValue": (raw.get("name") or "기본")[:100],
            "priceDelta": price - base_price,
            "stock": STOCK_OUT if raw.get("availability") == "out_of_stock" else STOCK_IN,
            "status": "ACTIVE",
        })
    return variants or build_variants(external_id, base_price, None, max_variants)


def load_rows(sqlite_path):
    connection = sqlite3.connect(sqlite_path)
    query = """
        SELECT product_id, source_code, name, category_path, primary_image_url, sale_price, variants_json
          FROM clean_catalog
         ORDER BY product_id
    """
    rows = []
    for product_id, source, name, category, image, price, variants_json in connection.execute(query):
        if not (product_id and name and image and price):
            continue
        if source not in MALLS:
            raise KeyError(f"몰로 등록되지 않은 출처: {source!r}. MALLS 에 추가할 것")
        category = level1(category)
        raw = json.loads(variants_json) if variants_json and variants_json not in ("[]", "null") else []
        rows.append({
            "mall": source,
            "category": category,
            "source": source,
            "product_id": product_id,
            "name": name,
            "image": image,
            "price": price,
            "raw_variants": raw,
        })
    connection.close()
    return rows


def pick(rows, per_mall):
    """몰마다 카테고리를 고르게 덮어 per_mall 건을 고른다.

    카테고리를 라운드로빈으로 돌면서 한 건씩 집는다. 한 카테고리가 목록을 독점하지 않는다.
    몰이 곧 출처라 한 몰 안에는 한 쇼핑몰의 상품만 들어간다.
    """
    by_mall = defaultdict(lambda: defaultdict(list))
    for row in rows:
        by_mall[row["mall"]][row["category"]].append(row)

    selected = {}
    for mall, categories in by_mall.items():
        for candidates in categories.values():
            # 옵션이 있는 것을 먼저 집는다. 합성 옵션보다 원본 SKU 가 시연에 낫다.
            # 그다음은 product_id 로 고정한다. 무작위를 쓰지 않아 다시 돌려도 결과가 같다.
            candidates.sort(key=lambda r: (0 if r["raw_variants"] else 1, r["product_id"]))

        chosen = []
        order = sorted(categories, key=lambda c: (-len(categories[c]), c))
        cursor = 0
        while len(chosen) < per_mall:
            progressed = False
            for category in order:
                bucket = categories[category]
                if cursor < len(bucket):
                    chosen.append(bucket[cursor])
                    progressed = True
                    if len(chosen) == per_mall:
                        break
            if not progressed:
                break  # 재고 소진. 이 몰에는 per_mall 만큼이 없다
            cursor += 1
        selected[mall] = chosen
    return selected


def to_product(row, max_variants):
    return {
        "id": deterministic_id(row["product_id"]),
        "externalId": row["product_id"],
        "title": row["name"],
        "imageUrl": row["image"],
        "basePrice": row["price"],
        "currency": "KRW",
        "status": "ACTIVE",
        "variants": build_variants(row["product_id"], row["price"], row["raw_variants"], max_variants),
    }


def main():
    parser = argparse.ArgumentParser(description="시연 카탈로그 시드 JSON 생성")
    parser.add_argument("--sqlite", required=True, help="korean_products_clean.sqlite 경로")
    parser.add_argument("--out-dir", required=True, help="출력 디렉터리")
    parser.add_argument("--per-mall", type=int, default=100, help="몰당 상품 수 (기본 100)")
    parser.add_argument("--max-variants", type=int, default=10, help="상품당 옵션 상한 (기본 10)")
    args = parser.parse_args()

    rows = load_rows(args.sqlite)
    selected = pick(rows, args.per_mall)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for mall, mall_name in MALLS.items():
        products = [to_product(row, args.max_variants) for row in selected.get(mall, [])]
        # externalId 로 정렬해 두면 다시 돌렸을 때 diff 가 내용 변경만 보여준다.
        products.sort(key=lambda p: p["externalId"])
        document = {
            "mall": mall,
            "mallName": mall_name,
            "source": "NomaDamas/k-skill · korean_products_clean.sqlite",
            "count": len(products),
            "products": products,
        }
        path = out_dir / f"{mall}.json"
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        categories = len({row["category"] for row in selected.get(mall, [])})
        skus = sum(len(product["variants"]) for product in products)
        synthesized = sum(
            1 for product in products
            if len(product["variants"]) == 1 and product["variants"][0]["sku"].endswith("-default")
        )
        size_kb = round(path.stat().st_size / 1024, 1)
        print(f"{path.name:12s} 상품 {len(products):3d}  카테고리 {categories:2d}  "
              f"SKU {skus:4d}  단일옵션합성 {synthesized:3d}  {size_kb}KB")


if __name__ == "__main__":
    main()
