import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("catalog", Path(__file__).with_name("import-qa-catalog.py"))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class CatalogImportTest(unittest.TestCase):
    def test_prices_are_repeatable_integer_qa_values_and_unknown_is_preserved(self):
        self.assertIsNone(catalog.price_noise(None, "p1", "qa-a"))
        self.assertEqual(0, catalog.price_noise(0, "p1", "qa-a"))
        prices = [catalog.price_noise(10000, "p1", shop) for shop in ("qa-a", "qa-b", "qa-c")]
        self.assertGreater(len(set(prices)), 1)
        self.assertTrue(all(9300 <= p <= 10700 and isinstance(p, int) for p in prices))
        self.assertTrue(all(p % 100 == 0 for p in prices))
        self.assertEqual(prices[0], catalog.price_noise(10000, "p1", "qa-a"))
        self.assertEqual("UNKNOWN", catalog.availability("unspecified"))

    def test_import_is_read_only_and_does_not_promote_comparison_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "catalog.sqlite"
            connection = sqlite3.connect(source)
            connection.executescript("""
                CREATE TABLE products(product_id, name, brand, category_path, primary_image_url,
                  sale_price, availability, description);
                CREATE TABLE product_variants(variant_id, product_id, name, sale_price, availability, purchase_allowed);
                CREATE TABLE product_specs(product_id, name, value);
                CREATE TABLE comparison_configurations(product_id, label, price);
                INSERT INTO products VALUES('p1','<script>external text</script>','brand','group > child',
                  'javascript:alert(1)',10000,'unknown','description');
                INSERT INTO comparison_configurations VALUES('p1','Not a verified SKU',1234);
                INSERT INTO product_specs VALUES('p1','size','10cm'),('p1','size','10cm'),('p1','size','20cm');
            """)
            connection.commit()
            connection.close()
            before = source.read_bytes()
            result = catalog.convert(source)["products"][0]
            self.assertEqual(before, source.read_bytes())
            self.assertEqual([], result["variants"])
            self.assertEqual("group", result["category"])
            self.assertEqual("", result["imageUrl"])
            self.assertEqual("<script>external text</script>", result["name"])
            self.assertEqual([{"name": "size", "value": "10cm"}, {"name": "size", "value": "20cm"}], result["specs"])


if __name__ == "__main__":
    unittest.main()
