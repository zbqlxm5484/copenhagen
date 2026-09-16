"""Read the supplied SQLite bundle without changing it; write ignored local QA data.

No UCP/network access. Synthetic shop prices are deterministic and are not live offers.
"""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile
from urllib.parse import urlsplit


def price_noise(price, product_id, shop):
    if price is None:
        return None
    basis = 9300 + int.from_bytes(hashlib.sha256(f"{product_id}:{shop}".encode()).digest()[:2], "big") % 1401
    return max(0, int(price) * basis // 10000 // 100 * 100)


def text(value, limit):
    return str(value or "").strip()[:limit]


def image(value):
    value = text(value, 2048)
    parsed = urlsplit(value)
    return value if parsed.scheme == "https" and parsed.hostname and not parsed.username else ""


def availability(value):
    return {"in_stock": "IN_STOCK", "out_of_stock": "OUT_OF_STOCK",
            "discontinued": "DISCONTINUED"}.get(value, "UNKNOWN")


def convert(database):
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute("SELECT * FROM products ORDER BY product_id").fetchall()
        products = []
        for row in rows:
            product_id = row["product_id"]
            variants = connection.execute(
                "SELECT * FROM product_variants WHERE product_id = ? ORDER BY variant_id LIMIT 50",
                (product_id,)).fetchall()
            specs = connection.execute(
                "SELECT name, value FROM product_specs WHERE product_id = ? LIMIT 30", (product_id,)).fetchall()
            products.append({
                "productId": product_id, "name": text(row["name"], 240),
                "brand": text(row["brand"], 120),
                "category": text((row["category_path"] or "기타").split(" > ")[0], 160),
                "imageUrl": image(row["primary_image_url"]),
                "offers": [{"merchantId": shop, "merchantName": name,
                            "price": price_noise(row["sale_price"], product_id, shop), "currency": "KRW",
                            "availability": availability(row["availability"])}
                           for shop, name in [("qa-a", "가상몰 A"), ("qa-b", "가상몰 B"), ("qa-c", "가상몰 C")]],
                "description": text(row["description"], 4000),
                "specs": [{"name": name, "value": value} for name, value in dict.fromkeys(
                    (text(spec["name"], 80), text(spec["value"], 400)) for spec in specs)],
                # Only source variants; comparison_configurations are NOT verified seller options.
                "variants": [{"optionId": "qa-option-" + hashlib.sha256(str(v["variant_id"]).encode()).hexdigest()[:16],
                              "label": text(v["name"], 240),
                              "prices": {shop: price_noise(v["sale_price"], product_id, shop)
                                         for shop in ("qa-a", "qa-b", "qa-c")},
                              "purchaseAllowed": None if v["purchase_allowed"] is None else bool(v["purchase_allowed"]),
                              "availability": availability(v["availability"])} for v in variants],
                # Local fixture selection rule only; the real required-option rule must come from Core.
                "optionsRequired": True if len(variants) > 1 else None,
                "optionsComplete": connection.execute(
                    "SELECT count(*) FROM product_variants WHERE product_id = ?", (product_id,)).fetchone()[0] <= 50,
            })
        return {"format": 1, "mode": "QA", "products": products}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Provided SQLite file or clean bundle ZIP")
    args = parser.parse_args()
    output = Path(__file__).resolve().parent.parent / "build" / "qa2" / "catalog.json"
    with tempfile.TemporaryDirectory(prefix="copenhagen-catalog-") as temporary:
        source = args.source
        if source.suffix.lower() == ".zip":
            with zipfile.ZipFile(source) as archive:
                matches = [item for item in archive.infolist() if item.filename.endswith("korean_products_clean.sqlite")]
                if len(matches) != 1 or matches[0].file_size > 50_000_000:
                    raise ValueError("Expected one bounded clean catalog database")
                source = Path(temporary) / "catalog.sqlite"
                source.write_bytes(archive.read(matches[0]))
        payload = convert(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    hosts = sorted({urlsplit(p["imageUrl"]).hostname for p in payload["products"] if p["imageUrl"]})
    print(json.dumps({"products": len(payload["products"]), "imageHosts": hosts, "output": str(output)}))


if __name__ == "__main__":
    main()
