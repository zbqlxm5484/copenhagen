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

# 몰마다 다른 것의 기준값. 전부 u(key) 와 비교하거나 곱한다.
#
# 428 에서 취급 여부를 빼고 가격·재고만 남겼다. 그전에는 몰마다 취급 상품을 갈라 세 몰 공통이
# 1,408건 중 689건뿐이었고, 그래서 같은 검색어에 몰마다 다른 결과가 나왔다. 가격 비교를 보이려면
# 세 몰이 같은 상품을 갖고 가격만 달라야 한다.
CARRY_RATIO = 1.0           # 이 값보다 u 가 작으면 그 몰이 그 상품을 취급한다. 1.0 이면 세 몰이 전량을 취급한다(428)
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
    """이 상품을 취급하는 몰 목록. CARRY_RATIO 가 1.0 이면 전부다(428).

    상수만 낮추면 몰마다 취급을 다시 가를 수 있게 해시 비교는 남겨 둔다.
    """
    scores = {mall: u(external_id, mall, "carry") for mall in MALLS}
    carrying = [mall for mall, score in scores.items() if score < CARRY_RATIO]
    if not carrying:
        carrying = [min(scores, key=scores.get)]
    return carrying


# 옵션을 합성할 카테고리(428). category_path 에 이 낱말이 있으면 그 축을 만든다.
#
# 원본 sqlite 의 옵션 재료는 셋인데 쓸 수 있는 것은 colors_json 하나다. sizes_json 은 신발에도 "M" 이
# 들어 있어 신발 사이즈로 못 쓰고, capacities_json 은 저장용량·RAM·무게가 한 배열에 섞여 있어
# ("512GB", "12GB", "204g") 그대로 옵션으로 만들면 무게를 고르는 상품이 된다. 그래서 색상은 원본을 쓰고
# 사이즈는 카테고리를 보고 합성한다. 용량 축은 만들지 않는다 — 제목에 이미 들어 있다.
SHOE_CATEGORIES = ("스포츠화", "운동화", "러닝화", "신발", "슈즈", "부츠", "샌들")
APPAREL_CATEGORIES = ("의류", "스포츠웨어", "양말", "장갑", "이너웨어", "티셔츠")
SHOE_SIZES = ("250", "260", "270", "280")
APPAREL_SIZES = ("S", "M", "L")

# 색이 실제로 갈리는 카테고리만 색상을 합성한다. 세탁기·정수기 색을 지어내면 시연에서 더 어색하다.
DIGITAL_CATEGORIES = ("휴대폰", "태블릿", "노트북", "웨어러블", "이어폰", "헤드셋", "헤드폰")
GOODS_CATEGORIES = ("여행가방", "가방", "의자", "파우치", "지갑")
DIGITAL_COLORS = ("블랙", "화이트", "실버")
GOODS_COLORS = ("블랙", "베이지", "네이비")


def has(category, keywords):
    return any(keyword in category for keyword in keywords)


def color_axis(category, source_colors):
    """색상 값들. 원본을 먼저 쓰고, 색이 갈리는 카테고리인데 값이 하나뿐이면 팔레트로 채운다.

    원본 색상이 하나뿐인 상품이 많다. 그대로 두면 "색상: 블루" 하나짜리 옵션이 되어 고를 것이 없다.
    팔레트 색을 덧붙이는 것은 몰별 가격을 ±10% 로 흔드는 것과 같은 종류의 각색이다(ADR 0007).
    색이 갈리지 않는 카테고리(세탁기·정수기)는 원본이 있든 없든 채우지 않는다.
    """
    # 원본에 ["BLACK", "Black"] 처럼 대소문자만 다른 같은 색이 들어 있다. 그대로 두면 같은 옵션이
    # 두 줄이 되고 SKU 도 겹친다(danawa:17790299 에서 잡혔다). 먼저 온 표기를 남긴다.
    colors = []
    seen = set()
    for color in source_colors:
        value = str(color)[:40]
        if value.casefold() in seen:
            continue
        seen.add(value.casefold())
        colors.append(value)
    if has(category, DIGITAL_CATEGORIES) or has(category, SHOE_CATEGORIES):
        palette = DIGITAL_COLORS
    elif has(category, GOODS_CATEGORIES):
        palette = GOODS_COLORS
    else:
        return colors
    for color in palette:
        if len(colors) >= len(palette):
            break
        if color not in colors:
            colors.append(color)
    return colors


def size_axis(category):
    if has(category, SHOE_CATEGORIES):
        return list(SHOE_SIZES)
    if has(category, APPAREL_CATEGORIES):
        return list(APPAREL_SIZES)
    return []


def slug(axis_initial, index, value):
    """SKU 조각. 영숫자면 그대로 쓰고 한글이면 축 머리글자 + 순번이다."""
    return value.lower() if value.isascii() and value.isalnum() else f"{axis_initial}{index + 1}"


def synthesize_variants(external_id, category, source_colors, sold_out, max_variants):
    """원본에 옵션이 없는 상품의 옵션을 만든다(428). 만들 재료가 없으면 빈 목록이다.

    축은 색상과 사이즈 둘뿐이고 조합은 색상 × 사이즈다. 조합 수가 상한을 넘으면 사이즈를 다 살리고
    색상을 줄인다 — 사이즈가 빠진 신발보다 색이 두 가지인 신발이 낫다.

    priceDelta 는 전부 0 이다. 몰별 가격 차이는 이미 지어내고 있고 옵션 추가금까지 지어내지 않는다.
    """
    colors = color_axis(category, source_colors)
    sizes = size_axis(category)
    if not colors and not sizes:
        return []
    if colors and sizes and len(colors) * len(sizes) > max_variants:
        colors = colors[:max(max_variants // len(sizes), 1)]

    if colors and sizes:
        option_name = "색상/사이즈"
        combos = [([color, size], [slug("c", ci, color), slug("s", si, size)])
                  for ci, color in enumerate(colors) for si, size in enumerate(sizes)]
    elif colors:
        option_name = "색상"
        combos = [([color], [slug("c", ci, color)]) for ci, color in enumerate(colors)]
    else:
        option_name = "사이즈"
        combos = [([size], [slug("s", si, size)]) for si, size in enumerate(sizes)]

    variants = []
    skus = set()
    for values, parts in combos[:max_variants]:
        sku = external_id + "-" + "-".join(parts)
        if sku in skus:
            # 값이 달라도 슬러그가 겹칠 수 있다. 순번을 붙여 파일 안에서 유일하게 만든다.
            sku = f"{sku}-{len(skus) + 1}"
        skus.add(sku)
        variants.append({
            "sku": sku,
            "optionName": option_name,
            "optionValue": " / ".join(values),
            "priceDelta": 0,
            "stock": STOCK_OUT if sold_out else STOCK_IN,
            "status": "ACTIVE",
        })
    return variants


def build_variants(external_id, base_price, raw_variants, factor, unit, sold_out, max_variants,
                   category="", source_colors=()):
    """원본 variant 를 products.variants_json 구조로 바꾼다.

    priceDelta 는 절대가가 아니라 base_price 와의 차액이다. 이걸 절대가로 넣으면
    체크아웃 금액이 두 배가 된다(DESIGN.md 5.7절). 차액에도 같은 factor 를 곱한다 —
    기본가만 낮추고 차액은 그대로 두면 몰마다 옵션 비율이 어긋난다.
    """
    if not raw_variants:
        # 원본에 옵션이 없는 상품(다나와·다이소 전부). 카테고리를 보고 색상·사이즈를 합성한다(428).
        synthesized = synthesize_variants(external_id, category, source_colors, sold_out, max_variants)
        if synthesized:
            return synthesized
        # 재료가 없으면 단일 옵션이다. V3 의 product-104 와 같은 모양이다.
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
    return variants or build_variants(external_id, base_price, None, factor, unit, sold_out, max_variants,
                                      category, source_colors)


def load_rows(sqlite_path):
    connection = sqlite3.connect(sqlite_path)
    query = """
        SELECT product_id, source_code, name, primary_image_url, sale_price, variants_json,
               category_path, colors_json, brand
          FROM clean_catalog
         ORDER BY product_id
    """
    rows = []
    for (product_id, source, name, image, price, variants_json,
            category_path, colors_json, brand) in connection.execute(query):
        if not (product_id and name and image and price):
            continue
        raw = json.loads(variants_json) if variants_json and variants_json not in ("[]", "null") else []
        colors = json.loads(colors_json) if colors_json and colors_json not in ("[]", "null") else []
        rows.append({
            "source": source,
            "product_id": product_id,
            "name": name,
            "image": image,
            "price": price,
            "raw_variants": raw,
            "category": category_path or "",
            "brand": brand or "",
            "colors": [color for color in colors if str(color).strip()],
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
        # 검색 매칭 전용이다(470). UCP 응답에는 싣지 않는다 — 내보내면 Core 와 필드 합의가 필요하다.
        "category": row["category"],
        "brand": row["brand"],
        "imageUrl": row["image"],
        "basePrice": base_price,
        "currency": "KRW",
        "status": "ACTIVE",
        "variants": build_variants(
            external_id, row["price"], row["raw_variants"], factor, unit, sold_out, max_variants,
            row["category"], row["colors"]),
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
            "source": "NomaDamas/k-skill · korean_products_clean.sqlite · 세 몰이 전량을 취급하고 "
                      "결정적 해시로 몰별 가격·재고를 가름",
            "count": len(products),
            "products": products,
        }
        path = out_dir / f"{mall}.json"
        # newline="" 로 줄바꿈 변환을 끈다. 윈도우에서 돌리면 CRLF 로 나가 git 이 파일 전체를 바뀐 것으로
        # 보고, 커밋할 때 다시 LF 로 눕혀 작업본이 영원히 수정됨 상태로 남는다.
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8", newline="")

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
